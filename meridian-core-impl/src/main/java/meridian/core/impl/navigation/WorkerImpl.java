package meridian.core.impl.navigation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import meridian.api.session.ProxySession;
import meridian.core.api.Block;
import meridian.core.api.BlockBox;
import meridian.core.api.BlockView;
import meridian.core.api.PlayerState;
import meridian.core.impl.ContainersImpl;
import meridian.protocol.SmartMoveType;
import meridian.protocol.packets.inventory.SmartMoveItemStack;
import meridian.protocol.packets.window.CloseWindow;
import meridian.core.api.BlockPos;
import meridian.core.api.EntityTracker;
import meridian.core.api.Goal;
import meridian.core.api.InteractionControl;
import meridian.core.api.Movement;
import meridian.core.api.NavResult;
import meridian.core.api.Navigator;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import meridian.core.api.Worker;
import org.slf4j.Logger;

/**
 * The {@link Worker}: one block at a time, broken from a place within reach of the tool that
 * breaks it best - where the body is, or one the {@link Navigator} walks it to - with what
 * {@link Worker.Access} allows broken first; drops walked over into their own cells.
 */
public final class WorkerImpl implements Worker {

    /** The reach taken when core cannot tell the held item's: the server's default use distance, in Adventure. */
    static final double DEFAULT_REACH = 5.0;
    /** The most swings at one block before it is left. */
    static final int MAX_SWINGS = 30;
    /** How many places to stand are tried for one block before it is left. */
    static final int STAND_TRIES = 3;
    /** How long to wait at a drop for the server to take it (a block's drop waits 0.5 s first). */
    static final long PICKUP_WAIT_MS = 1500;
    /** A place to break a block from costs this much more for each block to break first. */
    static final double PER_OBSTACLE = 6.0;
    /** ... and this much for one inside {@link Access#within}, which is dug anyway. */
    static final double PER_OWN_OBSTACLE = 0.5;
    /** A reach is kept this much short, so the dig or the use still lands from where the body ends up. */
    static final double REACH_MARGIN = 0.3;
    /** Once there, a block is still tried this much past the reach it was chosen for. */
    static final double REACH_SLACK = 0.4;
    /** A sweep hitting a block this hard breaks it in a swing or two: a shovel's 0.4-0.5 on soil, not its 0.05 on stone. */
    static final double SWEEP_BREAKS = 0.2;
    /** A block is broken from no farther than this, eyes to its middle, when a place that near will do. */
    static final double COMFORT = 3.0;
    /** ... a place farther costs this much more for each block past it: a player steps up to what they dig. */
    static final double PER_BLOCK_PAST = 1.5;
    /**
     * The line from the eyes to a block is this thick, half each side: a line that only gets through
     * where two blocks meet at an edge or a corner does not get through - a player does not hit
     * through a crack.
     */
    static final double SIGHT_EDGE = 0.12;
    /**
     * A place on the far side of a wall from the body costs this much more for each solid block on the
     * straight line between: it is likely not to be got to, and the walk round is long.
     */
    static final double PER_WALL_BETWEEN = 3.0;
    /** A face aimed at is aimed this far inside the block, from its middle toward the face. */
    static final double FACE_IN = 0.4;
    /** A drop is walked to within this of where it lies, feet to it: inside the server's 1.75. */
    static final double PICKUP_REACH = 1.5;
    /** How long a container is waited for to open, and the stacks moved in to arrive. */
    static final long WINDOW_WAIT_MS = 2500;

    private final Movement movement;
    private final World world;
    private final InteractionControl interactions;
    /** Where the drops lie; {@code null} where core does not offer it - then none are walked to. */
    private final EntityTracker entities;
    private final Navigator navigator;
    /** What is in the containers opened; {@code null} where core does not offer it - then nothing is put in one. */
    private final ContainersImpl containers;
    /** The player's inventory, as the server last sent it. */
    private final PlayerState inventory;
    /** The session packets of the player's own go out on. */
    private final Supplier<ProxySession> session;
    private final Executor executor;
    private final Logger log;
    /** The work under way; a new one, or a cancel, moves it on. */
    private final AtomicInteger work = new AtomicInteger();
    /** The break under way, to stop it on a cancel. */
    private volatile CompletableFuture<?> breaking;

