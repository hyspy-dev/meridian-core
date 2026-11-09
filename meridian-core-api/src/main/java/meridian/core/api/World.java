package meridian.core.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * The live world — the proxy's mirror of blocks and the local player.
 *
 * <p>A meridian-core Layer-1 service and a building block, not a feature: a
 * Layer-2 module queries it and acts through it, writing its own logic. Core
 * only removes the packet plumbing — it does not encapsulate behaviour.
 *
 * <pre>{@code
 * World world = ctx.services().require(World.class);
 * Vec3 p = world.player().orElseThrow().position();
 * for (int dx = -4; dx <= 4; dx++) {
 *     for (int dz = -4; dz <= 4; dz++) {
 *         Block b = world.blockAt((int) p.x() + dx, (int) p.y(), (int) p.z() + dz);
 *         if (b.type() != null && b.type().name().contains("Flower")) {
 *             b.use();
 *         }
 *     }
 * }
 * }</pre>
 */
public interface World {

    /**
     * The block at the given world coordinates. Never {@code null}; a block in
     * an unloaded chunk reports {@link Block#isAir()} and a {@code null} type -
     * as air does, so ask {@link #known} to tell the two apart.
     */
    Block blockAt(int x, int y, int z);

    /**
     * Whether core has been sent the blocks of this cell: its section came, or the server said the
     * section holds nothing (an older server, which does not say so, leaves such a section out of a
     * column it sends). Where this is false, {@link #blockAt} reads air because nothing is known
     * there, not because the cell is empty.
     *
     * <p>A core too old to tell answers true only where a block stands, the one thing that proves
     * the chunk was sent.
     */
    default boolean known(int x, int y, int z) {
        return blockAt(x, y, z).type() != null;
    }

    /**
     * The blocks within {@code radius} of {@code from} whose type {@code type} accepts, nearest
     * first by the distance from {@code from} to the middle of the block, at most {@code limit} of
     * them.
     *
     * <p>Only what core has been sent is searched. Air is never offered to {@code type}, nor a block
     * whose type the server has not described yet. A block bigger than one cell - a door, a bed -
     * is found once, at the cell it stands on ({@link Block#base()}), never at the cells it only
     * fills. {@code type} is asked once per kind of block in a call, not once per block, so it can
     * afford to look at names and categories:
     *
     * <pre>{@code
     * List<Block> ores = world.find(player.position(), 32,
     *         t -> t.categories().contains("Blocks.Ores"), 10);
     * }</pre>
     *
     * <p>A core too old to search answers with an empty list.
     *
     * @param from   where distances are measured from - the feet, the eyes
     * @param radius how far out to look, in blocks
     * @param type   which kinds of block count
     * @param limit  the most blocks to return
     * @return the blocks found, nearest first; empty when none are, or {@code limit} is under 1
     */
    default List<Block> find(Vec3 from, double radius, Predicate<BlockView> type, int limit) {
        return List.of();
    }

    /**
     * Waits until the block at {@code pos} is one {@code until} accepts - a block the server breaks,
     * places or turns, or a chunk that arrives.
     *
     * <p>{@code until} is asked at once, then each time the server changes that cell or sends the
     * chunk it is in; there is no polling. It is asked on the thread the change arrives on, so it
     * should only look at the block it is given. The future completes on core's scheduler thread,
     * or at once when the block is already one {@code until} accepts.
     *
     * <pre>{@code
     * world.awaitBlock(pos, Block::isAir, Duration.ofSeconds(2))
     *      .thenAccept(b -> log.info(b.isPresent() ? "gone" : "still there"));
     * }</pre>
     *
     * <p>A core too old to wait answers at once, from the block as it is now.
     *
     * @param pos     the cell to watch
     * @param until   what the block has to be
     * @param timeout how long to wait at most
     * @return the block as {@code until} accepted it, or empty when {@code timeout} ran out first
     */
    default CompletableFuture<Optional<Block>> awaitBlock(BlockPos pos, Predicate<Block> until,
                                                          Duration timeout) {
        Block now = blockAt(pos.x(), pos.y(), pos.z());
        return CompletableFuture.completedFuture(
                until.test(now) ? Optional.of(now) : Optional.empty());
    }

    /**
     * The name of the fluid at a world position - {@code "Water"}, {@code "Lava"}, ... - or
     * {@code null} when there is no fluid there (or the section is not loaded).
     *
     * <p>Fluids are a layer of their own: {@link #blockAt} reads air where water or lava is, so a
     * scan for the surface would land on the floor of a pond. Ask here as well to lift clear of
     * one, or to keep out of lava.
     */
    String fluidAt(int x, int y, int z);

