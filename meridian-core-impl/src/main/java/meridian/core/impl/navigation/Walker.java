package meridian.core.impl.navigation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import meridian.core.api.BlockPos;
import meridian.core.api.Goal;
import meridian.core.api.InteractionControl;
import meridian.core.api.Movement;
import meridian.core.api.NavResult;
import meridian.core.api.Navigator.Step;
import meridian.core.api.Navigator;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import org.slf4j.Logger;

/**
 * The {@link Navigator}: finds a way ({@link PathFinder}) from where the player is, plays it on a
 * copy of the body first ({@link Movement#predict(List)}) - a move that would not arrive is banned
 * and the way found again - then hands the whole way to {@link Movement} at once, so the body runs
 * through its points without stopping. A move that goes wrong for real, or a way that only got
 * nearer (the world beyond not sent yet), means looking again from where the body is - until the
 * goal, no way, or too many tries.
 */
public final class Walker implements Navigator {

    /** Looks again at most this often in one trip. */
    static final int MAX_REPLANS = 12;
    /** Searches again at most this often for one way when its moves would not arrive. */
    static final int MAX_BANS = 8;
    /** A straight walk at least this long is sprinted, stamina allowing. */
    static final double SPRINT_FROM = 6.0;
    /** The stamina a sprint along the way leaves, for the run-ups of leaps: a sprint takes 1 a second. */
    static final double STAMINA_KEPT = 4.0;

    private final Movement movement;
    private final World world;
    /** Opens doors on the way; {@code null} where core does not offer it - then a door is a wall. */
    private final InteractionControl interactions;
    private final PathFinder finder;
    private final Executor executor;
    private final Logger log;
    /** The trip under way, by id (an urgency stamp); a new one, or a cancel, moves it on. */
    private final AtomicInteger trip = new AtomicInteger();
    /** The trip's hold on a game's body, given back when it ends; {@code null} for none. */
    private volatile Movement.Wheel took;
    /** The player's stamina now; {@code NaN} when it is not known. */
    private final java.util.function.DoubleSupplier stamina;
    /** Core's urgent holds: a trip begun before one waits while it is held ({@link Movement#takeFirst}). */
    private final meridian.core.impl.movement.Urgency urgency;
    /** The trips under way or waiting, by id - their futures. */
    private final java.util.Map<Integer, CompletableFuture<NavResult>> trips = new java.util.concurrent.ConcurrentHashMap<>();
    /** The trips an urgent hold keeps waiting, by id - what ends each, should it be cancelled meanwhile. */
    private final java.util.Map<Integer, Runnable> parked = new java.util.concurrent.ConcurrentHashMap<>();

    public Walker(Movement movement, World world, InteractionControl interactions, Executor executor, Logger log) {
        this(movement, world, interactions, () -> Double.NaN, executor, log);
    }

    /** With the player's stamina ({@code NaN} unknown), for sprinting the long straight walks. */
    public Walker(Movement movement, World world, InteractionControl interactions,
                  java.util.function.DoubleSupplier stamina, Executor executor, Logger log) {
        this.stamina = stamina;
        this.movement = movement;
        this.world = world;
        this.interactions = interactions;
        this.finder = new PathFinder(movement, world,
                movement instanceof meridian.core.impl.movement.MovementImpl m ? m::runUps : () -> null);
        this.executor = executor;
        this.log = log;
        this.urgency = movement instanceof meridian.core.impl.movement.MovementImpl m ? m.urgency()
                : new meridian.core.impl.movement.Urgency();
    }

    @Override
    public boolean available() {
        return movement.available();
    }

    @Override
    public List<Step> plan(Goal goal) {
        Vec3 p = movement.position();
        if (p == null) {
            return List.of();
        }
        int[] c = cellOf(p);
        return checked(c, p, goal, new HashSet<>()).steps();
    }

