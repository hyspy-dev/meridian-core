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
import meridian.core.api.Face;
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
    /** How often a dig is begun again after an urgent hold broke into it. */
    static final int URGENT_RETRIES = 4;
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
    /** Core's urgent holds: a job begun before one waits while it is held ({@link Movement#takeFirst}). */
    private final meridian.core.impl.movement.Urgency urgency;
    /** The game's items, for a tool's break shape ({@link #shapeCells}); {@code null}: none known. */
    private volatile java.util.function.Function<String, java.util.Optional<meridian.protocol.ItemBase>> items;

    /** The game's items, by id: a tool's break shape is read from them. */
    public void items(java.util.function.Function<String, java.util.Optional<meridian.protocol.ItemBase>> items) {
        this.items = items;
    }

    /** The stamp of the job the calling thread runs ({@link #held}); {@code null} off a job's thread. */
    private static final ThreadLocal<Long> BORN = new ThreadLocal<>();

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
        this.urgency = movement instanceof meridian.core.impl.movement.MovementImpl m ? m.urgency()
                : new meridian.core.impl.movement.Urgency();
    }

    /**
     * Waits while an urgent hold holds this job back - one taken after the job began
     * ({@link Movement#takeFirst}).
     *
     * @return whether it waited: the body may be somewhere else now
     */
    private boolean pause() throws InterruptedException {
        Long born = BORN.get();
        if (born == null || !urgency.awaitTurn(born)) {
            return false;
        }
        log.info("meridian-core: the work goes on after an urgent hold");
        return true;
    }

    /** A trip of the job's, once nothing holds the job back. */
    private CompletableFuture<NavResult> go(Goal goal) throws InterruptedException {
        pause();
        return navigator.goTo(goal);
    }

    /** A turn of the head for the job, once nothing holds the job back. */
    private CompletableFuture<Boolean> faceNow(Vec3 point, double within) throws InterruptedException {
        pause();
        return movement.face(point, within);
    }

    private CompletableFuture<Boolean> faceNow(BlockPos block) throws InterruptedException {
        pause();
        return movement.face(block);
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
    public boolean digsFromHere(BlockPos target, Access access, Tools tools) {
        if (target == null || !world.known(target.x(), target.y(), target.z())) {
            return false;
        }
        Block b = world.blockAt(target.x(), target.y(), target.z());
        if (b.isAir() || nearLava(target)) {
            return false;
        }
        Rules rules = new Rules(access == null ? Access.ANY : access, tools == null ? Tools.BEST : tools);
        // The tool dig would take, without taking it: its reach is the one the place is chosen by.
        Player player = world.player().orElse(null);
        String item = null;
        if (player != null) {
            int slot = toolFor(player, target, b.type(), rules);
            item = slot >= 0 ? player.hotbarItem(slot) : player.heldItem();
        }
        double reach = Math.max(1.0, interactions.digReach(item).orElse(interactions.useReach(item).orElse(DEFAULT_REACH))
                - REACH_MARGIN);
        Stand s = stand(rules.access(), target, new HashSet<>(), reach);
        return s != null && s.here();
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
    public CompletableFuture<Outcome> place(BlockPos cell, String itemId, int rotation) {
        if (cell == null || itemId == null || itemId.isEmpty()) {
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        return held(id -> placeNow(id, cell, itemId, rotation, false), Outcome.NOT_AVAILABLE);
    }

    @Override
    public CompletableFuture<Outcome> pour(BlockPos cell, String itemId) {
        if (cell == null || itemId == null || itemId.isEmpty()) {
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        return held(id -> placeNow(id, cell, itemId, -1, true), Outcome.NOT_AVAILABLE);
    }

    @Override
    public CompletableFuture<Outcome> scoop(BlockPos cell, String itemId) {
        if (cell == null || itemId == null || itemId.isEmpty()) {
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        return held(id -> scoopNow(id, cell, itemId), Outcome.NOT_AVAILABLE);
    }

    @Override
    public CompletableFuture<Outcome> pillarDown(int blocks) {
        if (blocks < 1) {
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        return held(id -> pillarDownNow(id, blocks), Outcome.NOT_AVAILABLE);
    }

    @Override
    public CompletableFuture<Outcome> pillarUp(String itemId, int blocks) {
        if (itemId == null || itemId.isEmpty() || blocks < 1) {
            return CompletableFuture.completedFuture(Outcome.NOTHING);
        }
        return held(id -> pillarNow(id, itemId, blocks), Outcome.NOT_AVAILABLE);
    }

    @Override
    public CompletableFuture<Map<String, Integer>> take(BlockPos chest, Map<String, Integer> items) {
        if (chest == null || items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return held(id -> takeNow(id, chest, items), Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Integer>> deposit(BlockPos chest, Map<String, Integer> items) {
        return depositOpened(chest, items).thenApply(o -> o.orElse(Map.of()));
    }

    @Override
    public CompletableFuture<java.util.Optional<Map<String, Integer>>> depositOpened(BlockPos chest,
                                                                                    Map<String, Integer> items) {
        return held(id -> depositNow(id, chest, items), java.util.Optional.empty());
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
        // The worker's work is what an urgent hold interrupts: a job begun while one is held - the
        // next block of a job of many - waits for it as well.
        long born = urgency.active() ? urgency.lastBegan() - 1 : urgency.stamp();
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
            BORN.set(born);
            try {
                done.complete(job.run(id));
            } catch (Throwable t) {
                log.warn("meridian-core: work failed", t);
                done.completeExceptionally(t);
            } finally {
                BORN.remove();
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
        int again = 0;
        for (int attempt = 0; attempt < STAND_TRIES && live(id); attempt++) {
            pause();
            long mark = urgency.lastBegan();
            Stand s = stand(access, target, tried, reach);
            if (s == null) {
                break;
            }
            placed = true;
            tried.add(s.cell());
            log.info("meridian-core: {} to be broken from {}{} (cost {})", text(target), text(s.cell()),
                    s.here() ? ", where the body is" : "", String.format(Locale.ROOT, "%.1f", s.cost()));
            if (!s.here()) {
                // Walked - or, with no walk there at all (into a pit with sheer walls, back from a chest
                // above it), a way dug there: a staircase down, as a player cuts one. A long way round is
                // walked: what is dug here is the job's, nothing else.
                NavResult r = travel(id, Goal.at(s.cell()), a -> s.cell().equals(a), null, false, rules, broken);
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
            if (urgency.lastBegan() != mark && again++ < URGENT_RETRIES) {
                // An urgent hold came in the middle of it: the body is elsewhere now - again, from there.
                tried.clear();
                attempt--;
            }
        }
        // No place on open ground to break it from: a tunnel to it, the body's height, through the rock.
        if (!got && live(id) && access.within() == null && access.wayIn()) {
            Outcome t = tunnel(id, target, rules, broken);
            if (t != null) {
                return t;
            }
        }
        return !live(id) ? Outcome.INTERRUPTED : got ? Outcome.WOULD_NOT_BREAK
                : placed ? Outcome.UNREACHABLE : Outcome.NO_PLACE;
    }

    // ------------------------------------------------------------------
    // A tunnel to a block deep in the rock
    // ------------------------------------------------------------------

    /** The longest tunnel dug to one block, in moves. */
    static final int TUNNEL_MOST = 48;
    /** The most places a tunnel is looked for through. */
    static final int TUNNEL_NODES = 30_000;
    /** What a block to break costs a tunnel, against 1 for a move. */
    static final double TUNNEL_PER_BLOCK = 1.5;
    /** ... and sand or gravel over what is broken, which would come down into it. */
    static final double TUNNEL_FALLING = 6.0;

    /** One move of a tunnel: the blocks to break, then the place to go to. */
    private record TunnelStep(BlockPos to, List<BlockPos> breaks) {}

    private record TunnelNode(BlockPos at, double g, double f, int depth, TunnelNode parent, List<BlockPos> breaks) {}

    /**
     * Digs a tunnel to a block no place on open ground reaches, then breaks it: the blocks of each
     * move broken, then the move made. {@code null} when there is no tunnel to it.
     */
    private Outcome tunnel(int id, BlockPos target, Rules rules, List<BlockPos> broken) throws Exception {
        List<TunnelStep> plan = tunnelPlan(target);
        if (plan == null) {
            return null;
        }
        log.info("meridian-core: a tunnel of {} move(s), {} block(s) to break, to {}", plan.size(),
                plan.stream().mapToInt(st -> st.breaks().size()).sum(), text(target));
        for (TunnelStep st : plan) {
            for (BlockPos c : st.breaks()) {
                if (!live(id)) {
                    return Outcome.INTERRUPTED;
                }
                if (world.blockAt(c.x(), c.y(), c.z()).isAir()) {
                    continue;
                }
                if (nearLava(c)) {
                    return Outcome.LAVA;
                }
                if (!breakInReach(c, rules, broken)) {
                    return Outcome.WOULD_NOT_BREAK;
                }
            }
            NavResult r = go(Goal.at(st.to())).get(1, TimeUnit.MINUTES);
            if (!live(id)) {
                return Outcome.INTERRUPTED;
            }
            if (r.status() != NavResult.Status.ARRIVED) {
                log.info("meridian-core: the tunnel to {} did not go on at {} ({})", text(target), text(st.to()),
                        r.status());
                return Outcome.UNREACHABLE;
            }
        }
        return breakFromHere(id, target, rules, List.of(), broken) ? Outcome.BROKEN : Outcome.WOULD_NOT_BREAK;
    }

    /**
     * A walk to {@code goal} is this many times longer than the straight line at most before the way is
     * dug instead - out of a pit, up a cliff: a staircase, as a player cuts one.
     */
    static final double DETOUR = 2.0;
    /** ... and a little more than that, for a short trip where any way round is long by the ratio. */
    static final double DETOUR_SLACK = 4.0;

    /**
     * Goes to {@code goal}: walks there - unless the walk goes a long way round ({@link #DETOUR}) and
     * a way dug there is shorter; then that is dug, a move at a time (its blocks broken, then the move
     * made, as a tunnel to a block is), and the rest walked.
     */
    private NavResult travel(int id, Goal goal, Rules rules, List<BlockPos> broken) throws Exception {
        return travel(id, goal, a -> goal.reachedBy(a.x(), a.y(), a.z()), null, rules, broken);
    }

    private NavResult travel(int id, Goal goal, java.util.function.Predicate<BlockPos> arrived, BlockPos keep,
                             Rules rules, List<BlockPos> broken) throws Exception {
        return travel(id, goal, arrived, keep, true, rules, broken);
    }

    /**
     * {@link #travel(int, Goal, Rules, List)} where a way dug ends at a place {@code arrived} takes -
     * beside a chest, not merely near it through the rock - {@code keep} never broken; with no
     * {@code shortcut}, a way is dug only when there is no walk there at all, never for a long way round.
     */
    private NavResult travel(int id, Goal goal, java.util.function.Predicate<BlockPos> arrived, BlockPos keep,
                             boolean shortcut, Rules rules, List<BlockPos> broken) throws Exception {
        Vec3 me = movement.position();
        if (me == null) {
            return go(goal).get(3, TimeUnit.MINUTES);
        }
        double straight = Math.sqrt(distSq(me, new Vec3(goal.x() + 0.5, goal.y(), goal.z() + 0.5)));
        List<Navigator.Step> walk = navigator.plan(goal);
        double walked = 0;
        Vec3 at = me;
        for (Navigator.Step st : walk) {
            Vec3 to = new Vec3(st.x() + 0.5, st.feet(), st.z() + 0.5);
            walked += Math.sqrt(distSq(at, to));
            at = to;
        }
        Navigator.Step last = walk.isEmpty() ? null : walk.get(walk.size() - 1);
        boolean walks = last != null && goal.reachedBy(last.x(), last.y(), last.z());
        if (walks && (!shortcut || walked <= DETOUR * straight + DETOUR_SLACK)) {
            return go(goal).get(3, TimeUnit.MINUTES);
        }
        BlockPos aim = new BlockPos(goal.x(), goal.y(), goal.z());
        List<TunnelStep> dug = tunnelPlan(aim, arrived, keep);
        double cost = 0;
        if (dug != null) {
            for (TunnelStep st : dug) {
                cost += 1 + TUNNEL_PER_BLOCK * st.breaks().size();
            }
        }
        if (dug == null || (walks && cost >= walked)) {
            return go(goal).get(3, TimeUnit.MINUTES);
        }
        log.info("meridian-core: {} - a way dug instead ({} move(s), {} block(s); the walk {} long, straight {})",
                text(aim), dug.size(), dug.stream().mapToInt(st -> st.breaks().size()).sum(),
                walks ? String.format(Locale.ROOT, "%.0f", walked) : "none",
                String.format(Locale.ROOT, "%.0f", straight));
        for (TunnelStep st : dug) {
            for (BlockPos c : st.breaks()) {
                if (!live(id)) {
                    return new NavResult(NavResult.Status.INTERRUPTED, movement.position(), 0, 0);
                }
                if (world.blockAt(c.x(), c.y(), c.z()).isAir()) {
                    continue;
                }
                if (nearLava(c) || !breakInReach(c, rules, broken)) {
                    return go(goal).get(3, TimeUnit.MINUTES);     // could not dig on: walk the rest
                }
            }
            NavResult r = go(Goal.at(st.to())).get(1, TimeUnit.MINUTES);
            if (!live(id)) {
                return r;
            }
            if (r.status() != NavResult.Status.ARRIVED) {
                return go(goal).get(3, TimeUnit.MINUTES);
            }
        }
        // At the way's end: there, where it was dug to.
        return new NavResult(NavResult.Status.ARRIVED, movement.position(), dug.size(), 0);
    }

    /**
     * A tunnel to the block: from where the body stands, moves along the axes - level, a step up, a
     * step down - each through a way the body's height, breaking what is there, to a place with the
     * block beside it at the feet or the head, or over the head. A move costs 1 and each block to break
     * {@link #TUNNEL_PER_BLOCK} more; sand or gravel over what is broken costs {@link #TUNNEL_FALLING}.
     * Not dug: a cell with a fluid, one next to lava, one nothing in the hotbar breaks, one core does
     * not know, the block itself; nothing is dug under the feet. {@code null} when there is no tunnel
     * within {@link #TUNNEL_MOST} moves.
     */
    private List<TunnelStep> tunnelPlan(BlockPos target) {
        return tunnelPlan(target, beside(target), target);
    }

    /** A place with the block beside it at the feet or the head, or over the head: where it is got at. */
    private static java.util.function.Predicate<BlockPos> beside(BlockPos target) {
        return a -> {
            int hx = Math.abs(a.x() - target.x()) + Math.abs(a.z() - target.z());
            return (hx == 1 && (target.y() == a.y() || target.y() == a.y() + 1))
                    || (hx == 0 && target.y() == a.y() + 2);
        };
    }

    /**
     * A way dug from where the body stands to a place {@code arrived} takes - the same moves as a tunnel
     * to a block ({@link #tunnelPlan(BlockPos)}), led toward {@code aim}, {@code keep} never broken
     * ({@code null}: nothing kept).
     */
    private List<TunnelStep> tunnelPlan(BlockPos aim, java.util.function.Predicate<BlockPos> arrived, BlockPos keep) {
        BlockPos target = aim;
        Vec3 me = movement.position();
        Player player = world.player().orElse(null);
        if (me == null || player == null || !movement.onGround()) {
            return null;
        }
        int[] c = Walker.cellOf(me);
        BlockPos start = new BlockPos(c[0], c[1], c[2]);
        java.util.PriorityQueue<TunnelNode> open = new java.util.PriorityQueue<>(
                (a, b) -> Double.compare(a.f(), b.f()));
        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, Boolean> clear = new HashMap<>();
        open.add(new TunnelNode(start, 0, tunnelGuess(start, target), 0, null, List.of()));
        best.put(start, 0.0);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int seen = 0;
        while (!open.isEmpty() && seen++ < TUNNEL_NODES) {
            TunnelNode n = open.poll();
            if (n.g() > best.getOrDefault(n.at(), Double.MAX_VALUE)) {
                continue;
            }
            BlockPos a = n.at();
            if (arrived.test(a)) {
                List<TunnelStep> out = new ArrayList<>();
                for (TunnelNode k = n; k.parent() != null; k = k.parent()) {
                    out.add(0, new TunnelStep(k.at(), k.breaks()));
                }
                return out;
            }
            if (n.depth() >= TUNNEL_MOST) {
                continue;
            }
            for (int[] d : dirs) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = a.x() + d[0];
                    int z = a.z() + d[1];
                    int y = a.y() + dy;
                    List<BlockPos> cells = new ArrayList<>();
                    if (dy > 0) {
                        cells.add(new BlockPos(a.x(), a.y() + 2, a.z()));     // room to jump
                    } else if (dy < 0) {
                        cells.add(new BlockPos(x, a.y() + 1, z));              // the head, stepping down
                    }
                    cells.add(new BlockPos(x, y + 1, z));
                    cells.add(new BlockPos(x, y, z));
                    BlockPos floor = new BlockPos(x, y - 1, z);
                    if (!world.known(floor.x(), floor.y(), floor.z()) || !movement.cell(floor).solid()
                            || floor.equals(keep)) {
                        continue;
                    }
                    List<BlockPos> breaks = new ArrayList<>();
                    double extra = 0;
                    boolean ok = true;
                    for (BlockPos cell : cells) {
                        Boolean pass = clear.computeIfAbsent(cell, k -> tunnelCell(k, keep, player));
                        if (pass == null || !pass) {
                            ok = false;
                            break;
                        }
                        if (!world.blockAt(cell.x(), cell.y(), cell.z()).isAir()) {
                            breaks.add(cell);
                        }
                    }
                    if (!ok) {
                        continue;
                    }
                    if (!breaks.isEmpty()) {
                        BlockPos top = cells.get(0).y() >= cells.get(1).y() ? cells.get(0) : cells.get(1);
                        Block over = world.blockAt(top.x(), top.y() + 1, top.z());
                        String name = over.isAir() || over.type() == null ? null : over.type().name();
                        if (name != null && (name.contains("Sand") || name.contains("Gravel"))) {
                            extra += TUNNEL_FALLING;
                        }
                    }
                    BlockPos to = new BlockPos(x, y, z);
                    double g = n.g() + 1 + TUNNEL_PER_BLOCK * breaks.size() + extra;
                    if (g < best.getOrDefault(to, Double.MAX_VALUE)) {
                        best.put(to, g);
                        open.add(new TunnelNode(to, g, g + tunnelGuess(to, target), n.depth() + 1, n, breaks));
                    }
                }
            }
        }
        return null;
    }

    /**
     * Whether a tunnel may go through the cell: air with no fluid, or a block the hotbar breaks that is
     * not the target, with no fluid and no lava beside it.
     */
    private Boolean tunnelCell(BlockPos cell, BlockPos keep, Player player) {
        if (cell.equals(keep) || !world.known(cell.x(), cell.y(), cell.z())) {
            return false;
        }
        Movement.Cell m = movement.cell(cell);
        if (m.fluid() != null || m.hurts()) {
            return false;
        }
        Block b = world.blockAt(cell.x(), cell.y(), cell.z());
        if (b.isAir()) {
            return true;
        }
        return player.bestToolSlot(b.type()) >= 0 && !nearLava(cell);
    }

    private static double tunnelGuess(BlockPos at, BlockPos target) {
        return Math.max(0, Math.abs(at.x() - target.x()) + Math.abs(at.z() - target.z()) - 1)
                + Math.max(0, Math.abs(at.y() - target.y()) - 1);
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
        // A tool with a break shape (a drill) breaks the cells round the one aimed at too: what of
        // them is there now is reported broken with it once gone - the drops lie there as well.
        Player player = world.player().orElse(null);
        List<BlockPos> shape = player == null ? null : shapeCells(player.heldItem(), pos, eye);
        List<BlockPos> there = new ArrayList<>();
        if (shape != null) {
            for (BlockPos c : shape) {
                if (!c.equals(pos) && world.known(c.x(), c.y(), c.z()) && !world.blockAt(c.x(), c.y(), c.z()).isAir()) {
                    there.add(c);
                }
            }
        }
        if (!breakOne(pos)) {
            return false;
        }
        broken.add(pos);
        if (!there.isEmpty()) {
            long until = System.currentTimeMillis() + SHAPE_SETTLE_MS;
            List<BlockPos> waiting = new ArrayList<>(there);
            while (true) {
                waiting.removeIf(c -> {
                    if (world.blockAt(c.x(), c.y(), c.z()).isAir()) {
                        broken.add(c);
                        return true;
                    }
                    return false;
                });
                if (waiting.isEmpty() || System.currentTimeMillis() > until) {
                    break;
                }
                Thread.sleep(25);
            }
        }
        return true;
    }

    /** How long the cells of a swing's break shape are waited for, after the one aimed at went. */
    static final long SHAPE_SETTLE_MS = 300;

    /**
     * The cells a swing with {@code itemId} at {@code target} breaks, looked at from {@code eye}: the
     * item's box break shape laid out as the server lays it ({@code BreakShape.forEachBlock}, the
     * View orientation - the look's main axis forward, up the screen's up, right across, the shape
     * shifted off the block aimed at by its offset). {@code null} for a tool without one (it breaks
     * the one block) or a shape core does not lay out (a cylinder, the Surface orientation).
     */
    List<BlockPos> shapeCells(String itemId, BlockPos target, Vec3 eye) {
        var lookup = items;
        if (itemId == null || lookup == null || eye == null) {
            return null;
        }
        meridian.protocol.ItemBase item = lookup.apply(itemId).orElse(null);
        if (item == null || item.tool == null
                || !(item.tool.breakShape instanceof meridian.protocol.BoxBreakShape box)
                || box.orientation != meridian.protocol.BreakShapeOrientation.View) {
            return null;
        }
        double dx = target.x() + 0.5 - eye.x();
        double dy = target.y() + 0.5 - eye.y();
        double dz = target.z() + 0.5 - eye.z();
        int[] f = new int[3];
        int[] u = new int[3];
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            f[1] = dy >= 0 ? 1 : -1;
            int sign = f[1] > 0 ? -1 : 1;           // verticalUp
            if (ax >= az) {
                u[0] = dx >= 0 ? sign : -sign;
            } else {
                u[2] = dz >= 0 ? sign : -sign;
            }
        } else if (ax >= az) {
            f[0] = dx >= 0 ? 1 : -1;
            u[1] = 1;
        } else {
            f[2] = dz >= 0 ? 1 : -1;
            u[1] = 1;
        }
        int[] r = {u[1] * f[2] - u[2] * f[1], u[2] * f[0] - u[0] * f[2], u[0] * f[1] - u[1] * f[0]};
        int ox = box.offset == null ? 0 : Math.round(box.offset.x());
        int oy = box.offset == null ? 0 : Math.round(box.offset.y());
        int oz = box.offset == null ? 0 : Math.round(box.offset.z());
        int bx = target.x() + r[0] * ox + u[0] * oy + f[0] * oz;
        int by = target.y() + r[1] * ox + u[1] * oy + f[1] * oz;
        int bz = target.z() + r[2] * ox + u[2] * oy + f[2] * oz;
        int uLow = box.centered ? -((box.width - 1) / 2) : 0;
        int vLow = box.centered ? -((box.height - 1) / 2) : 0;
        List<BlockPos> out = new ArrayList<>();
        for (int w = 0; w < box.depth; w++) {
            for (int a = uLow; a < uLow + box.width; a++) {
                for (int v = vLow; v < vLow + box.height; v++) {
                    out.add(new BlockPos(bx + r[0] * a + u[0] * v + f[0] * w, by + r[1] * a + u[1] * v + f[1] * w,
                            bz + r[2] * a + u[2] * v + f[2] * w));
                }
            }
        }
        return out;
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
        java.util.function.Predicate<String> safe = id -> within == null
                || ((!interactions.digSweeps(id) || !spills(player, id, pos, within)) && !shapeSpills(id, pos, within));
        String part = type == null || type.gatherType() == null ? null
                : rules.tools().prefer().getOrDefault(type.gatherType(), rules.tools().prefer().get("*"));
        if (part != null) {
            int slot = player.bestToolSlot(type, id -> id.contains(part) && safe.test(id));
            // A tool with a break shape is worth it while what it takes a swing - its power times the
            // cells of the shape it breaks - is at least the best single tool's: a drill on rock,
            // not on the hardest ores, where a pickaxe goes faster.
            int best = player.bestToolSlot(type, safe);
            if (slot >= 0 && best >= 0 && best != slot) {
                List<BlockPos> shape = shapeCells(player.hotbarItem(slot), pos, eye());
                int cells = shape == null ? 1 : (int) shape.stream().filter(c -> c.equals(pos)
                        || (world.known(c.x(), c.y(), c.z()) && !world.blockAt(c.x(), c.y(), c.z()).isAir())).count();
                if (shape != null && player.toolPower(slot, type) * Math.max(1, cells) < player.toolPower(best, type)) {
                    slot = -1;
                }
            }
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
     * Whether a swing with a tool with a break shape at {@code pos} breaks a block outside the box -
     * one there, that the tool breaks in earnest.
     */
    private boolean shapeSpills(String itemId, BlockPos pos, BlockBox within) {
        Player player = world.player().orElse(null);
        List<BlockPos> shape = player == null ? null : shapeCells(itemId, pos, eye());
        if (shape == null) {
            return false;
        }
        int slot = player.hotbarSlotOf(itemId);
        for (BlockPos c : shape) {
            if (within.contains(c.x(), c.y(), c.z()) || !world.known(c.x(), c.y(), c.z())) {
                continue;
            }
            Block n = world.blockAt(c.x(), c.y(), c.z());
            if (!n.isAir() && (slot < 0 || player.toolPower(slot, n.type()) >= SWEEP_BREAKS)) {
                return true;
            }
        }
        return false;
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
        if (pause()) {
            return world.blockAt(pos.x(), pos.y(), pos.z()).isAir();   // the body is elsewhere now
        }
        Vec3 eye = eye();
        Vec3 aim = eye == null ? centre(pos) : sight(eye, pos).aim();
        double d = eye == null ? 1 : Math.sqrt(distSq(eye, aim));
        faceNow(aim, Math.max(Movement.FACE_WITHIN, Math.atan(0.25 / Math.max(d, 0.5)))).get(2, TimeUnit.SECONDS);
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
            // Walked in after the drop: the way needs a floor all along.
            if (!world.known(x, y - 1, z) || !movement.cell(new BlockPos(x, y - 1, z)).solid()) {
                return null;
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
    Goal pickupFrom(Vec3 drop, Vec3 me) {
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
                    if (d < bestD && pickupReachable(Goal.at(cell), me)) {
                        bestD = d;
                        best = cell;
                    }
                }
            }
        }
        return best == null ? null : Goal.at(best);
    }

    /** A nearby standing cell can be on the other side of the wall containing the drop. */
    private boolean pickupReachable(Goal goal, Vec3 me) {
        int[] here = Walker.cellOf(me);
        if (goal.reachedBy(here[0], here[1], here[2])) return true;
        List<Navigator.Step> path = navigator.plan(goal);
        if (path.isEmpty()) return false;
        Navigator.Step last = path.getLast();
        return goal.reachedBy(last.x(), last.y(), last.z());
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
            pause();
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
            NavResult r = go(goal).get(3, TimeUnit.MINUTES);
            if (live(id) && r.status() != NavResult.Status.ARRIVED) {
                Vec3 now = movement.position();
                Goal alternative = now == null ? null : pickupFrom(bestAt, now);
                if (alternative != null && !alternative.equals(goal)) {
                    r = go(alternative).get(3, TimeUnit.MINUTES);
                }
            }
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
    private java.util.Optional<Map<String, Integer>> depositNow(int id, BlockPos chest, Map<String, Integer> want)
            throws Exception {
        ProxySession s = session.get();
        if (containers == null || inventory == null || s == null
                || !world.known(chest.x(), chest.y(), chest.z()) || world.blockAt(chest.x(), chest.y(), chest.z()).isAir()) {
            return java.util.Optional.empty();
        }
        if (want == null || want.isEmpty()) {
            return java.util.Optional.of(Map.of());
        }
        int window = openContainer(id, chest);
        if (window < 0) {
            return java.util.Optional.empty();
        }
        // What is held once there: the way there may have been dug, its drops picked up on it.
        Map<String, Integer> before = held();
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
            return java.util.Optional.of(moved);
        } finally {
            close(s, window);
        }
    }

    // ------------------------------------------------------------------
    // Placing a block
    // ------------------------------------------------------------------

    /** How long a placed block is waited for to show. */
    static final long PLACE_WAIT_MS = 1500;

    /** A place to put a block from: where to stand, which block to place it against and on which face. */
    private record PlaceStand(BlockPos cell, boolean here, BlockPos against, Face face, Vec3 aim, double cost) {}

    private Outcome placeNow(int id, BlockPos cell, String itemId, int rotation, boolean fluid) throws Exception {
        if (!world.known(cell.x(), cell.y(), cell.z())) {
            return Outcome.NOTHING;
        }
        if (!world.blockAt(cell.x(), cell.y(), cell.z()).isAir()) {
            return Outcome.OCCUPIED;
        }
        Player player = world.player().orElse(null);
        if (player == null) {
            return Outcome.NOT_AVAILABLE;
        }
        int slot = hotbarSlotWith(player, itemId);
        if (slot < 0) {
            slot = intoHotbar(player, itemId);
            if (slot < 0) {
                return Outcome.NO_ITEM;
            }
        }
        List<Face> sides = new ArrayList<>();
        for (Face f : PLACE_AGAINST) {
            BlockPos b = f.from(cell);
            if (world.known(b.x(), b.y(), b.z()) && !world.blockAt(b.x(), b.y(), b.z()).isAir()
                    && movement.cell(b).solid()) {
                sides.add(f);
            }
        }
        if (sides.isEmpty()) {
            return Outcome.NO_SUPPORT;
        }
        double reach = Math.max(1.0, interactions.useReach(itemId).orElse(DEFAULT_REACH) - REACH_MARGIN);
        Set<BlockPos> tried = new HashSet<>();
        boolean got = false;
        for (int attempt = 0; attempt < STAND_TRIES && live(id); attempt++) {
            PlaceStand st = placeStand(cell, sides, reach, tried);
            if (st == null) {
                break;
            }
            tried.add(st.cell());
            if (!st.here()) {
                NavResult r = go(Goal.at(st.cell())).get(3, TimeUnit.MINUTES);
                if (!live(id)) {
                    return Outcome.INTERRUPTED;
                }
                if (r.status() != NavResult.Status.ARRIVED) {
                    continue;
                }
            }
            got = true;
            if (player.activeHotbarSlot() != slot) {
                interactions.switchHotbarSlot(slot).get(5, TimeUnit.SECONDS);
            }
            Vec3 eye = eye();
            double d = eye == null ? 1 : Math.sqrt(distSq(eye, st.aim()));
            faceNow(st.aim(), Math.max(Movement.FACE_WITHIN, Math.atan(0.2 / Math.max(d, 0.5))))
                    .get(2, TimeUnit.SECONDS);
            interactions.placeOnBlock(st.against(), st.face(), rotation).get(10, TimeUnit.SECONDS);
            for (long waited = 0; waited < PLACE_WAIT_MS; waited += 50) {
                if (fluid ? source(world.fluidCellAt(cell.x(), cell.y(), cell.z()))
                        : !world.blockAt(cell.x(), cell.y(), cell.z()).isAir()) {
                    return Outcome.PLACED;
                }
                Thread.sleep(50);
            }
            log.info("meridian-core: {} placed from {} against {} did not show", text(cell), text(st.cell()),
                    text(st.against()));
            return Outcome.REFUSED;
        }
        return !live(id) ? Outcome.INTERRUPTED : got ? Outcome.REFUSED
                : tried.isEmpty() ? Outcome.NO_PLACE : Outcome.UNREACHABLE;
    }

    /** How long a jump of a pillar is waited for to clear the block it puts under the feet. */
    static final long PILLAR_JUMP_MS = 1200;

    /**
     * A pillar under the body: each time, looking down, the jump held until the feet are over the cell
     * they stood in, then a block put on the one under it - it lands under the feet - and the jump
     * let go, the body coming down on it.
     */
    private Outcome pillarNow(int id, String itemId, int blocks) throws Exception {
        Player player = world.player().orElse(null);
        if (player == null) {
            return Outcome.NOT_AVAILABLE;
        }
        int slot = hotbarSlotWith(player, itemId);
        if (slot < 0) {
            slot = intoHotbar(player, itemId);
            if (slot < 0) {
                return Outcome.NO_ITEM;
            }
        }
        if (player.activeHotbarSlot() != slot) {
            interactions.switchHotbarSlot(slot).get(5, TimeUnit.SECONDS);
        }
        for (int i = 0; i < blocks; i++) {
            if (!live(id)) {
                return Outcome.INTERRUPTED;
            }
            Vec3 at = movement.position();
            if (at == null || !movement.onGround()) {
                return Outcome.NO_PLACE;
            }
            int[] c = Walker.cellOf(at);
            BlockPos feet = new BlockPos(c[0], c[1], c[2]);
            BlockPos under = new BlockPos(c[0], c[1] - 1, c[2]);
            BlockPos over = new BlockPos(c[0], c[1] + 2, c[2]);
            if (!world.blockAt(over.x(), over.y(), over.z()).isAir() || world.blockAt(under.x(), under.y(), under.z()).isAir()) {
                return Outcome.NO_PLACE;
            }
            if (player.hotbarItem(slot) == null || !itemId.equals(player.hotbarItem(slot))) {
                return Outcome.NO_ITEM;
            }
            // Look down at the top of the block under the feet, then jump and hold it.
            faceNow(new Vec3(under.x() + 0.5, under.y() + 1.0, under.z() + 0.5), Math.toRadians(8))
                    .get(2, TimeUnit.SECONDS);
            Movement.State st = movement.state();
            movement.hold(new Movement.Controls(0, 0, st.yaw(), st.pitch(), true, false, false, false, false));
            boolean over1 = false;
            try {
                for (long waited = 0; waited < PILLAR_JUMP_MS; waited += 10) {
                    Vec3 p = movement.position();
                    if (p != null && p.y() >= feet.y() + 1.02) {
                        over1 = true;
                        break;
                    }
                    Thread.sleep(10);
                }
                if (!over1) {
                    return Outcome.NO_PLACE;
                }
                interactions.placeOnBlock(under, Face.UP, -1).get(10, TimeUnit.SECONDS);
            } finally {
                movement.hold(null);
            }
            boolean down = false;
            for (long waited = 0; waited < PILLAR_JUMP_MS; waited += 20) {
                Vec3 p = movement.position();
                if (!world.blockAt(feet.x(), feet.y(), feet.z()).isAir() && movement.onGround() && p != null
                        && p.y() >= feet.y() + 0.99) {
                    down = true;
                    break;
                }
                Thread.sleep(20);
            }
            if (!down) {
                log.info("meridian-core: the pillar's block at {} did not land under the feet", text(feet));
                return Outcome.REFUSED;
            }
        }
        return Outcome.PLACED;
    }

    /**
     * A container filled from the fluid in the cell: walked within its use reach with a clear line, the
     * head turned onto the cell, the item used; filled once the fluid is gone from the cell.
     */
    private Outcome scoopNow(int id, BlockPos cell, String itemId) throws Exception {
        if (!source(world.fluidCellAt(cell.x(), cell.y(), cell.z()))) {
            return Outcome.NOTHING;
        }
        Player player = world.player().orElse(null);
        if (player == null) {
            return Outcome.NOT_AVAILABLE;
        }
        int slot = hotbarSlotWith(player, itemId);
        if (slot < 0) {
            slot = intoHotbar(player, itemId);
            if (slot < 0) {
                return Outcome.NO_ITEM;
            }
        }
        double reach = Math.max(1.0, interactions.useReach(itemId).orElse(DEFAULT_REACH) - REACH_MARGIN);
        Vec3 aim = centre(cell);
        Vec3 me = movement.position();
        if (me == null) {
            return Outcome.NOT_AVAILABLE;
        }
        int[] here = Walker.cellOf(me);
        double eyeH = movement.capabilities().eyeHeight();
        BlockPos best = null;
        boolean bestHere = false;
        double bestCost = Double.MAX_VALUE;
        int r = (int) Math.ceil(reach);
        for (int dy = -r - 1; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos at = new BlockPos(cell.x() + dx, cell.y() + dy, cell.z() + dz);
                    OptionalDouble feet = movement.standAt(at);
                    if (feet.isEmpty() || at.equals(cell)) {
                        continue;
                    }
                    boolean isHere = at.x() == here[0] && at.y() == here[1] && at.z() == here[2];
                    Vec3 eye = isHere ? new Vec3(me.x(), me.y() + eyeH, me.z())
                            : new Vec3(at.x() + 0.5, feet.getAsDouble() + eyeH, at.z() + 0.5);
                    if (distSq(eye, aim) > reach * reach) {
                        continue;
                    }
                    List<BlockPos> in = obstaclesTo(eye, cell, aim);
                    if (in == null || !in.isEmpty()) {
                        continue;
                    }
                    double cost = Math.sqrt(distSq(me, eye)) - (isHere ? 2 : 0);
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = at;
                        bestHere = isHere;
                    }
                }
            }
        }
        if (best == null) {
            return Outcome.NO_PLACE;
        }
        if (!bestHere) {
            NavResult nav = go(Goal.at(best)).get(3, TimeUnit.MINUTES);
            if (!live(id)) {
                return Outcome.INTERRUPTED;
            }
            if (nav.status() != NavResult.Status.ARRIVED) {
                return Outcome.UNREACHABLE;
            }
        }
        if (player.activeHotbarSlot() != slot) {
            interactions.switchHotbarSlot(slot).get(5, TimeUnit.SECONDS);
        }
        Vec3 eye = eye();
        double d = eye == null ? 1 : Math.sqrt(distSq(eye, aim));
        // The server takes the fluid its own ray from the eyes along the look meets: onto the middle.
        faceNow(aim, Math.max(Movement.FACE_WITHIN, Math.atan(0.15 / Math.max(d, 0.5)))).get(2, TimeUnit.SECONDS);
        Thread.sleep(100);
        // The right button in the open air: the server's own ray along the look finds the fluid.
        interactions.useItem().get(10, TimeUnit.SECONDS);
        // Filled once the container in the hand is another item (its filled state) - the server takes
        // the first source its ray meets, which need not be this very cell.
        for (long waited = 0; waited < PLACE_WAIT_MS; waited += 50) {
            String now = player.hotbarItem(slot);
            if (now != null && !now.equals(itemId)) {
                return Outcome.FILLED;
            }
            Thread.sleep(50);
        }
        return Outcome.REFUSED;
    }

    /** A fluid's source - what a bucket takes and leaves - not its flow. */
    private static boolean source(meridian.core.api.FluidCell f) {
        return f != null && f.name() != null && f.name().endsWith("_Source");
    }

    /**
     * Down a pillar: looking at the block under the feet, it is broken, the body drops onto the next;
     * {@code blocks} times, or until the block under the feet is not one to break.
     */
    private Outcome pillarDownNow(int id, int blocks) throws Exception {
        for (int i = 0; i < blocks; i++) {
            if (!live(id)) {
                return Outcome.INTERRUPTED;
            }
            Vec3 at = movement.position();
            if (at == null || !movement.onGround()) {
                return Outcome.NO_PLACE;
            }
            int[] c = Walker.cellOf(at);
            BlockPos under = new BlockPos(c[0], c[1] - 1, c[2]);
            Block b = world.blockAt(under.x(), under.y(), under.z());
            if (b.isAir() || nearLava(under) || world.blockAt(under.x(), under.y() - 1, under.z()).isAir()) {
                // Nothing to break, lava beside, or nothing under it to come down on: done here.
                return i == 0 ? Outcome.NOTHING : Outcome.BROKEN;
            }
            equip(under, new Rules(Access.ANY, Tools.BEST));
            faceNow(new Vec3(under.x() + 0.5, under.y() + 1.0, under.z() + 0.5), Math.toRadians(8))
                    .get(2, TimeUnit.SECONDS);
            if (!breakOne(under)) {
                return Outcome.WOULD_NOT_BREAK;
            }
            for (long waited = 0; waited < PILLAR_JUMP_MS; waited += 20) {
                Vec3 p = movement.position();
                if (p != null && movement.onGround() && p.y() <= under.y() + 0.01) {
                    break;
                }
                Thread.sleep(20);
            }
        }
        return Outcome.BROKEN;
    }

    /** How far behind a side face the eyes may be, over the edge above it, to place a block on it. */
    static final double BRIDGE_LEAN = 0.6;

    /** The faces of a cell a block placed into it may lean on, in the order a builder tries them. */
    private static final Face[] PLACE_AGAINST = {Face.DOWN, Face.NORTH, Face.SOUTH, Face.EAST, Face.WEST, Face.UP};

    /**
     * Where to place a block into {@code cell} from: a place the body stands on, not in the cell (nor
     * with its head there), the eyes within {@code reach} of the cell and of a face of a block beside
     * it that is turned toward them, with a clear line to that face. Nearer the body first; where it
     * stands is cheapest.
     */
    private PlaceStand placeStand(BlockPos cell, List<Face> sides, double reach, Set<BlockPos> tried) {
        Vec3 me = movement.position();
        if (me == null) {
            return null;
        }
        int[] here = Walker.cellOf(me);
        double eyeH = movement.capabilities().eyeHeight();
        int r = (int) Math.ceil(reach);
        Vec3 middle = centre(cell);
        PlaceStand best = null;
        for (int dy = -r - 1; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos at = new BlockPos(cell.x() + dx, cell.y() + dy, cell.z() + dz);
                    if (tried.contains(at)) {
                        continue;
                    }
                    OptionalDouble feet = movement.standAt(at);
                    if (feet.isEmpty()) {
                        continue;
                    }
                    // The body is not where the block goes: its feet and its head are out of the cell.
                    int foot = (int) Math.floor(feet.getAsDouble() + 1e-3);
                    if (at.x() == cell.x() && at.z() == cell.z() && cell.y() >= foot - 1 && cell.y() <= foot + 1) {
                        continue;
                    }
                    boolean isHere = at.x() == here[0] && at.y() == here[1] && at.z() == here[2];
                    Vec3 eye = isHere ? new Vec3(me.x(), me.y() + eyeH, me.z())
                            : new Vec3(at.x() + 0.5, feet.getAsDouble() + eyeH, at.z() + 0.5);
                    if (distSq(eye, middle) > reach * reach) {
                        continue;
                    }
                    for (Face toCell : sides) {
                        BlockPos against = toCell.from(cell);
                        Face face = opposite(toCell);
                        Vec3 aim = new Vec3(against.x() + 0.5 + face.dx * 0.49, against.y() + 0.5 + face.dy * 0.49,
                                against.z() + 0.5 + face.dz * 0.49);
                        double toEye = face.dx * (eye.x() - aim.x()) + face.dy * (eye.y() - aim.y())
                                + face.dz * (eye.z() - aim.z());
                        // Over an edge, looking down at the side of the block the body stands at the
                        // edge of - a bridge's next block: the face is seen leaning over it.
                        boolean overEdge = face.dy == 0 && toEye > -BRIDGE_LEAN && eye.y() > against.y() + 1.0;
                        if ((toEye <= 0.05 && !overEdge) || distSq(eye, aim) > reach * reach) {
                            continue;                       // the face is turned away, or out of reach
                        }
                        List<BlockPos> in = obstaclesTo(eye, against, aim);
                        if (in == null || !in.isEmpty()) {
                            continue;
                        }
                        double cost = Math.sqrt(distSq(me, eye)) - (isHere ? 2 : 0)
                                + (isHere ? 0 : PER_WALL_BETWEEN * solidBetween(me,
                                        new Vec3(at.x() + 0.5, feet.getAsDouble(), at.z() + 0.5)))
                                + Math.max(0, Math.sqrt(distSq(eye, middle)) - COMFORT) * PER_BLOCK_PAST
                                + (toCell == Face.DOWN ? 0 : 0.3);
                        if (best == null || cost < best.cost()) {
                            best = new PlaceStand(at, isHere, against, face, aim, cost);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Face opposite(Face f) {
        return Face.ofDelta(-f.dx, -f.dy, -f.dz);
    }

    /** The hotbar slot holding exactly this item, or -1. */
    private static int hotbarSlotWith(Player player, String itemId) {
        for (int slot = 0; slot < 9; slot++) {
            if (itemId.equals(player.hotbarItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Moves a stack of the item from the storage or the backpack into the hotbar, as a shift-click
     * does; the hotbar slot it lands in, or -1 when the player carries none, or it did not come.
     */
    private int intoHotbar(Player player, String itemId) throws InterruptedException {
        ProxySession s = session.get();
        if (inventory == null || s == null) {
            return -1;
        }
        for (String name : List.of("Storage", "Backpack")) {
            PlayerState.Section section = inventory.inventories().get(name);
            Integer sectionId = SECTIONS.get(name);
            if (section == null || sectionId == null) {
                continue;
            }
            for (Map.Entry<Integer, meridian.core.api.Containers.Item> e : section.items().entrySet()) {
                meridian.core.api.Containers.Item item = e.getValue();
                if (item == null || !itemId.equals(item.id())) {
                    continue;
                }
                SmartMoveItemStack move = new SmartMoveItemStack();
                move.fromSectionId = sectionId;
                move.fromSlotId = e.getKey();
                move.quantity = item.quantity();
                move.moveType = SmartMoveType.PutInHotbarOrWindow;
                s.sendToServer(move);
                for (long waited = 0; waited < WINDOW_WAIT_MS; waited += 50) {
                    int slot = hotbarSlotWith(player, itemId);
                    if (slot >= 0) {
                        return slot;
                    }
                    Thread.sleep(50);
                }
                return -1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Taking from a container
    // ------------------------------------------------------------------

    private Map<String, Integer> takeNow(int id, BlockPos chest, Map<String, Integer> want) throws Exception {
        ProxySession s = session.get();
        if (containers == null || inventory == null || s == null
                || !world.known(chest.x(), chest.y(), chest.z()) || world.blockAt(chest.x(), chest.y(), chest.z()).isAir()) {
            return Map.of();
        }
        int window = openContainer(id, chest);
        if (window < 0) {
            return Map.of();
        }
        try {
            Map<String, Integer> before = held();
            meridian.core.api.Containers.Contents in = containers.at(chest);
            Map<String, Integer> left = new HashMap<>(want);
            if (in != null) {
                for (Map.Entry<Integer, meridian.core.api.Containers.Item> slot : in.items().entrySet()) {
                    meridian.core.api.Containers.Item item = slot.getValue();
                    int need = item == null ? 0 : left.getOrDefault(item.id(), 0);
                    if (need <= 0) {
                        continue;
                    }
                    int n = Math.min(need, item.quantity());
                    SmartMoveItemStack move = new SmartMoveItemStack();
                    move.fromSectionId = window;
                    move.fromSlotId = slot.getKey();
                    move.quantity = n;
                    move.moveType = SmartMoveType.PutInHotbarOrWindow;
                    s.sendToServer(move);
                    left.put(item.id(), need - n);
                }
            }
            // What came out shows as what the player holds more of.
            Map<String, Integer> got = Map.of();
            for (long waited = 0; waited < WINDOW_WAIT_MS; waited += 100) {
                Thread.sleep(100);
                Map<String, Integer> now = moved(held(), before, want);
                if (now.equals(got) && !now.isEmpty() && waited >= 500) {
                    break;
                }
                got = now;
            }
            return got;
        } finally {
            close(s, window);
        }
    }

    /**
     * Walks to within the held item's use reach of the container and opens it, as a use does; the
     * window's id, or -1 when it did not open (or the work was ended).
     */
    private int openContainer(int id, BlockPos chest) throws Exception {
        Vec3 eye = eye();
        if (eye == null) {
            return -1;
        }
        Player player = world.player().orElse(null);
        double reach = Math.max(1.0, interactions.useReach(player == null ? null : player.heldItem())
                .orElse(DEFAULT_REACH) - REACH_MARGIN);
        if (distSq(eye, centre(chest)) > reach * reach) {
            travel(id, Goal.near(chest, 2), beside(chest), chest, new Rules(Access.ANY, Tools.BEST), new ArrayList<>());
            // Within reach is what counts - a way dug up to it may end short of the goal's ground.
            Vec3 now = eye();
            if (!live(id) || now == null || distSq(now, centre(chest)) > (reach + REACH_SLACK) * (reach + REACH_SLACK)) {
                return -1;
            }
        }
        // The head on the container as on a block to break - on the part of it the eyes see, not the middle
        // behind a chest stacked beside it - and all the way: a small chest is less than a block, and a
        // look stopped short of its middle may pass over it. A server's guard may open it only to a look
        // on it, and put the player back otherwise.
        Vec3 from = eye();
        Vec3 aim = from == null ? centre(chest) : sight(from, chest).aim();
        faceNow(aim, Movement.FACE_WITHIN).get(2, TimeUnit.SECONDS);
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
        }
        return window;
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