    public WorkerImpl(Movement movement, World world, InteractionControl interactions, EntityTracker entities,
                      Navigator navigator, ContainersImpl containers, PlayerState inventory,
                      Supplier<ProxySession> session, Executor executor, Logger log) {
        this.movement = movement;
        this.world = world;
        this.interactions = interactions;
        this.entities = entities;
        this.navigator = navigator;
        this.containers = containers;
        this.inventory = inventory;
        this.session = session;
        this.executor = executor;
        this.log = log;
    }

    @Override
    public boolean available() {
        return navigator.available();
    }

    @Override
    public void cancel() {
        work.incrementAndGet();
        CompletableFuture<?> b = breaking;
        if (b != null) {
            b.cancel(false);
        }
        navigator.cancel();
    }

    @Override
    public CompletableFuture<Dug> dig(BlockPos target, Access access, Tools tools) {
        Rules how = new Rules(access == null ? Access.ANY : access, tools == null ? Tools.BEST : tools);
        return held(id -> {
            List<BlockPos> broken = new ArrayList<>();
            Outcome o = digNow(id, target, how, broken);
            return new Dug(o, List.copyOf(broken));
        }, new Dug(Outcome.NOT_AVAILABLE, List.of()));
    }

    @Override
    public CompletableFuture<Integer> pickUp(BlockBox area) {
        if (area == null) {
            return CompletableFuture.completedFuture(0);
        }
        return held(id -> collect(id, (e, at) -> area.contains((int) Math.floor(at.x()), (int) Math.floor(at.y()),
                (int) Math.floor(at.z()))), 0);
    }

