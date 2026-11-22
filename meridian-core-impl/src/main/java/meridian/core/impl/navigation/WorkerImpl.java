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
    /** The server's default pickup radius; a short approach is checked by physics before it is made. */
    static final double PICKUP_RADIUS = 1.75;
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
        this.log = log != null ? log : org.slf4j.helpers.NOPLogger.NOP_LOGGER;     // a rig with nothing to say it to
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
    public CompletableFuture<NavResult> travel(Goal goal) {
        if (goal == null) {
            return CompletableFuture.completedFuture(new NavResult(NavResult.Status.NO_PATH, movement.position(), 0, 0));
        }
        return held(id -> travel(id, goal, new Rules(Access.ANY, Tools.BEST), new ArrayList<>()),
                new NavResult(NavResult.Status.NOT_AVAILABLE, null, 0, 0));
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
                NavResult r = travel(id, Goal.at(s.cell()), a -> s.cell().equals(a), Set.of(), rules, broken);
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
    // A way anywhere: walked where a walk goes, made where none does ({@link Ways})
    // ------------------------------------------------------------------

    /**
     * The most places one search for a way to make looks at. It looks towards the goal, so a way that
     * is there is found within a few thousand; the count is what "no way" costs when there is none -
     * a place in the sky with nothing to build up to it.
     */
    static final int WAY_NODES = 60_000;
    /**
     * What a block outside the job's selection costs a way made, over any block's: three blocks' worth
     * - a step cut outside the selection (three blocks) then costs about what two blocks put down do.
     */
    static final double OUTSIDE = 3.0;

    /**
     * Digs a tunnel to a block no place on open ground reaches, then breaks it: a way made to beside
     * it ({@link #travel}). {@code null} when there is no way there.
     */
    private Outcome tunnel(int id, BlockPos target, Rules rules, List<BlockPos> broken) throws Exception {
        NavResult r = travel(id, Goal.at(target), beside(target), Set.of(target), rules, broken);
        if (!live(id) || r.status() == NavResult.Status.INTERRUPTED) {
            return Outcome.INTERRUPTED;
        }
        if (!r.arrived()) {
            return r.moves() == 0 ? null : Outcome.UNREACHABLE;
        }
        return breakFromHere(id, target, rules, List.of(), broken) ? Outcome.BROKEN : Outcome.WOULD_NOT_BREAK;
    }

    /** Goes to {@code goal} ({@link #travel(int, Goal, java.util.function.Predicate, Set, Rules, List)}). */
    private NavResult travel(int id, Goal goal, Rules rules, List<BlockPos> broken) throws Exception {
        return travel(id, goal, a -> goal.reachedBy(a.x(), a.y(), a.z()), Set.of(), rules, broken);
    }

    /**
     * Goes to {@code goal}, anywhere: walks as far as a walk goes; where none goes on, the way is made
     * - dug, built - from a place the body walks to, to the NEAREST place a walk goes on from to the
     * goal (or to a place {@code arrived} takes), and the walk taken up again from there
     * ({@link Ways#connect}). {@code keep} is never broken; nor is a block that holds something - a
     * chest, a bench - or the block one stands on; under {@link Access#within} nothing outside the box.
     *
     * <p>It ends by what happened, never by a clock: there; interrupted; or no way - nothing to make
     * one through or with, or every way tried would not go (a block that would not break is left out
     * of the next try, so each try differs from the last).
     */
    private NavResult travel(int id, Goal goal, java.util.function.Predicate<BlockPos> arrived, Set<BlockPos> keep,
                             Rules rules, List<BlockPos> broken) throws Exception {
        Walker walker = navigator instanceof Walker w ? w : null;
        Set<BlockPos> refused = new HashSet<>();      // what would not break, be built on or be stepped to
        Set<BlockPos> unreached = new HashSet<>();    // places the search took for walked to, and the walk did not get to
        Set<BlockPos> notWalked = new HashSet<>();    // places a whole walk was found from, and was not walked
        Set<BlockPos> lookedAgain = new HashSet<>();  // places the walk was taken up again from, the world having grown
        int moves = 0;
        int ways = 0;
        while (true) {
            if (!live(id)) {
                return new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, ways);
            }
            // 1. As far as a walk goes.
            NavResult r = go(goal).get();
            moves += r.moves();
            // The moves that walk found the body would not make: no walk goes by them.
            Set<String> banned = walker == null ? Set.of() : walker.bannedLast();
            Vec3 me = movement.position();
            if (!live(id) || me == null || r.status() == NavResult.Status.INTERRUPTED
                    || r.status() == NavResult.Status.NOT_AVAILABLE) {
                return new NavResult(me == null ? NavResult.Status.NOT_AVAILABLE
                        : r.status() == NavResult.Status.NOT_AVAILABLE ? r.status() : NavResult.Status.INTERRUPTED,
                        me, moves, ways);
            }
            int[] c = Walker.cellOf(me);
            BlockPos at = new BlockPos(c[0], c[1], c[2]);
            if (r.arrived() || arrived.test(at)) {
                return new NavResult(NavResult.Status.ARRIVED, me, moves, ways);
            }
            if (walker == null) {
                return new NavResult(r.status(), me, moves, ways);
            }
            // The world ends by the body: the server has not shown it yet - the body was just set down
            // here (alive again after a death, teleported), or walked faster than the chunks came.
            // Nothing is a way before it is seen: waited for, then the walk again.
            if (awaitWorld(id, at)) {
                continue;
            }
            // 2. No walk from here: a way made to where one goes on.
            landed(id);
            PathFinder.WANTED.set(() -> live(id));      // the searches below end with the job
            PathFinder.Reach reach = walker.reach(at, me.y(), goal, true, banned);
            if (reach.way().complete()) {
                // A whole walk goes from here, and the walk just made did not get there: what stopped it
                // is on the way (a mob in a doorway, something fallen in), not the lack of one - no way
                // is made for that. Once more from here; a second time from the same place is the answer.
                PathFinder.WANTED.remove();
                if (!notWalked.add(at)) {
                    log.info("meridian-core: a walk to {} goes from {} and was not walked ({}) - no way is made for that",
                            goal, text(at), r.status());
                    return new NavResult(r.status(), me, moves, ways);
                }
                continue;
            }
            if (nearer(reach.way(), me, goal) && lookedAgain.add(at)) {
                // No whole walk - but one that gets nearer the goal than the body is, and the walk just
                // made did not take it: it looked before the server had shown that much (alive again a
                // moment ago, chunks still coming in). Walked - once more from here.
                PathFinder.WANTED.remove();
                continue;
            }
            Map<BlockPos, Double> sources = new HashMap<>(reach.places());
            sources.keySet().removeAll(unreached);
            sources.putIfAbsent(at, 0.0);
            String filler = filler(rules);
            Ways.Made way = Ways.connect(grid(keep, rules, refused), sources, arrived, place -> {
                PathFinder.Reach on = walker.reach(place, world.standAt(place).orElse(place.y()), goal, false, banned);
                if (!on.way().complete()) {
                    return new Ways.On(false, on.places(), Set.of());
                }
                // What the walk on stands on: the way made must leave it whole.
                Set<BlockPos> floors = new HashSet<>();
                floors.add(new BlockPos(place.x(), place.y() - 1, place.z()));
                for (Navigator.Step st : on.way().steps()) {
                    floors.add(new BlockPos(st.x(), st.y() - 1, st.z()));
                }
                return new Ways.On(true, Map.of(), floors);
            }, fillers(filler), (int) Math.floor(movement.capabilities().safeDrop()), WAY_NODES,
                    new BlockPos(goal.x(), goal.y(), goal.z()));
            PathFinder.WANTED.remove();
            if (!live(id)) {
                return new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, ways);
            }
            if (way == null) {
                log.info("meridian-core: no way to {} from {}: no walk ({}), and none to make - {} place(s) walked to, "
                                + "{} block(s) that would not go", goal, text(at), reach.way().why(), sources.size(),
                        refused.size());
                return new NavResult(NavResult.Status.NO_PATH, me, moves, ways);
            }
            ways++;
            log.info("meridian-core: a way made to {}: from {} to {}, {} step(s), {} block(s) to break, {} to put down - {}",
                    goal, text(way.from()), text(way.to()), way.steps().size(),
                    way.steps().stream().mapToInt(st -> st.breaks().size()).sum(),
                    way.steps().stream().filter(st -> st.place() != null).count(),
                    way.arrives() ? "to the goal" : "a walk goes on from there");
            // 3. To where it starts, then along it.
            if (!at.equals(way.from())) {
                NavResult w = go(Goal.at(way.from())).get();
                moves += w.moves();
                if (!live(id) || w.status() == NavResult.Status.INTERRUPTED) {
                    return new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, ways);
                }
                if (!w.arrived()) {
                    log.info("meridian-core: the way's start {} was not got to ({}) - another", text(way.from()), w.status());
                    unreached.add(way.from());
                    continue;
                }
            }
            int before = refused.size();
            int made = follow(id, way, rules, broken, filler, refused);
            moves += made;
            if (made < way.steps().size() && refused.size() == before && live(id)) {
                // A step did not go and nothing says which block: not that way again.
                refused.add(way.steps().get(made).to());
            }
        }
    }

    /** Whether the walk ends at least two blocks nearer the goal than the body stands. */
    private static boolean nearer(PathFinder.Way way, Vec3 me, Goal goal) {
        if (way.steps().isEmpty()) {
            return false;
        }
        Navigator.Step last = way.steps().get(way.steps().size() - 1);
        double from = Math.sqrt(sq(me.x() - goal.x() - 0.5) + sq(me.y() - goal.y()) + sq(me.z() - goal.z() - 0.5));
        double to = Math.sqrt(sq(last.x() - goal.x()) + sq(last.y() - goal.y()) + sq(last.z() - goal.z()));
        return to < from - 2;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** A chunk's side, in blocks. */
    static final int CHUNK = 32;

    /**
     * Waits for the server to show the world round the body, where it has not: the body's own chunk
     * and the eight about it, at the body's height. The server sends what lies round the player, so
     * they come - or the hold on the body ends, and the wait with it.
     *
     * <p>Round it, not one way only: alive again by a chunk's edge, the chunk towards the goal was
     * known still and the body's own was not (22.11) - eight blocks ahead looked shown, and the way was
     * looked for from a body standing in nothing: "no way", at once.
     *
     * @return whether there was anything to wait for (and the walk is to be tried again)
     */
    private boolean awaitWorld(int id, BlockPos at) throws InterruptedException {
        BlockPos missing = unshown(at);
        if (missing == null) {
            return false;
        }
        log.info("meridian-core: the world round the body is not shown yet (at {}, standing at {}) - waiting for it",
                text(missing), text(at));
        while (live(id) && unshown(at) != null) {
            Thread.sleep(50);       // the next chunks the server sends say
        }
        return live(id);
    }

    /** A cell of the nine chunks round {@code at} the server has not shown, at the feet's height or under them; {@code null}: all shown. */
    private BlockPos unshown(BlockPos at) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int y = Math.max(0, at.y() - 1); y <= at.y(); y++) {
                    int x = at.x() + ox * CHUNK;
                    int z = at.z() + oz * CHUNK;
                    if (!world.known(x, y, z)) {
                        return new BlockPos(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    /** Waits for the body to be on the ground: a way is looked for from where it stands. */
    private void landed(int id) throws InterruptedException {
        while (live(id) && !movement.onGround() && movement.position() != null) {
            Movement.State st = movement.state();
            if (st != null && (st.swimming() || st.climbing() || st.flying())) {
                return;
            }
            Thread.sleep(20);       // a body in the air comes down by the server's own gravity: the next tick says
        }
    }

    /**
     * The steps of a way made in turn: the blocks of each broken, then the block it puts down, then the
     * move. Stops at a step that does not go, the block at fault put into {@code refused}.
     *
     * @return the steps made
     */
    private int follow(int id, Ways.Made way, Rules rules, List<BlockPos> broken, String filler, Set<BlockPos> refused)
            throws Exception {
        int done = 0;
        for (Ways.Step st : way.steps()) {
            for (BlockPos c : st.breaks()) {
                if (!live(id)) {
                    return done;
                }
                if (world.blockAt(c.x(), c.y(), c.z()).isAir()) {
                    continue;
                }
                if (nearLava(c) || !breakInReach(c, rules, broken)) {
                    log.info("meridian-core: the way did not go on at {}: {} would not break", text(st.to()), text(c));
                    refused.add(c);
                    return done;
                }
            }
            if (!live(id)) {
                return done;
            }
            switch (st.kind()) {
                case PILLAR -> {
                    Outcome o = filler == null ? Outcome.NO_ITEM : pillarNow(id, filler, 1);
                    if (o != Outcome.PLACED) {
                        log.info("meridian-core: the way's pillar did not go up at {} ({})", text(st.place()), o);
                        refused.add(st.place());
                        return done;
                    }
                }
                case BRIDGE -> {
                    Outcome o = filler == null ? Outcome.NO_ITEM : placeNow(id, st.place(), filler, 0, false);
                    if (o != Outcome.PLACED && o != Outcome.OCCUPIED) {
                        log.info("meridian-core: the way's block did not go down at {} ({})", text(st.place()), o);
                        refused.add(st.place());
                        return done;
                    }
                    if (!stepTo(id, st)) {
                        return done;
                    }
                }
                case MOVE, WALK -> {
                    if (!stepTo(id, st)) {
                        return done;
                    }
                }
            }
            done++;
        }
        return done;
    }

    private boolean stepTo(int id, Ways.Step st) throws Exception {
        NavResult r = go(Goal.at(st.to())).get();
        if (live(id) && r.status() != NavResult.Status.ARRIVED) {
            log.info("meridian-core: the way did not go on at {} ({})", text(st.to()), r.status());
            return false;
        }
        return live(id);
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
     * The world as a way made sees it: what the body may be in, stand on, break, build on.
     * Never broken: {@code keep}; {@code refused}; a block that holds something or is used (a chest, a
     * bench, a door - {@link #precious}) and the block one stands on.
     *
     * <p>{@link Access#within} is what may be broken to get AT a block from where the body stands; the
     * way to that place is another matter - into a pit with sheer walls there is none but a staircase
     * cut in the rock beside it. So a block outside the box may be broken for the way, at a price
     * ({@link #OUTSIDE}): the way keeps to the selection, or is built, where that is near as short.
     *
     * <p>The owner's rule (21.11): out there a block is not broken while something that is no block
     * stands on it ({@link #carries} - a torch, a plant, a table: it would come down) - the way is cut
     * round it. Nor is the thing itself broken ({@link #model}): the body walks through what it walks
     * through (grass, a torch), and goes round what it does not (a table). Inside the selection the
     * job digs both anyway.
     */
    private Ways.Grid grid(Set<BlockPos> keep, Rules rules, Set<BlockPos> refused) {
        Player player = world.player().orElse(null);
        BlockBox within = rules.access().within();
        Map<BlockPos, Boolean> clear = new HashMap<>();
        return new Ways.Grid() {
            @Override
            public boolean known(BlockPos p) {
                return world.known(p.x(), p.y(), p.z());
            }

            @Override
            public boolean air(BlockPos p) {
                // Nothing to break to be in the cell: air - or, outside the selection, a thing that is
                // no block and that the body goes through as it stands.
                return world.blockAt(p.x(), p.y(), p.z()).isAir()
                        || (!(within != null && within.contains(p)) && model(p) && !movement.cell(p).solid());
            }

            @Override
            public boolean passable(BlockPos p) {
                if (player == null || refused.contains(p)) {
                    return false;
                }
                return clear.computeIfAbsent(p, k -> {
                    boolean inside = within != null && within.contains(k);
                    if (!inside && model(k)) {
                        // No block, and not the way's to break: gone through where the body goes
                        // through it, gone round where it does not.
                        Movement.Cell m = movement.cell(k);
                        return !keep.contains(k) && !m.solid() && m.fluid() == null && !m.hurts();
                    }
                    if (!Boolean.TRUE.equals(tunnelCell(k, keep, player))) {
                        return false;
                    }
                    if (world.blockAt(k.x(), k.y(), k.z()).isAir()) {
                        return true;
                    }
                    if (precious(k) || precious(new BlockPos(k.x(), k.y() + 1, k.z()))) {
                        return false;
                    }
                    return inside || !carries(k);
                });
            }

            @Override
            public boolean floor(BlockPos p) {
                return movement.cell(p).solid() && !precious(p);
            }

            @Override
            public boolean buildable(BlockPos p) {
                if (refused.contains(p) || !world.known(p.x(), p.y(), p.z())
                        || !world.blockAt(p.x(), p.y(), p.z()).isAir()) {
                    return false;       // into air only: nothing is built into a plant, or over one
                }
                Movement.Cell m = movement.cell(p);
                return m.fluid() == null && !m.hurts();
            }

            @Override
            public double toll(BlockPos p) {
                return within != null && !within.contains(p) ? OUTSIDE : 0;
            }

            @Override
            public boolean falls(BlockPos p) {
                Block b = world.blockAt(p.x(), p.y(), p.z());
                String name = b.isAir() || b.type() == null ? null : b.type().name();
                return name != null && (name.contains("Sand") || name.contains("Gravel"));
            }
        };
    }

    /**
     * Whether something that is no block stands on the block: the cell over it holds what is drawn as
     * a model, not as a cube - furniture, a plant, a torch, a ladder's foot. Broken from under it, it
     * drops.
     */
    private boolean carries(BlockPos p) {
        return model(new BlockPos(p.x(), p.y() + 1, p.z()));
    }

    /** Whether the cell holds something that is no block: drawn as a model, not as a cube. */
    private boolean model(BlockPos p) {
        java.util.function.IntFunction<meridian.protocol.BlockType> types = blockTypes;
        Block b = world.blockAt(p.x(), p.y(), p.z());
        if (types == null || b.isAir() || b.type() == null) {
            return false;
        }
        meridian.protocol.BlockType t = types.apply(b.type().id());
        return t != null && t.drawType == meridian.protocol.DrawType.Model;
    }

    /**
     * A block a way never breaks, and never the block under it either: one that is used - it has an
     * interaction of its own (a chest opens, a bench is worked at, a door swings). Its things would
     * spill, or the owner's work be undone.
     */
    private boolean precious(BlockPos p) {
        java.util.function.IntFunction<meridian.protocol.BlockType> types = blockTypes;
        Block b = world.blockAt(p.x(), p.y(), p.z());
        if (types == null || b.isAir() || b.type() == null) {
            return false;
        }
        meridian.protocol.BlockType t = types.apply(b.type().id());
        return t != null && t.interactions != null && !t.interactions.isEmpty();
    }

    /**
     * Whether a way may go through the cell: air with no fluid, or a block the hotbar breaks that is
     * not one kept, with no fluid and no lava beside it.
     */
    private Boolean tunnelCell(BlockPos cell, Set<BlockPos> keep, Player player) {
        if (keep.contains(cell) || !world.known(cell.x(), cell.y(), cell.z())) {
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

    /** The server's block types by id, for which carried blocks a way may be built of ({@link #filler}). */
    private volatile java.util.function.IntFunction<meridian.protocol.BlockType> blockTypes;

    public void blockTypes(java.util.function.IntFunction<meridian.protocol.BlockType> types) {
        this.blockTypes = types;
    }

    /**
     * The block a way is built of - a pillar's, a bridge's: of those carried, a plain cube of rock or
     * soil that stays where it is put (stone, cobble, dirt; not sand or gravel, not an ore, nothing
     * that is used), the one carried most of. {@code null} for none.
     */
    private String filler(Rules rules) {
        java.util.function.Function<String, java.util.Optional<meridian.protocol.ItemBase>> it = items;
        java.util.function.IntFunction<meridian.protocol.BlockType> types = blockTypes;
        if (it == null || types == null) {
            return null;
        }
        String best = null;
        int most = 0;
        for (Map.Entry<String, Integer> e : held().entrySet()) {
            String id = e.getKey();
            if (!(id.startsWith("Rock_") || id.startsWith("Soil_")) || id.contains("Sand") || id.contains("Gravel")) {
                continue;       // what a digger has by the hundred and nobody misses
            }
            meridian.protocol.ItemBase item = it.apply(id).orElse(null);
            if (item == null || item.blockId <= 0) {
                continue;
            }
            meridian.protocol.BlockType t = types.apply(item.blockId);
            if (t == null || t.material != meridian.protocol.BlockMaterial.Solid
                    || t.drawType != meridian.protocol.DrawType.Cube
                    || (t.interactions != null && !t.interactions.isEmpty())) {
                continue;
            }
            if (e.getValue() > most) {
                best = id;
                most = e.getValue();
            }
        }
        return best;
    }

    private int fillers(String filler) {
        return filler == null ? 0 : held().getOrDefault(filler, 0);
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
        return null; // 0.5.9: tools carry no break shape - every tool breaks the one block
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
            return Boolean.TRUE.equals(run.get());
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
                    if (distSq(at, drop) > PICKUP_REACH * PICKUP_REACH
                            && distSq(pickupSpot(at, drop), drop) > PICKUP_RADIUS * PICKUP_RADIUS) {
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

    /** Towards the drop, within a quarter block of this standing cell's centre. */
    private static Vec3 pickupSpot(Vec3 at, Vec3 drop) {
        int[] c = Walker.cellOf(at);
        return new Vec3(Math.max(c[0] + 0.25, Math.min(c[0] + 0.75, drop.x())), at.y(),
                Math.max(c[2] + 0.25, Math.min(c[2] + 0.75, drop.z())));
    }

    /** A head-high drop at the back of an alcove can be just outside pickup radius at cell centre. */
    private void approachPickup(int id, int item) throws Exception {
        pause();
        Vec3 at = movement.position();
        Vec3 drop = entities.positionOf(item).orElse(null);
        if (!live(id) || at == null || drop == null || entities.itemOf(item).isEmpty()
                || distSq(at, drop) <= PICKUP_REACH * PICKUP_REACH) return;
        Movement.Move step = new Movement.Move(Movement.Kind.WALK, pickupSpot(at, drop), Movement.Gait.WALK);
        Movement.Prediction prediction = movement.predict(step);
        Vec3 end = prediction.result().at();
        if (prediction.hurt() || !prediction.result().arrived() || end == null
                || Math.abs(end.y() - at.y()) > 0.1
                || distSq(end, drop) > PICKUP_RADIUS * PICKUP_RADIUS) return;
        int[] from = Walker.cellOf(at), to = Walker.cellOf(end);
        if (from[0] != to[0] || from[2] != to[2]) return;
        // The real physics keeps the body out of the wall, as in the prediction; no block is broken.
        movement.move(step).get();
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
                log.info("meridian-core: the drop at {} is left - no place to pick it up from (under water, in a wall)",
                        text(cell));
                passed.add(best);
                continue;
            }
            NavResult r = go(goal).get();
            if (live(id) && r.status() != NavResult.Status.ARRIVED) {
                Vec3 now = movement.position();
                Goal alternative = now == null ? null : pickupFrom(bestAt, now);
                if (alternative != null && !alternative.equals(goal)) {
                    r = go(alternative).get();
                }
            }
            if (!live(id)) {
                break;
            }
            if (r.status() == NavResult.Status.ARRIVED) {
                approachPickup(id, best);
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
                    log.info("meridian-core: the drop at {} is left - {}", text(cell),
                            r.status() == NavResult.Status.ARRIVED ? "not taken from where it lies" : "not got to (" + r.status() + ")");
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
                NavResult r = go(Goal.at(st.cell())).get();
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
            NavResult nav = go(Goal.at(best)).get();
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
            // The chest kept, and the block it stands on: a chest with nothing under it comes off.
            travel(id, Goal.near(chest, 2), beside(chest), Set.of(chest), new Rules(Access.ANY, Tools.BEST),
                    new ArrayList<>());
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
