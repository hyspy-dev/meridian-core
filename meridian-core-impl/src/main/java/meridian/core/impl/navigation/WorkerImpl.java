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
import meridian.core.api.Block;
import meridian.core.api.BlockBox;
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

    /** A block is broken from no farther than this, eyes to its middle - inside the game's own reach of 5. */
    static final double REACH = 4.5;
    /** The most swings at one block before it is left. */
    static final int MAX_SWINGS = 30;
    /** How many places to stand are tried for one block before it is left. */
    static final int STAND_TRIES = 3;
    /** A drop is walked to within this when its own cell cannot be stood in; the server picks it up within 1.75 of the feet. */
    static final double PICKUP_NEAR = 1.0;
    /** How long to wait at a drop for the server to take it (a block's drop waits 0.5 s first). */
    static final long PICKUP_WAIT_MS = 1500;
    /** A place to break a block from costs this much more for each block to break first. */
    static final double PER_OBSTACLE = 6.0;
    /** A tool's reach is kept this much short, so the dig still lands from where the body ends up. */
    static final double REACH_MARGIN = 0.3;
    /** Once there, a block is still tried this much past the reach it was chosen for. */
    static final double REACH_SLACK = 0.4;

    private final Movement movement;
    private final World world;
    private final InteractionControl interactions;
    /** Where the drops lie; {@code null} where core does not offer it - then none are walked to. */
    private final EntityTracker entities;
    private final Navigator navigator;
    private final Executor executor;
    private final Logger log;
    /** The work under way; a new one, or a cancel, moves it on. */
    private final AtomicInteger work = new AtomicInteger();
    /** The break under way, to stop it on a cancel. */
    private volatile CompletableFuture<?> breaking;

    public WorkerImpl(Movement movement, World world, InteractionControl interactions, EntityTracker entities,
                      Navigator navigator, Executor executor, Logger log) {
        this.movement = movement;
        this.world = world;
        this.interactions = interactions;
        this.entities = entities;
        this.navigator = navigator;
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
    public CompletableFuture<Dug> dig(BlockPos target, Access access) {
        Access how = access == null ? Access.ANY : access;
        return held(id -> {
            List<BlockPos> broken = new ArrayList<>();
            Outcome o = digNow(id, target, how, broken);
            return new Dug(o, List.copyOf(broken));
        }, new Dug(Outcome.NOT_AVAILABLE, List.of()));
    }

    @Override
    public CompletableFuture<Integer> pickUp(BlockBox area) {
        return held(id -> collect(id, area), 0);
    }

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
    private Outcome digNow(int id, BlockPos target, Access access, List<BlockPos> broken) throws Exception {
        if (!world.known(target.x(), target.y(), target.z()) || world.blockAt(target.x(), target.y(), target.z()).isAir()) {
            return Outcome.NOTHING;
        }
        if (nearLava(target)) {
            return Outcome.LAVA;
        }
        Set<BlockPos> tried = new HashSet<>();
        double reach = equip(target);
        boolean placed = false;
        boolean got = false;
        for (int attempt = 0; attempt < STAND_TRIES && live(id); attempt++) {
            Stand s = stand(access, target, tried, reach);
            if (s == null) {
                break;
            }
            placed = true;
            tried.add(s.cell());
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
            if (breakFromHere(id, target, access, s.passage(), broken)) {
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
    private boolean breakFromHere(int id, BlockPos target, Access access, List<BlockPos> passage,
                                  List<BlockPos> broken) throws Exception {
        for (BlockPos p : passage) {
            if (!live(id)) {
                return false;
            }
            if (world.blockAt(p.x(), p.y(), p.z()).isAir()) {
                continue;
            }
            if (!breakInReach(p, broken)) {
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
            if (!breakInReach(next, broken)) {
                return false;
            }
            if (next.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** Takes the best tool for the block and breaks it, if the body is within that tool's reach. */
    private boolean breakInReach(BlockPos pos, List<BlockPos> broken) throws Exception {
        double reach = equip(pos);
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
     * Takes the hotbar's tool that breaks the block best; how far a dig with what is held then
     * reaches - a shovel's sweep is short - inside {@link #REACH}.
     */
    private double equip(BlockPos pos) throws Exception {
        Block b = world.blockAt(pos.x(), pos.y(), pos.z());
        Player player = world.player().orElse(null);
        if (player != null && !b.isAir()) {
            int slot = player.bestToolSlot(b.type());
            if (slot >= 0 && slot != player.activeHotbarSlot()) {
                interactions.switchHotbarSlot(slot).get(5, TimeUnit.SECONDS);
            }
        }
        String held = player == null ? null : player.heldItem();
        double reach = interactions.digReach(held).orElse(REACH);
        return Math.max(1.0, Math.min(REACH, reach - REACH_MARGIN));
    }

    /** Turns to the block and swings until it is gone. */
    private boolean breakOne(BlockPos pos) throws Exception {
        if (world.blockAt(pos.x(), pos.y(), pos.z()).isAir()) {
            return true;
        }
        movement.face(pos).get(2, TimeUnit.SECONDS);
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
                        toBreak = passage.size();
                    }
                    double cost = toBreak * PER_OBSTACLE + Math.sqrt(distSq(me, eye)) - (isHere ? 2 : 0);
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
        Vec3 aim = centre(target);
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
        for (int guard = 0; guard < 64; guard++) {
            if (x == target.x() && y == target.y() && z == target.z()) {
                return out;
            }
            if (!world.known(x, y, z)) {
                return null;
            }
            Block b = world.blockAt(x, y, z);
            if (!b.isAir()) {
                BlockPos base = b.base();
                if (!base.equals(target) && !out.contains(base)) {
                    out.add(base);
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
    private Integer collect(int id, BlockBox area) throws Exception {
        if (entities == null || area == null) {
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
                if (at == null || !area.contains((int) Math.floor(at.x()), (int) Math.floor(at.y()),
                        (int) Math.floor(at.z()))) {
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
            Goal goal = movement.standAt(cell).isPresent() ? Goal.at(cell) : Goal.near(cell, PICKUP_NEAR);
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