    /**
     * The way over the grid as found - no move banned, not straightened - played on a copy of the body
     * tick by tick, for looking into a move: the steps, how many arrived, and the ticks.
     */
    public java.util.Map<String, Object> trace(Goal goal) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        Vec3 p = movement.position();
        if (p == null || !(movement instanceof meridian.core.impl.movement.MovementImpl impl)) {
            out.put("error", "no body");
            return out;
        }
        int[] c = cellOf(p);
        PathFinder.Way way = finder.find(c[0], c[1], c[2], p.y(), goal, new HashSet<>());
        List<String> ticks = new ArrayList<>();
        Movement.Prediction pred = impl.predict(moves(way.steps().subList(0, untilDoor(way.steps()))), ticks);
        out.put("steps", way.steps().stream().map(s -> s.kind() + " " + s.x() + " " + s.y() + " " + s.z() + " " + s.gait())
                .toList());
        out.put("arrived", pred.arrived());
        out.put("result", pred.result().status().name());
        out.put("trace", ticks);
        return out;
    }

    /**
     * A way found and played on a copy of the body; a move that would not arrive is banned and the
     * way found again. What is left is a way the body can go, or as far along one as it can.
     */
    private PathFinder.Way checked(int[] c, Vec3 p, Goal goal, Set<String> banned) {
        return straightened(p, grid(c, p, goal, banned));
    }

    /**
     * The checked way with its level walks straightened ({@link PathFinder#straightened}), played on a
     * copy of the body first as well: if the straight lines would not do, the way as found.
     */
    private PathFinder.Way straightened(Vec3 p, PathFinder.Way way) {
        List<Step> straight = finder.straightened(p, way.steps());
        List<Step> fast = sprinted(p, straight);
        for (List<Step> ws : fast == straight ? List.of(straight) : List.of(fast, straight)) {
            if (ws == way.steps()) {
                return way;
            }
            int upTo = untilDoor(ws);
            Movement.Prediction pred = movement.predict(moves(ws.subList(0, upTo)));
            if ((pred.arrived() >= upTo && !pred.hurt()) || unknown(pred)) {
                return new PathFinder.Way(ws, way.complete());
            }
        }
        log.info("navigation: the straightened way would not make it - the way as found");
        return way;
    }

    /**
     * The long straight walks sprinted ({@link #SPRINT_FROM} or more): as many as the stamina pays
     * for at a second a second, keeping {@link #STAMINA_KEPT} for the leaps' run-ups. None while the
     * stamina is not known. The same steps when none is.
     */
    private List<Step> sprinted(Vec3 p, List<Step> steps) {
        double left = stamina.getAsDouble() - STAMINA_KEPT;
        double speed = movement.capabilities().sprintSpeed();
        if (!(left > 0) || !(speed > 0)) {
            return steps;
        }
        List<Step> out = new ArrayList<>(steps.size());
        boolean any = false;
        double x = p.x();
        double z = p.z();
        for (Step s : steps) {
            double len = Math.hypot(s.x() + 0.5 - x, s.z() + 0.5 - z);
            Step o = s;
            if (s.kind() == Movement.Kind.WALK && s.door() == null && s.gait() != Movement.Gait.SPRINT
                    && len >= SPRINT_FROM && len / speed <= left) {
                left -= len / speed;
                o = new Step(s.kind(), s.x(), s.y(), s.z(), s.feet(), Movement.Gait.SPRINT, s.door());
                any = true;
            }
            out.add(o);
            x = s.x() + 0.5;
            z = s.z() + 0.5;
        }
        return any ? out : steps;
    }

    /** A way over the grid, found and played on a copy of the body, moves that would not arrive banned. */
    private PathFinder.Way grid(int[] c, Vec3 p, Goal goal, Set<String> banned) {
        PathFinder.Way way = finder.find(c[0], c[1], c[2], p.y(), goal, banned);
        for (int i = 0; i < MAX_BANS && !way.steps().isEmpty(); i++) {
            // Up to the first shut door: past it is played once it is open.
            int upTo = untilDoor(way.steps());
            if (upTo == 0) {
                return way;
            }
            Movement.Prediction pred = movement.predict(moves(way.steps().subList(0, upTo)));
            if ((pred.arrived() >= upTo && !pred.hurt()) || unknown(pred)) {
                return way;
            }
            int bad = Math.min(pred.arrived(), way.steps().size() - 1);
            Step s = way.steps().get(bad);
            int[] from = bad == 0 ? c : new int[] {way.steps().get(bad - 1).x(), way.steps().get(bad - 1).y(),
                    way.steps().get(bad - 1).z()};
            banned.add(PathFinder.edge(from[0], from[1], from[2], s.x(), s.y(), s.z(), s.kind()));
            log.info("navigation: {} onto ({}, {}, {}) would not make it ({}) - looking for another way", s.kind(),
                    s.x(), s.y(), s.z(), pred.hurt() ? "hurt" : pred.result().status());
            way = finder.find(c[0], c[1], c[2], p.y(), goal, banned);
        }
        // Still not clean: keep the part the body can go.
        int upTo = untilDoor(way.steps());
        Movement.Prediction pred = movement.predict(moves(way.steps().subList(0, upTo)));
        if (pred.arrived() < upTo && !unknown(pred)) {
            return new PathFinder.Way(new ArrayList<>(way.steps().subList(0, pred.arrived())), false);
        }
        return way;
    }

    /**
     * A prediction with no body to play it on - a game's player whose body is the game's again: it
     * says nothing about the way, so no move is banned for it.
     */
    private static boolean unknown(Movement.Prediction pred) {
        return pred.result().status() == Movement.MoveResult.Status.NOT_AVAILABLE;
    }

    /** How many steps come before the first one through a shut door. */
    private static int untilDoor(List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).door() != null) {
                return i;
            }
        }
        return steps.size();
    }

    private static List<Movement.Move> moves(List<Step> steps) {
        List<Movement.Move> out = new ArrayList<>(steps.size());
        for (Step s : steps) {
            out.add(new Movement.Move(s.kind(), new Vec3(s.x() + 0.5, s.feet(), s.z() + 0.5),
                    s.gait() == null ? Movement.Gait.RUN : s.gait()));
        }
        return out;
    }

    @Override
    public void cancel() {
        trip.incrementAndGet();
        // Every trip: those an urgent hold keeps waiting too.
        for (Runnable end : List.copyOf(parked.values())) {
            end.run();
        }
        parked.clear();
        movement.stop();
        giveBack();
    }

    @Override
    public CompletableFuture<NavResult> goTo(Goal goal) {
        return start(new Fixed(goal));
    }

    @Override
    public CompletableFuture<NavResult> chase(java.util.function.Supplier<java.util.Optional<Vec3>> target,
                                              double within) {
        if (target == null) {
            return CompletableFuture.completedFuture(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), 0, 0));
        }
        return start(new Chase(target, Math.max(0.5, within)));
    }

    /** Begins a trip to {@code target}, ending the one under way - or leaving it waiting, when an urgent hold holds it back. */
    private CompletableFuture<NavResult> start(Target target) {
        int before = trip.get();
        int id = (int) urgency.stamp();
        trip.set(id);
        // The trip before ends - unless an urgent hold keeps it waiting: then it goes on after.
        boolean keep = trips.containsKey(before) && urgency.holdsBack(before);
        if (keep) {
            log.info("navigation: the trip under way waits for an urgent one");
        }
        movement.stop();
        CompletableFuture<NavResult> done = new CompletableFuture<>();
        // A game's body is held for the trip - a hold of its own, so whoever else holds it keeps it.
        // The trip before's hold goes once this one's is in hand, so the body does not go back between.
        Movement.Wheel wheel = movement.take().orElse(null);
        Movement.Wheel prior = took;
        took = wheel;
        if (prior != null && !keep) {
            prior.release();
        }
        if (wheel == null) {
            done.complete(new NavResult(NavResult.Status.NOT_AVAILABLE, movement.position(), 0, 0));
            return done;
        }
        trips.put(id, done);
        // The game takes the body back (a key, the server's teleport, a death): the trip is over.
        wheel.released().thenRun(() -> end(id));
        done.whenComplete((r, e) -> {
            trips.remove(id);
            parked.remove(id);
            wheel.release();
            if (took == wheel) {
                took = null;
            }
            if (done.isCancelled() && trip.compareAndSet(id, id + 1)) {
                // Cancelled by its caller: this trip only.
                movement.stop();
            }
        });
        executor.execute(() -> leg(id, target, done, 0, 0, Double.MAX_VALUE, new HashSet<>()));
        return done;
    }

    /** Ends trip {@code id}, the one under way or one waiting. */
    private void end(int id) {
        trip.compareAndSet(id, id + 1);
        Runnable waiting = parked.remove(id);
        if (waiting != null) {
            waiting.run();
        }
    }

    /** Whether trip {@code id} is still on: the one under way, or one an urgent hold keeps waiting. */
    private boolean live(int id) {
        return trip.get() == id || parked.containsKey(id);
    }

    /**
     * Holds trip {@code id} back while an urgent hold keeps it waiting: it goes on with {@code resume}
     * once the hold is given back - the trip under way again, whatever the urgent one left going
     * stopped. {@code false} when nothing holds it back.
     */
    private boolean heldBack(int id, CompletableFuture<NavResult> done, int moves, int replans, Runnable resume) {
        if (!urgency.holdsBack(id)) {
            return false;
        }
        parked.put(id, () -> done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves,
                replans)));
        urgency.turn(id).thenRunAsync(() -> {
            if (parked.remove(id) == null || done.isDone()) {
                return;     // ended while it waited
            }
            if (trip.get() != id) {
                trip.set(id);
                movement.stop();
            }
            log.info("navigation: the urgent hold is given back - the trip goes on");
            resume.run();
        }, executor);
        return true;
    }

    /** Gives back the trip's hold on the body. */
    private void giveBack() {
        Movement.Wheel w = took;
        took = null;
        if (w != null) {
            w.release();
        }
    }

    /** Where a trip goes: a fixed goal, or something that moves ({@link Chase}). */
    private interface Target {

        /** The goal now; {@code null} once there is nothing to go to. */
        Goal goal();

        /** Whether the body is there. */
        boolean reached(Vec3 feet, boolean onGround);

        /** Whether the target has gone off the goal a way was found to. */
        boolean moved(Goal plannedFor);

        /** Whether it never moves - nothing to watch while the way is gone. */
        boolean fixed();

        /**
         * With no way on: whether the body is as near as it will get, and that is near enough - a
         * chased thing against a wall, the cells beside it out of reach, the body a hair past the
         * distance asked. {@code false} for a fixed goal.
         */
        default boolean nearEnough(Vec3 feet) {
            return false;
        }
    }

    private record Fixed(Goal goal) implements Target {
        @Override
        public boolean reached(Vec3 feet, boolean onGround) {
            int[] c = cellOf(feet);
            return goal.reachedBy(c[0], c[1], c[2]) && onGround;
        }

        @Override
        public boolean moved(Goal plannedFor) {
            return false;
        }

        @Override
        public boolean fixed() {
            return true;
        }
    }

    /** Something that moves: near enough within {@code within}, the way found again as it goes. */
    private record Chase(java.util.function.Supplier<java.util.Optional<Vec3>> where, double within) implements Target {

        private Vec3 now() {
            try {
                return where.get().orElse(null);
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public Goal goal() {
            Vec3 p = now();
            if (p == null) {
                return null;
            }
            int[] c = cellOf(p);
            return new Goal(c[0], c[1], c[2], within);
        }

        @Override
        public boolean reached(Vec3 feet, boolean onGround) {
            Vec3 p = now();
            return p != null && Math.hypot(p.x() - feet.x(), p.z() - feet.z()) <= within
                    && Math.abs(p.y() - feet.y()) <= Math.max(CHASE_UP, within);
        }

        @Override
        public boolean nearEnough(Vec3 feet) {
            Vec3 p = now();
            return p != null && Math.hypot(p.x() - feet.x(), p.z() - feet.z()) <= within + CHASE_SLACK
                    && Math.abs(p.y() - feet.y()) <= Math.max(CHASE_UP, within);
        }

        @Override
        public boolean moved(Goal plannedFor) {
            Vec3 p = now();
            if (p == null || plannedFor == null) {
                return true;
            }
            double off = Math.hypot(p.x() - (plannedFor.x() + 0.5), p.z() - (plannedFor.z() + 0.5));
            return off > Math.max(CHASE_DRIFT, within * 0.5) || Math.abs(p.y() - plannedFor.y()) > CHASE_UP;
        }

        @Override
        public boolean fixed() {
            return false;
        }
    }

    /** A chase is near enough no more than this up or down, at the least. */
    static final double CHASE_UP = 1.5;
    /** A chase with no way nearer ends as there this much past the distance asked: as near as it gets. */
    static final double CHASE_SLACK = 0.5;
    /** A chased target this far off the place its way was found to has the way found again. */
    static final double CHASE_DRIFT = 1.0;
    /** How often a chase looks where its target is while the way is gone. */
    static final long CHASE_EVERY_MS = 100;

    /** One leg: find a way from here, check it, then play it. */
    private void leg(int id, Target target, CompletableFuture<NavResult> done, int moves, int replans, double lastGap,
                     Set<String> banned) {
        if (!live(id) || done.isDone()) {
            done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
            return;
        }
        if (heldBack(id, done, moves, replans, () -> leg(id, target, done, moves, replans, Double.MAX_VALUE, banned))) {
            return;
        }
        Vec3 p = movement.position();
        if (p == null) {
            done.complete(new NavResult(NavResult.Status.NOT_AVAILABLE, null, moves, replans));
            return;
        }
        if (target.reached(p, movement.onGround())) {
            done.complete(new NavResult(NavResult.Status.ARRIVED, p, moves, replans));
            return;
        }
        Goal goal = target.goal();
        if (goal == null) {
            log.info("navigation: what was chased is gone");
            done.complete(new NavResult(NavResult.Status.INTERRUPTED, p, moves, replans));
            return;
        }
        int[] c = cellOf(p);
        PathFinder.Way way = checked(c, p, goal, banned);
        if (!live(id)) {
            // The body went back to the player while the way was looked for.
            done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
            return;
        }
        double gap = gap(way, c, goal);
        if (way.steps().isEmpty() || (!way.complete() && gap >= lastGap)) {
            if (target.nearEnough(p)) {
                log.info("navigation: no way nearer to {}, and near enough", goal);
                done.complete(new NavResult(NavResult.Status.ARRIVED, p, moves, replans));
                return;
            }
            log.info("navigation: no way from {} to {} ({} move(s), {} replan(s))", p, goal, moves, replans);
            done.complete(new NavResult(NavResult.Status.NO_PATH, p, moves, replans));
            return;
        }
        log.info("navigation: {} move(s) towards {}{}", way.steps().size(), goal, way.complete() ? "" : " (as far as known)");
        play(id, target, goal, done, way, moves, replans, gap, banned);
    }

    /** All the way's moves at once; the first that goes wrong stops the rest, and the way is looked for again. */
    private void play(int id, Target target, Goal goal, CompletableFuture<NavResult> done, PathFinder.Way way, int moves,
                      int replans, double gap, Set<String> banned) {
        int door = untilDoor(way.steps());
        if (door < way.steps().size()) {
            throughDoor(id, target, done, way, door, moves, replans, banned);
            return;
        }
        if (heldBack(id, done, moves, replans, () -> leg(id, target, done, moves, replans, Double.MAX_VALUE, banned))) {
            return;
        }
        List<Step> steps = way.steps();
        List<Movement.Move> ms = moves(steps);
        List<CompletableFuture<Movement.MoveResult>> results = new ArrayList<>(ms.size());
        for (Movement.Move m : ms) {
            results.add(movement.move(m));
        }
        Watch watch = target.fixed() ? null : watch(id, target, goal, results.get(results.size() - 1));
        follow(id, target, done, way, results, 0, moves, replans, gap, banned, watch);
    }

    /**
     * Why a chase stopped its own moves: there already, or the target moved off the way's end, or
     * gone. Set by the watch, read by the move's end.
     */
    private static final class Watch {
        volatile String why;
    }

    /** Looks where a chased target is while the way plays: there, moved, or gone - the moves stop. */
    private Watch watch(int id, Target target, Goal goal, CompletableFuture<Movement.MoveResult> last) {
        Watch w = new Watch();
        Runnable[] look = new Runnable[1];
        look[0] = () -> {
            if (last.isDone() || !live(id) || trip.get() != id || w.why != null) {
                return;
            }
            Vec3 feet = movement.position();
            if (feet != null && target.reached(feet, movement.onGround())) {
                w.why = "there";
            } else if (target.goal() == null) {
                w.why = "gone";
            } else if (target.moved(goal)) {
                w.why = "moved";
            }
            if (w.why != null) {
                movement.stop();
                return;
            }
            CompletableFuture.delayedExecutor(CHASE_EVERY_MS, java.util.concurrent.TimeUnit.MILLISECONDS, executor)
                    .execute(look[0]);
        };
        CompletableFuture.delayedExecutor(CHASE_EVERY_MS, java.util.concurrent.TimeUnit.MILLISECONDS, executor)
                .execute(look[0]);
        return w;
    }

    private void follow(int id, Target target, CompletableFuture<NavResult> done, PathFinder.Way way,
                        List<CompletableFuture<Movement.MoveResult>> results, int i, int moves, int replans, double gap,
                        Set<String> banned, Watch watch) {
        if (i >= results.size()) {
            // Played to its end: there, or as near as was known - look again from here.
            leg(id, target, done, moves, way.complete() ? replans : replans + 1,
                    way.complete() ? Double.MAX_VALUE : gap, banned);
            return;
        }
        results.get(i).whenCompleteAsync((r, error) -> {
            if (!live(id)) {
                done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
                return;
            }
            if (urgency.holdsBack(id)) {
                // An urgent hold stopped the moves: the trip waits, and finds its way again after.
                movement.stop();
                if (!heldBack(id, done, moves, replans,
                        () -> leg(id, target, done, moves, replans, Double.MAX_VALUE, banned))) {
                    leg(id, target, done, moves, replans, Double.MAX_VALUE, banned);
                }
                return;
            }
            String why = watch == null ? null : watch.why;
            if (why != null && (r == null || !r.arrived())) {
                movement.stop();
                if (why.equals("there")) {
                    done.complete(new NavResult(NavResult.Status.ARRIVED, movement.position(), moves, replans));
                } else {
                    // Moved on, or gone: looked at again from here - a chase's own replan is not a try.
                    leg(id, target, done, moves, replans, Double.MAX_VALUE, banned);
                }
                return;
            }
            Step s = way.steps().get(i);
            if (error != null || r == null) {
                done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
            } else if (r.arrived()) {
                follow(id, target, done, way, results, i + 1, moves + 1, replans, gap, banned, watch);
            } else if (r.status() == Movement.MoveResult.Status.BLOCKED && replans < MAX_REPLANS) {
                movement.stop();
                log.info("navigation: {} onto ({}, {}, {}) went wrong - looking again", s.kind(), s.x(), s.y(), s.z());
                leg(id, target, done, moves, replans + 1, Double.MAX_VALUE, banned);
            } else {
                movement.stop();
                NavResult.Status status = r.status() == Movement.MoveResult.Status.BLOCKED
                        ? NavResult.Status.BLOCKED
                        : r.status() == Movement.MoveResult.Status.NOT_AVAILABLE
                            ? NavResult.Status.NOT_AVAILABLE : NavResult.Status.INTERRUPTED;
                done.complete(new NavResult(status, r.at(), moves, replans));
            }
        }, executor);
    }

    /** Up to the door, then open it, then look again from there - through it now that it is open. */
    private void throughDoor(int id, Target target, CompletableFuture<NavResult> done, PathFinder.Way way, int door,
                             int moves, int replans, Set<String> banned) {
        List<Step> before = way.steps().subList(0, door);
        Step through = way.steps().get(door);
        int[] from = door == 0 ? cellOf(movement.position())
                : new int[] {before.get(door - 1).x(), before.get(door - 1).y(), before.get(door - 1).z()};
        List<CompletableFuture<Movement.MoveResult>> rs = new ArrayList<>();
        for (Movement.Move m : moves(before)) {
            rs.add(movement.move(m));
        }
        CompletableFuture.allOf(rs.toArray(new CompletableFuture[0])).whenCompleteAsync((v, e) -> {
            if (!live(id)) {
                done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
                return;
            }
            boolean there = e == null && rs.stream().allMatch(f -> f.join().arrived());
            if (!there) {
                movement.stop();
                leg(id, target, done, moves, replans + 1, Double.MAX_VALUE, banned);
                return;
            }
            open(through.door()).whenCompleteAsync((opened, e2) -> {
                if (!Boolean.TRUE.equals(opened)) {
                    log.info("navigation: the door at {} did not open - going round", through.door());
                    banned.add(PathFinder.edge(from[0], from[1], from[2], through.x(), through.y(), through.z(),
                            through.kind()));
                }
                leg(id, target, done, moves + door, replans + 1, Double.MAX_VALUE, banned);
            }, executor);
        }, executor);
    }

    /**
     * Opens a door the way a player does: turns to it, uses it, and waits for the server to show it
     * open. Completes with whether it did.
     */
    private CompletableFuture<Boolean> open(BlockPos door) {
        if (interactions == null) {
            return CompletableFuture.completedFuture(false);
        }
        Executor soon = CompletableFuture.delayedExecutor(150, java.util.concurrent.TimeUnit.MILLISECONDS, executor);
        // Turned to the middle of the door at its lower block's top, the use a moment later, however
        // it went: then open once the server shows it so.
        return movement.face(new Vec3(door.x() + 0.5, door.y() + 1.0, door.z() + 0.5))
                .thenComposeAsync(turned -> interactions.useOnBlock(door), soon)
                .handle((outcome, error) -> null)
                .thenCompose(v -> world.awaitBlock(door, b -> movement.cell(door).doorState() == Movement.Door.OPEN,
                        Duration.ofMillis(1500)))
                .thenApply(java.util.Optional::isPresent)
                .exceptionally(error -> false);
    }

    /** How far the end of a way is from the goal - a way that only gets nearer has to get nearer each time. */
    private static double gap(PathFinder.Way way, int[] from, Goal goal) {
        int x = from[0];
        int y = from[1];
        int z = from[2];
        if (!way.steps().isEmpty()) {
            Step last = way.steps().get(way.steps().size() - 1);
            x = last.x();
            y = last.y();
            z = last.z();
        }
        double dx = x - goal.x();
        double dy = y - goal.y();
        double dz = z - goal.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The cell the feet are in: the floor's top lies within the cell's height. */
    public static int[] cellOf(Vec3 feet) {
        return new int[] {(int) Math.floor(feet.x()), (int) Math.floor(feet.y() + 1e-3), (int) Math.floor(feet.z())};
    }
}
