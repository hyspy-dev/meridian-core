package meridian.core.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import meridian.api.module.Scheduler;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.core.api.Face;
import meridian.core.api.InteractionOutcome;
import meridian.core.api.InteractionControl;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import meridian.core.impl.movement.Terrain;
import meridian.protocol.BlockType;

/**
 * Live {@link World} — the position-addressed facade over the proxy's trackers.
 *
 * <p>Blocks come from {@link ChunkTracker} (live ids) resolved through
 * {@link WorldStateImpl} (the type catalog); actions are forged through
 * {@link InteractionControl}. A Layer-2 module queries blocks and acts on them,
 * writing its own scan / decision logic. Where the player's body can stand, fit or land is
 * {@link Terrain}'s, the same rules core's own movement goes by.
 */
public final class WorldImpl implements World {
    private final ChunkTracker chunks;
    private final WorldStateImpl worldState;
    private final Terrain terrain;
    private final InteractionControl interactions;
    private final Scheduler scheduler;
    private final PlayerImpl player;

    public WorldImpl(ChunkTracker chunks, WorldStateImpl worldState, Terrain terrain,
                     EntityTrackerImpl entities, InventoryTracker inventory,
                     InteractionControl interactions, MovementControlImpl movement,
                     Scheduler scheduler, DoubleSupplier eyeHeight, ItemRegistry items) {
        this.chunks = chunks;
        this.worldState = worldState;
        this.terrain = terrain;
        this.interactions = interactions;
        this.scheduler = scheduler;
        this.player = new PlayerImpl(entities, inventory, interactions, movement, eyeHeight, items, worldState);
        // Late binding — PlayerImpl needs us for lookedAtBlock's raycast.
        // Doing this after the fields are assigned keeps the leaked-this
        // window as narrow as possible.
        this.player.bindWorld(this);
    }

    @Override
    public Block blockAt(int x, int y, int z) {
        int id = chunks.blockIdAt(x, y, z);
        return new BlockImpl(x, y, z, id, view(id), id > 0 ? chunks.rotationAt(x, y, z) : 0,
                id > 0 ? chunks.fillerAt(x, y, z) : 0, interactions);
    }

    /** The type of a block id, or {@code null} for air, the unknown, or a type not yet sent. */
    private BlockView view(int id) {
        BlockType blockType = id > 0 ? worldState.blockTypeById(id) : null;
        return blockType == null ? null : new BlockViewImpl(id, blockType, worldState.itemCategories(), worldState.flips());
    }

    @Override
    public boolean known(int x, int y, int z) {
        return chunks.known(x, y, z);
    }