    @Override
    public CompletableFuture<Integer> pickUp(java.util.Collection<Integer> items) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        Set<Integer> these = new HashSet<>(items);
        return held(id -> collect(id, (e, at) -> these.contains(e)), 0);
    }

    @Override
    public CompletableFuture<Map<String, Integer>> deposit(BlockPos chest, Map<String, Integer> items) {
        return held(id -> depositNow(id, chest, items), Map.of());
    }

    /** What a dig may break on the way, and the tools it takes. */
    private record Rules(Access access, Tools tools) {}

    /** What a piece of work does, with its id: {@link #live} while the id is the work under way. */
    private interface Job<T> {
        T run(int id) throws Exception;
    }

    /**
     * Runs {@code job} off the caller's thread with the body held - a hold of its own, so the
     * caller's (or no one's) stays as it was - and the game taking it back ending the work.
     */
    private <T> CompletableFuture<T> held(Job<T> job, T unavailable) {
        int id = work.incrementAndGet();
        CompletableFuture<?> b = breaking;
        if (b != null) {
            b.cancel(false);
        }
        CompletableFuture<T> done = new CompletableFuture<>();
        executor.execute(() -> {
            Movement.Wheel wheel = movement.available() ? movement.take().orElse(null) : null;
            if (wheel == null) {
                done.complete(unavailable);
                return;
            }
            wheel.released().thenRun(() -> work.compareAndSet(id, id + 1));
            try {
                done.complete(job.run(id));
            } catch (Throwable t) {
                log.warn("meridian-core: work failed", t);
                done.completeExceptionally(t);
            } finally {
                wheel.release();
            }
        });
        return done;
    }

    private boolean live(int id) {
        return work.get() == id;
    }

    // ------------------------------------------------------------------
    // A block
    // ------------------------------------------------------------------

    /**
     * Breaks the block: with the tool that breaks it best, from a place within that tool's reach -
     * what may be broken first, first. A place that cannot be reached, or from where something will
     * not break, is passed for the next.
     */
    private Outcome digNow(int id, BlockPos target, Rules rules, List<BlockPos> broken) throws Exception {
        Access access = rules.access();
        if (!world.known(target.x(), target.y(), target.z()) || world.blockAt(target.x(), target.y(), target.z()).isAir()) {
            return Outcome.NOTHING;
        }
        if (nearLava(target)) {
            return Outcome.LAVA;
        }
        Set<BlockPos> tried = new HashSet<>();
        double reach = equip(target, rules);
        boolean placed = false;
        boolean got = false;
        for (int attempt = 0; attempt < STAND_TRIES && live(id); attempt++) {
            Stand s = stand(access, target, tried, reach);
            if (s == null) {
                break;
            }
            placed = true;
            tried.add(s.cell());
            log.info("meridian-core: {} to be broken from {}{} (cost {})", text(target), text(s.cell()),
                    s.here() ? ", where the body is" : "", String.format(Locale.ROOT, "%.1f", s.cost()));
            if (!s.here()) {
                NavResult r = navigator.goTo(Goal.at(s.cell())).get(3, TimeUnit.MINUTES);
                if (!live(id)) {
                    return Outcome.INTERRUPTED;
                }
                if (r.status() != NavResult.Status.ARRIVED) {
                    log.info("meridian-core: could not get to {} to break {} ({})", text(s.cell()), text(target),
                            r.status());
                    continue;
                }
            }
            got = true;
            if (breakFromHere(id, target, rules, s.passage(), broken)) {
                return Outcome.BROKEN;
            }
        }
        return !live(id) ? Outcome.INTERRUPTED : got ? Outcome.WOULD_NOT_BREAK
                : placed ? Outcome.UNREACHABLE : Outcome.NO_PLACE;
    }

    /**
     * Breaks the way in to the block, then what is still on the line from the eyes to it, then the
     * block - each with its own best tool, and only within that tool's reach.
     */
    private boolean breakFromHere(int id, BlockPos target, Rules rules, List<BlockPos> passage,
                                  List<BlockPos> broken) throws Exception {
        Access access = rules.access();
        for (BlockPos p : passage) {
            if (!live(id)) {
                return false;
            }
            if (world.blockAt(p.x(), p.y(), p.z()).isAir()) {
                continue;
            }
            if (!breakInReach(p, rules, broken)) {
                return false;
            }
        }
        for (int guard = 0; guard < 16 && live(id); guard++) {
            Vec3 eye = eye();
            if (eye == null) {
                return false;
            }
            List<BlockPos> inTheWay = obstacles(eye, target);
            if (inTheWay == null) {
                return false;
            }
            BlockPos next = inTheWay.isEmpty() ? target : inTheWay.get(0);
            if (access.within() != null && !next.equals(target) && !access.within().contains(next)) {
                return false;
            }
            if (!breakInReach(next, rules, broken)) {
                return false;
            }
            if (next.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** Takes the best tool for the block and breaks it, if the body is within that tool's reach. */
    private boolean breakInReach(BlockPos pos, Rules rules, List<BlockPos> broken) throws Exception {
        double reach = equip(pos, rules);
        Vec3 eye = eye();
        if (eye == null || distSq(eye, centre(pos)) > (reach + REACH_SLACK) * (reach + REACH_SLACK)) {
            log.info("meridian-core: {} is out of reach ({})", text(pos), String.format(Locale.ROOT, "%.1f", reach));
            return false;
        }
        if (!breakOne(pos)) {
            return false;
        }
        broken.add(pos);
        return true;
    }

    /**
     * Takes the tool for the block ({@link Tools}); how far a dig with what is held then reaches, as
     * the server's config for it says - a shovel's sweep is short - kept {@link #REACH_MARGIN} short.
     */
    private double equip(BlockPos pos, Rules rules) throws Exception {
        Block b = world.blockAt(pos.x(), pos.y(), pos.z());
        Player player = world.player().orElse(null);
        if (player != null && !b.isAir()) {
            int slot = toolFor(player, pos, b.type(), rules);
            if (slot >= 0 && slot != player.activeHotbarSlot()) {
                interactions.switchHotbarSlot(slot).get(5, TimeUnit.SECONDS);
            }
        }
        String held = player == null ? null : player.heldItem();
        double reach = interactions.digReach(held).orElse(interactions.useReach(held).orElse(DEFAULT_REACH));
        return Math.max(1.0, reach - REACH_MARGIN);
    }

    /**
     * The hotbar slot to break the block with: the tool preferred for its gather type, else the best
     * - near the edge of the box it may break in, one that does not sweep blocks outside it away.
     */
    private int toolFor(Player player, BlockPos pos, BlockView type, Rules rules) {
        BlockBox within = rules.access().within();
        java.util.function.Predicate<String> safe = id -> within == null || !interactions.digSweeps(id)
                || !spills(player, id, pos, within);
        String part = type == null || type.gatherType() == null ? null : rules.tools().prefer().get(type.gatherType());
        if (part != null) {
            int slot = player.bestToolSlot(type, id -> id.contains(part) && safe.test(id));
            if (slot >= 0) {
                return slot;
            }
        }
        int best = player.bestToolSlot(type);
        if (within != null && best >= 0) {
            String bestId = player.hotbarItem(best);
            if (bestId != null && !safe.test(bestId)) {
                int aimed = player.bestToolSlot(type, safe);
                if (aimed >= 0) {
                    return aimed;
                }
            }
        }
        return best;
    }

    /**
     * Whether a dig with this sweeping tool at {@code pos} could break a block just outside the box:
     * one beside it (the 26 around) that the tool breaks in earnest.
     */
    private boolean spills(Player player, String itemId, BlockPos pos, BlockBox within) {
        int slot = player.hotbarSlotOf(itemId);
        if (slot < 0) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = pos.x() + dx;
                    int y = pos.y() + dy;
                    int z = pos.z() + dz;
                    if (within.contains(x, y, z) || !world.known(x, y, z)) {
                        continue;
                    }
                    Block n = world.blockAt(x, y, z);
                    if (!n.isAir() && player.toolPower(slot, n.type()) >= SWEEP_BREAKS) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Turns to the block and swings until it is gone. */
    private boolean breakOne(BlockPos pos) throws Exception {
        if (world.blockAt(pos.x(), pos.y(), pos.z()).isAir()) {
            return true;
        }
        Vec3 eye = eye();
        Vec3 aim = eye == null ? centre(pos) : sight(eye, pos).aim();
        double d = eye == null ? 1 : Math.sqrt(distSq(eye, aim));
        movement.face(aim, Math.max(Movement.FACE_WITHIN, Math.atan(0.25 / Math.max(d, 0.5)))).get(2, TimeUnit.SECONDS);
        CompletableFuture<Boolean> run = interactions.breakBlock(pos, MAX_SWINGS);
        breaking = run;
        try {
            return Boolean.TRUE.equals(run.get(2, TimeUnit.MINUTES));
        } catch (java.util.concurrent.CancellationException cancelled) {
            return false;
        } finally {
            breaking = null;
        }
    }

    // ------------------------------------------------------------------
    // Where to break a block from
    // ------------------------------------------------------------------

    /**
     * A place to stand and break a block from; {@code here} when the body is in it already, and
     * the blocks to break first to make a way in to it ({@link #passage}).
     */
    private record Stand(BlockPos cell, boolean here, double cost, List<BlockPos> passage) {}

    /**
     * The best place to break {@code target} from, not one of {@code tried}: one the body stands in,
     * with its eyes within {@code reach} of the block's middle, not standing on the block itself nor
     * on a block it breaks first. Fewer blocks to break and nearer the body come first; where the
     * body is comes first of all. With {@link Access#within} only blocks inside it may be in the way;
     * with {@link Access#wayIn}, for a block in the rock, the way in is dug the body's height.
     */
    private Stand stand(Access access, BlockPos target, Set<BlockPos> tried, double reach) {
        Vec3 me = movement.position();
        if (me == null) {
            return null;
        }
        int[] here = Walker.cellOf(me);
        double eyeH = movement.capabilities().eyeHeight();
        Vec3 aim = centre(target);
        int r = (int) Math.ceil(reach);
        Stand best = null;
        for (int dy = -r - 1; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos cell = new BlockPos(target.x() + dx, target.y() + dy, target.z() + dz);
                    if (tried.contains(cell)) {
                        continue;
                    }
                    boolean isHere = cell.x() == here[0] && cell.y() == here[1] && cell.z() == here[2];
                    OptionalDouble feet = movement.standAt(cell);
                    if (feet.isEmpty()) {
                        continue;
                    }
                    Vec3 eye = isHere ? new Vec3(me.x(), me.y() + eyeH, me.z())
                            : new Vec3(cell.x() + 0.5, feet.getAsDouble() + eyeH, cell.z() + 0.5);
                    if (distSq(eye, aim) > reach * reach) {
                        continue;
                    }
                    BlockPos under = new BlockPos(cell.x(), (int) Math.floor(feet.getAsDouble() - 1e-3), cell.z());
                    if (under.equals(target)) {
                        continue;
                    }
                    List<BlockPos> inTheWay = obstacles(eye, target);
                    if (inTheWay == null || inTheWay.contains(under)) {
                        continue;
                    }
                    List<BlockPos> passage = List.of();
                    int toBreak = inTheWay.size();
                    if (access.within() != null) {
                        if (!inTheWay.stream().allMatch(access.within()::contains)) {
                            continue;
                        }
                    } else if (!inTheWay.isEmpty() && access.wayIn()) {
                        passage = passage(cell, target);
                        if (passage == null || passage.contains(under)) {
                            continue;
                        }
                        // The way in and what is still on the line: both are broken from here.
                        Set<BlockPos> all = new HashSet<>(passage);
                        all.addAll(inTheWay);
                        toBreak = all.size();
                    }
                    double far = Math.max(0, Math.sqrt(distSq(eye, aim)) - COMFORT) * PER_BLOCK_PAST;
                    far += isHere ? 0 : PER_WALL_BETWEEN * solidBetween(me, new Vec3(cell.x() + 0.5, feet.getAsDouble(),
                            cell.z() + 0.5));
                    // Inside the box what is in the way is dug anyway: breaking it first costs little,
                    // and the body stays rather than walking round to a clear line.
                    double perBlock = access.within() != null ? PER_OWN_OBSTACLE : PER_OBSTACLE;
                    double cost = toBreak * perBlock + Math.sqrt(distSq(me, eye)) - (isHere ? 2 : 0) + far;
                    if (best == null || cost < best.cost()) {
                        best = new Stand(cell, isHere, cost, passage);
                    }
                }
            }
        }
        return best;
    }

    /**
     * The blocks to break for a way in, the body's height, from the cell it stands in to the block:
     * the columns between, a step at a time along x or z, opened at the feet and the head; in the
     * block's own column, what lies between it and the body's height, so its drop falls, or the body
     * drops, to where it can be picked up. Nearest first, the block itself left out; {@code null}
     * when a cell is not known or lava is beside one.
     */
    private List<BlockPos> passage(BlockPos from, BlockPos target) {
        List<BlockPos> cells = new ArrayList<>();
        int x = from.x();
        int z = from.z();
        int y = from.y();
        while (true) {
            int ddx = target.x() - x;
            int ddz = target.z() - z;
            if (Math.abs(ddx) + Math.abs(ddz) <= 1) {
                break;                              // the next column is the block's own
            }
            if (Math.abs(ddx) >= Math.abs(ddz)) {
                x += Integer.signum(ddx);
            } else {
                z += Integer.signum(ddz);
            }
            cells.add(new BlockPos(x, y + 1, z));
            cells.add(new BlockPos(x, y, z));
        }
        if (target.y() > y) {
            for (int k = target.y() - 1; k >= y + 1; k--) {
                cells.add(new BlockPos(target.x(), k, target.z()));
            }
        } else if (target.y() < y) {
            for (int k = y + 1; k >= target.y() + 1; k--) {
                cells.add(new BlockPos(target.x(), k, target.z()));
            }
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : cells) {
            if (c.equals(target)) {
                continue;
            }
            if (!world.known(c.x(), c.y(), c.z())) {
                return null;
            }
            if (world.blockAt(c.x(), c.y(), c.z()).isAir()) {
                continue;
            }
            if (nearLava(c)) {
                return null;
            }
            out.add(c);
        }
        return out;
    }

    /**
     * The blocks on the line from {@code eye} to the middle of {@code target}, nearest the eyes
     * first, the target left out; {@code null} when a cell on it is not known.
     */
    private List<BlockPos> obstacles(Vec3 eye, BlockPos target) {
        return sight(eye, target).inTheWay();
    }

    /** A line from the eyes to a point of a block, and what is in its way; {@code inTheWay} null when unknown. */
    record Sight(Vec3 aim, List<BlockPos> inTheWay) {}

    /**
     * The best line from the eyes to the block: to its middle, or to the middle of a face turned
     * toward the eyes (a little inside it) - the one with the fewest blocks in the way, the middle
     * first when it ties. A player looks at the face they see: a block low in a tunnel's wall is hit on
     * its face, not through the block over it toward its middle.
     */
    private Sight sight(Vec3 eye, BlockPos target) {
        Vec3 middle = centre(target);
        List<BlockPos> best = obstaclesTo(eye, target, middle);
        Vec3 bestAim = middle;
        if (best != null && best.isEmpty()) {
            return new Sight(middle, best);
        }
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] n : faces) {
            double toEye = n[0] * (eye.x() - middle.x()) + n[1] * (eye.y() - middle.y()) + n[2] * (eye.z() - middle.z());
            if (toEye <= 0.5) {
                continue;                                  // the face is turned away, or edge on
            }
            Vec3 aim = new Vec3(middle.x() + n[0] * FACE_IN, middle.y() + n[1] * FACE_IN, middle.z() + n[2] * FACE_IN);
            List<BlockPos> in = obstaclesTo(eye, target, aim);
            if (in != null && (best == null || in.size() < best.size())) {
                best = in;
                bestAim = aim;
                if (in.isEmpty()) {
                    break;
                }
            }
        }
        return new Sight(bestAim, best);
    }

    /** How many solid blocks the straight line between two places goes through, at the body's middle. */
    private int solidBetween(Vec3 a, Vec3 b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double dy = b.y() - a.y();
        int n = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.25));
        Set<Long> seen = new HashSet<>();
        int solid = 0;
        for (int i = 1; i < n; i++) {
            int x = (int) Math.floor(a.x() + dx * i / n);
            int y = (int) Math.floor(a.y() + 1.0 + dy * i / n);
            int z = (int) Math.floor(a.z() + dz * i / n);
            long k = ((long) x << 40) ^ ((long) (y & 0xFFF) << 28) ^ (z & 0xFFFFFFFL);
            if (!seen.add(k) || !world.known(x, y, z)) {
                continue;
            }
            if (movement.cell(new BlockPos(x, y, z)).solid()) {
                solid++;
            }
        }
        return solid;
    }

    /** What is in the way of the line from the eyes to {@code aim}, a point of the target block. */
    private List<BlockPos> obstaclesTo(Vec3 eye, BlockPos target, Vec3 aim) {
        double dx = aim.x() - eye.x();
        double dy = aim.y() - eye.y();
        double dz = aim.z() - eye.z();
        int x = (int) Math.floor(eye.x());
        int y = (int) Math.floor(eye.y());
        int z = (int) Math.floor(eye.z());
        int sx = dx > 0 ? 1 : -1;
        int sy = dy > 0 ? 1 : -1;
        int sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tx = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - eye.x() : eye.x() - x) * tdx;
        double ty = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - eye.y() : eye.y() - y) * tdy;
        double tz = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - eye.z() : eye.z() - z) * tdz;
        List<BlockPos> out = new ArrayList<>();
        double edge = SIGHT_EDGE / Math.max(1e-6, Math.sqrt(dx * dx + dy * dy + dz * dz));
        for (int guard = 0; guard < 64; guard++) {
            if (x == target.x() && y == target.y() && z == target.z()) {
                return out;
            }
            if (!inTheWay(x, y, z, target, out)) {
                return null;
            }
            // Leaving this cell near an edge: the cell across the other face it nearly crosses is in
            // the way as well - the line is SIGHT_EDGE thick, and does not slip between two blocks.
            double t = Math.min(tx, Math.min(ty, tz));
            int step = tx <= ty && tx <= tz ? 0 : ty <= tz ? 1 : 2;
            if (t < 1) {
                // A tie is the line through the very edge: the other cell is checked all the more.
                if (step != 0 && tx - t < edge && !inTheWay(x + sx, y, z, target, out)) {
                    return null;
                }
                if (step != 1 && ty - t < edge && !inTheWay(x, y + sy, z, target, out)) {
                    return null;
                }
                if (step != 2 && tz - t < edge && !inTheWay(x, y, z + sz, target, out)) {
                    return null;
                }
            }
            if (tx <= ty && tx <= tz) {
                x += sx;
                tx += tdx;
            } else if (ty <= tz) {
                y += sy;
                ty += tdy;
            } else {
                z += sz;
                tz += tdz;
            }
            if (Math.min(tx, Math.min(ty, tz)) > 1 + 1e-9 && !(x == target.x() && y == target.y() && z == target.z())) {
                // Past the target's middle without entering its cell: the line ends there.
                return out;
            }
        }
        return out;
    }

    /**
     * Where to stand to pick up a drop lying where the body cannot stand - on a block under a low
     * ceiling, in a hole: of the places round it the body stands in, with the feet within
     * {@link #PICKUP_REACH} of it, the nearest to the body. {@code null} when there is none.
     */
    private Goal pickupFrom(Vec3 drop, Vec3 me) {
        int[] c = Walker.cellOf(drop);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -2; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos cell = new BlockPos(c[0] + dx, c[1] + dy, c[2] + dz);
                    OptionalDouble feet = movement.standAt(cell);
                    if (feet.isEmpty()) {
                        continue;
                    }
                    Vec3 at = new Vec3(cell.x() + 0.5, feet.getAsDouble(), cell.z() + 0.5);
                    if (distSq(at, drop) > PICKUP_REACH * PICKUP_REACH) {
                        continue;
                    }
                    double d = distSq(me, at);
                    if (d < bestD) {
                        bestD = d;
                        best = cell;
                    }
                }
            }
        }
        return best == null ? null : Goal.at(best);
    }

    /**
     * Adds the block at the cell to {@code out} when it is one in the way (not air, not the target);
     * {@code false} when core does not know the cell.
     */
    private boolean inTheWay(int x, int y, int z, BlockPos target, List<BlockPos> out) {
        if (!world.known(x, y, z)) {
            return false;
        }
        Block b = world.blockAt(x, y, z);
        if (!b.isAir()) {
            BlockPos base = b.base();
            if (!base.equals(target) && !out.contains(base)) {
                out.add(base);
            }
        }
        return true;
    }

    /** Whether lava is next to the block, so breaking it would let it in. */
    private boolean nearLava(BlockPos p) {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            Movement.Cell c = movement.cell(new BlockPos(p.x() + d[0], p.y() + d[1], p.z() + d[2]));
            if (c != null && c.fluid() != null && c.hurts()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // The drops
    // ------------------------------------------------------------------

    /** Walks over the drops in the area, nearest first, until none is left that can be got to. */
    /** The drops {@code which} takes, by entity and where it lies, walked over nearest first. */
    private Integer collect(int id, java.util.function.BiPredicate<Integer, Vec3> which) throws Exception {
        if (entities == null) {
            return 0;
        }
        int picked = 0;
        Set<Integer> passed = new HashSet<>();
        Set<Integer> retried = new HashSet<>();
        for (int round = 0; round < 256 && live(id); round++) {
            Vec3 me = movement.position();
            if (me == null) {
                break;
            }
            int best = -1;
            double bestD = Double.MAX_VALUE;
            Vec3 bestAt = null;
            for (int e : entities.trackedEntities()) {
                if (passed.contains(e) || entities.itemOf(e).isEmpty()) {
                    continue;
                }
                Vec3 at = entities.positionOf(e).orElse(null);
                if (at == null || !which.test(e, at)) {
                    continue;
                }
                double d = distSq(me, at);
                if (d < bestD) {
                    bestD = d;
                    best = e;
                    bestAt = at;
                }
            }
            if (best < 0) {
                break;
            }
            int[] c = Walker.cellOf(bestAt);
            BlockPos cell = new BlockPos(c[0], c[1], c[2]);
            Goal goal = movement.standAt(cell).isPresent() ? Goal.at(cell) : pickupFrom(bestAt, me);
            if (goal == null) {
                passed.add(best);
                continue;
            }
            NavResult r = navigator.goTo(goal).get(3, TimeUnit.MINUTES);
            if (!live(id)) {
                break;
            }
            boolean gone = false;
            for (long waited = 0; waited < PICKUP_WAIT_MS && !gone; waited += 100) {
                Thread.sleep(100);
                gone = entities.itemOf(best).isEmpty();
            }
            if (gone) {
                picked++;
            } else {
                // Not taken from there: tried once more if it has moved, else left.
                Vec3 now = entities.positionOf(best).orElse(null);
                boolean moved = now != null && distSq(now, bestAt) > 0.25;
                if (!moved || r.status() != NavResult.Status.ARRIVED || !retried.add(best)) {
                    passed.add(best);
                }
            }
        }
        return picked;
    }

    // ------------------------------------------------------------------
    // A container
    // ------------------------------------------------------------------

    /** The player's own sections the stacks go from, by the names core keeps them under, with their ids. */
    private static final Map<String, Integer> SECTIONS = Map.of("Hotbar", -1, "Storage", -2, "Backpack", -9);

    /**
     * Goes to the container, opens it, moves the stacks in as a shift-click does ({@code
     * PutInHotbarOrWindow}: from the hotbar or the storage into the open window first) and closes
     * it; how many of each went in, by what the player holds before and after.
     */
    private Map<String, Integer> depositNow(int id, BlockPos chest, Map<String, Integer> want) throws Exception {
        ProxySession s = session.get();
        if (containers == null || inventory == null || s == null || want == null || want.isEmpty()
                || !world.known(chest.x(), chest.y(), chest.z()) || world.blockAt(chest.x(), chest.y(), chest.z()).isAir()) {
            return Map.of();
        }
        Vec3 eye = eye();
        if (eye == null) {
            return Map.of();
        }
        Player player = world.player().orElse(null);
        double reach = Math.max(1.0, interactions.useReach(player == null ? null : player.heldItem())
                .orElse(DEFAULT_REACH) - REACH_MARGIN);
        if (distSq(eye, centre(chest)) > reach * reach) {
            NavResult r = navigator.goTo(Goal.near(chest, 2)).get(3, TimeUnit.MINUTES);
            if (!live(id) || r.status() != NavResult.Status.ARRIVED) {
                return Map.of();
            }
        }
        Map<String, Integer> before = held();
        movement.face(chest).get(2, TimeUnit.SECONDS);
        int window = containers.windowAt(chest);
        if (window < 0) {
            containers.reaching(chest);
            interactions.useOnBlock(chest).get(10, TimeUnit.SECONDS);
            for (long waited = 0; waited < WINDOW_WAIT_MS && window < 0; waited += 100) {
                Thread.sleep(100);
                window = containers.windowAt(chest);
            }
        }
        if (window < 0) {
            log.info("meridian-core: the container at {} did not open", text(chest));
            return Map.of();
        }
        try {
            Map<String, Integer> left = new HashMap<>(want);
            for (Map.Entry<String, PlayerState.Section> e : inventory.inventories().entrySet()) {
                Integer section = SECTIONS.get(e.getKey());
                if (section == null) {
                    continue;
                }
                for (Map.Entry<Integer, meridian.core.api.Containers.Item> slot : e.getValue().items().entrySet()) {
                    meridian.core.api.Containers.Item item = slot.getValue();
                    int need = item == null ? 0 : left.getOrDefault(item.id(), 0);
                    if (need <= 0) {
                        continue;
                    }
                    int n = Math.min(need, item.quantity());
                    SmartMoveItemStack move = new SmartMoveItemStack();
                    move.fromSectionId = section;
                    move.fromSlotId = slot.getKey();
                    move.quantity = n;
                    move.moveType = SmartMoveType.PutInHotbarOrWindow;
                    s.sendToServer(move);
                    left.put(item.id(), need - n);
                }
            }
            // What went in shows as what the player no longer holds.
            Map<String, Integer> moved = Map.of();
            for (long waited = 0; waited < WINDOW_WAIT_MS; waited += 100) {
                Thread.sleep(100);
                Map<String, Integer> now = moved(before, held(), want);
                if (now.equals(moved) && !now.isEmpty() && waited >= 500) {
                    break;
                }
                moved = now;
            }
            return moved;
        } finally {
            close(s, window);
        }
    }

    /** Closes a window the player has open: the server told, and a game shown it shut. */
    private void close(ProxySession s, int window) {
        CloseWindow close = new CloseWindow();
        close.id = window;
        s.sendToServer(close);
        boolean headless;
        try {
            headless = s.headless() != null;
        } catch (NoSuchMethodError olderProxy) {
            headless = false;
        }
        if (!headless) {
            CloseWindow shut = new CloseWindow();
            shut.id = window;
            s.sendToClient(shut);
        }
        containers.closed(window);
    }

    /** How many of each item the player holds, over the hotbar, the storage and the backpack. */
    private Map<String, Integer> held() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, PlayerState.Section> e : inventory.inventories().entrySet()) {
            if (!SECTIONS.containsKey(e.getKey())) {
                continue;
            }
            for (meridian.core.api.Containers.Item item : e.getValue().items().values()) {
                if (item != null) {
                    out.merge(item.id(), item.quantity(), Integer::sum);
                }
            }
        }
        return out;
    }

    /** How many of each wanted item the player holds fewer of now. */
    private static Map<String, Integer> moved(Map<String, Integer> before, Map<String, Integer> after,
                                              Map<String, Integer> want) {
        Map<String, Integer> out = new HashMap<>();
        for (String item : want.keySet()) {
            int gone = before.getOrDefault(item, 0) - after.getOrDefault(item, 0);
            if (gone > 0) {
                out.put(item, gone);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------

    private Vec3 eye() {
        Vec3 feet = movement.position();
        return feet == null ? null : new Vec3(feet.x(), feet.y() + movement.capabilities().eyeHeight(), feet.z());
    }

    private static Vec3 centre(BlockPos p) {
        return new Vec3(p.x() + 0.5, p.y() + 0.5, p.z() + 0.5);
    }

    private static double distSq(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static String text(BlockPos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }
}