    /**
     * The fluid at a world position with its id and level, or {@code null} when there is none
     * (or the section is not loaded) - what copying a fluid needs, where {@link #fluidAt} only
     * names it. A source and its flow are different fluids ({@code Water_Source} and
     * {@code Water}); the level is the server's, kept as it sent it.
     */
    default FluidCell fluidCellAt(int x, int y, int z) {
        return null;
    }

    /**
     * The id this connection numbers a fluid by, from its name ({@code "Water_Source"}), or
     * {@code -1} when the catalog has no such fluid (or has not arrived yet).
     */
    default int fluidId(String name) {
        return -1;
    }

    // ------------------------------------------------------------------
    // Standing: where the player's body can be, for any player
    // ------------------------------------------------------------------
    //
    // The body is the player's own, as the model the server sent for it has it (wide 0.65, tall
    // 1.85 for the default player). These answer for a game's player as well as for a headless one.

    /**
     * Where the player's feet are when it stands in cell {@code cell} - on the floor in it or under
     * it (a full block under it, a slab in it), by the blocks' own collision boxes, with room for the
     * whole body above, no fluid at the head and nothing burning at the feet - or empty when it
     * cannot stand there, or core has not been sent that part of the world.
     *
     * <p>A core too old to tell answers empty.
     */
    default OptionalDouble standAt(BlockPos cell) {
        return OptionalDouble.empty();
    }

    /**
     * Whether the player's body, centred at {@code feet} and {@code height} tall, hits no solid
     * block - and false where core has not been sent the world.
     *
     * <p>A core too old to tell answers false.
     */
    default boolean fits(Vec3 feet, double height) {
        return false;
    }

    /**
     * Whether the player's body with its feet at {@code feet} is in something that hurts - lava,
     * fire and their kin - in any cell of its column from the feet to the head.
     *
     * <p>A core too old to tell answers false.
     */
    default boolean hurtsAt(Vec3 feet) {
        return false;
    }

    /**
     * The height to stand at on top of the column {@code (x, z)}: over the highest cell holding a
     * block that is not air, or a fluid - one above a block, two above a fluid, so a drop onto a
     * pond clears it. A canopy over the ground is the top, as it is seen from the sky.
     *
     * <p>Empty while it cannot be told: core has nothing of the column yet, or what lies over the
     * highest block it has is not known yet - that part may still turn out to be the ground. Wait
     * for it with {@link #awaitSurface}.
     *
     * <p>A core too old to tell answers empty.
     */
    default OptionalInt surfaceAt(int x, int z) {
        return OptionalInt.empty();
    }

    /**
     * Waits until {@link #surfaceAt} can tell the column {@code (x, z)}: asked at once, then each
     * time the server sends or changes something of that column; there is no polling. The future
     * completes on core's scheduler thread, or at once when the surface is known already.
     *
     * <pre>{@code
     * world.awaitSurface(x, z, Duration.ofSeconds(2))
     *      .thenAccept(y -> y.ifPresent(feet -> player.teleport(new Vec3(x + 0.5, feet, z + 0.5))));
     * }</pre>
     *
     * <p>A core too old to wait answers at once, from what it knows now.
     *
     * @return the standing height, or empty when {@code timeout} ran out first
     */
    default CompletableFuture<OptionalInt> awaitSurface(int x, int z, Duration timeout) {
        return CompletableFuture.completedFuture(surfaceAt(x, z));
    }

    /**
     * The nearest safe place to stand around {@code (x, y, z)}: searched in square rings out to
     * {@code radius} columns - the column itself first, so a straight drop wins when there is one -
     * and in each column straight down from {@code y}, then up. Of one ring's columns the nearest
     * wins, the height counting too.
     *
     * <p>Safe is solid ground with nothing burning in it, and above it as many cells as the body is
     * tall clear of solid blocks and of any fluid: not in water, not in lava. Only what core knows
     * counts; a cell not sent yet is never taken for open air.
     *
     * <p>A core too old to tell answers empty.
     *
     * @return the feet there - the middle of the column, on top of the ground - or empty when no
     *         column within {@code radius} has such a place
     */
    default Optional<Vec3> safeSpotNear(int x, int y, int z, int radius) {
        return Optional.empty();
    }

    /** The local player, or empty until a client session is live. */
    Optional<Player> player();
}