    @Override
    public List<Block> find(Vec3 from, double radius, Predicate<BlockView> type, int limit) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(type, "type");
        TypeTest test = new TypeTest(type);
        List<ChunkTracker.Found> found = chunks.find(from.x(), from.y(), from.z(), radius, test::accepts, limit);
        List<Block> out = new ArrayList<>(found.size());
        for (ChunkTracker.Found f : found) {
            // Found at its own cell: no filler, by the search's rule.
            out.add(new BlockImpl(f.x(), f.y(), f.z(), f.id(), test.views.get(f.id()),
                    chunks.rotationAt(f.x(), f.y(), f.z()), 0, interactions));
        }
        return out;
    }

    /**
     * A search's type test, asked once per block id: a block of a type not yet sent is never
     * accepted, there being nothing to judge it by.
     */
    private final class TypeTest {
        /** Ids past this are kept in {@link #far} instead of the array. */
        private static final int ARRAY_IDS = 1 << 16;

        private final Predicate<BlockView> type;
        /** By id: 0 not asked yet, 1 accepted, 2 not. */
        private byte[] answers = new byte[1024];
        private final Map<Integer, Boolean> far = new HashMap<>();
        /** The types accepted, by id - what the blocks found are handed back with. */
        final Map<Integer, BlockView> views = new HashMap<>();

        TypeTest(Predicate<BlockView> type) {
            this.type = type;
        }

        boolean accepts(int id) {
            if (id >= ARRAY_IDS) {
                return far.computeIfAbsent(id, this::ask);
            }
            if (id >= answers.length) {
                answers = Arrays.copyOf(answers, Math.min(ARRAY_IDS, Math.max(id + 1, answers.length * 2)));
            }
            byte a = answers[id];
            if (a == 0) {
                boolean yes = ask(id);
                answers[id] = (byte) (yes ? 1 : 2);
                return yes;
            }
            return a == 1;
        }

        private boolean ask(int id) {
            BlockView v = view(id);
            boolean yes = v != null && type.test(v);
            if (yes) {
                views.put(id, v);
            }
            return yes;
        }
    }

    @Override
    public CompletableFuture<Optional<Block>> awaitBlock(BlockPos pos, Predicate<Block> until, Duration timeout) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(until, "until");
        CompletableFuture<Optional<Block>> done = new CompletableFuture<>();
        // Watched before the first look, so a change between the two is not missed.
        ChunkTracker.CellWatch watch = chunks.watchCell(pos.x(), pos.y(), pos.z(), () -> {
            if (done.isDone()) {
                return;
            }
            // Judged here, on the feed, so the block is the one the change made; answered on the
            // scheduler, so the caller's follow-up never runs on a network thread.
            Block b = blockAt(pos.x(), pos.y(), pos.z());
            try {
                if (until.test(b)) {
                    later(() -> done.complete(Optional.of(b)));
                }
            } catch (RuntimeException e) {
                later(() -> done.completeExceptionally(e));
            }
        });
        done.whenComplete((v, e) -> watch.close());
        Block now = blockAt(pos.x(), pos.y(), pos.z());
        try {
            if (until.test(now)) {
                done.complete(Optional.of(now));
                return done;
            }
        } catch (RuntimeException e) {
            done.completeExceptionally(e);
            return done;
        }
        Duration wait = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
        try {
            ScheduledFuture<?> timer = scheduler.schedule(() -> done.complete(Optional.empty()), wait);
            done.whenComplete((v, e) -> timer.cancel(false));
        } catch (RejectedExecutionException shutDown) {
            done.complete(Optional.empty());
        }
        return done;
    }

    @Override
    public OptionalDouble standAt(BlockPos cell) {
        Objects.requireNonNull(cell, "cell");
        return terrain.standAt(cell.x(), cell.y(), cell.z());
    }

    @Override
    public boolean fits(Vec3 feet, double height) {
        Objects.requireNonNull(feet, "feet");
        return terrain.fits(feet.x(), feet.y(), feet.z(), height);
    }

    @Override
    public boolean hurtsAt(Vec3 feet) {
        Objects.requireNonNull(feet, "feet");
        return terrain.hurts(feet.x(), feet.y(), feet.z());
    }

    @Override
    public OptionalInt surfaceAt(int x, int z) {
        return terrain.surfaceAt(x, z);
    }

    @Override
    public CompletableFuture<OptionalInt> awaitSurface(int x, int z, Duration timeout) {
        CompletableFuture<OptionalInt> done = new CompletableFuture<>();
        // Watched before the first look, so a section arriving between the two is not missed.
        ChunkTracker.CellWatch watch = chunks.watchColumn(x, z, () -> {
            if (done.isDone()) {
                return;
            }
            OptionalInt y = terrain.surfaceAt(x, z);
            if (y.isPresent()) {
                later(() -> done.complete(y));
            }
        });
        done.whenComplete((v, e) -> watch.close());
        OptionalInt now = terrain.surfaceAt(x, z);
        if (now.isPresent()) {
            done.complete(now);
            return done;
        }
        Duration wait = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
        try {
            ScheduledFuture<?> timer = scheduler.schedule(() -> done.complete(OptionalInt.empty()), wait);
            done.whenComplete((v, e) -> timer.cancel(false));
        } catch (RejectedExecutionException shutDown) {
            done.complete(OptionalInt.empty());
        }
        return done;
    }

    /** Where a block placed into a cell is set against: below first, then the sides, then above. */
    private static final int[][] PLACE_AGAINST = {
            {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}
    };

    @Override
    public CompletableFuture<InteractionOutcome> placeAt(BlockPos cell, Face orient) {
        if (cell == null || !known(cell.x(), cell.y(), cell.z()) || !blockAt(cell.x(), cell.y(), cell.z()).isAir()) {
            return CompletableFuture.completedFuture(InteractionOutcome.notSent());
        }
        for (int[] d : PLACE_AGAINST) {
            int nx = cell.x() + d[0];
            int ny = cell.y() + d[1];
            int nz = cell.z() + d[2];
            if (!known(nx, ny, nz)) {
                continue;
            }
            Block neighbour = blockAt(nx, ny, nz);
            if (neighbour.isAir()) {
                continue;
            }
            // The neighbour's face that looks at the cell: cell - neighbour.
            Face against = Face.ofDelta(-d[0], -d[1], -d[2]);
            return neighbour.place(against, orient != null ? orient : against);
        }
        return CompletableFuture.completedFuture(InteractionOutcome.notSent());
    }

    @Override
    public Optional<Vec3> safeSpotNear(int x, int y, int z, int radius) {
        return terrain.safeSpotNear(x, y, z, radius);
    }

    /** Runs on the scheduler, or here when it is shut down (the module torn down). */
    private void later(Runnable r) {
        try {
            scheduler.schedule(r, Duration.ZERO);
        } catch (RejectedExecutionException shutDown) {
            r.run();
        }
    }

    @Override
    public String fluidAt(int x, int y, int z) {
        return chunks.fluidNameAt(x, y, z);
    }

    @Override
    public meridian.core.api.FluidCell fluidCellAt(int x, int y, int z) {
        return chunks.fluidCellAt(x, y, z);
    }

    @Override
    public int fluidId(String name) {
        return chunks.fluidIdByName(name);
    }

    @Override
    public Optional<Player> player() {
        return interactions.available() ? Optional.of(player) : Optional.empty();
    }
}
