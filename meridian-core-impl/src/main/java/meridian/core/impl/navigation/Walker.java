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
    /** The trip under way; a new one, or a cancel, moves it on. */
    private final AtomicInteger trip = new AtomicInteger();
    /** The trip's hold on a game's body, given back when it ends; {@code null} for none. */
    private volatile Movement.Wheel took;
    /** The player's stamina now; {@code NaN} when it is not known. */
    private final java.util.function.DoubleSupplier stamina;

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

    @Override
    public void cancel() {
        trip.incrementAndGet();
        movement.stop();
        giveBack();
    }

    @Override
    public CompletableFuture<NavResult> goTo(Goal goal) {
        int id = trip.incrementAndGet();
        movement.stop();
        CompletableFuture<NavResult> done = new CompletableFuture<>();
        // A game's body is held for the trip - a hold of its own, so whoever else holds it keeps it.
        // The trip before's hold goes once this one's is in hand, so the body does not go back between.
        Movement.Wheel wheel = movement.take().orElse(null);
        Movement.Wheel before = took;
        took = wheel;
        if (before != null) {
            before.release();
        }
        if (wheel == null) {
            done.complete(new NavResult(NavResult.Status.NOT_AVAILABLE, movement.position(), 0, 0));
            return done;
        }
        // The game takes the body back (a key, the server's teleport, a death): the trip is over.
        wheel.released().thenRun(() -> trip.compareAndSet(id, id + 1));
        done.whenComplete((r, e) -> {
            wheel.release();
            if (took == wheel) {
                took = null;
            }
        });
        executor.execute(() -> leg(id, goal, done, 0, 0, Double.MAX_VALUE, new HashSet<>()));
        return done;
    }

    /** Gives back the trip's hold on the body. */
    private void giveBack() {
        Movement.Wheel w = took;
        took = null;
        if (w != null) {
            w.release();
        }
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

    /** One leg: find a way from here, check it, then play it. */
    private void leg(int id, Goal goal, CompletableFuture<NavResult> done, int moves, int replans, double lastGap,
                     Set<String> banned) {
        if (trip.get() != id) {
            done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
            return;
        }
        Vec3 p = movement.position();
        if (p == null) {
            done.complete(new NavResult(NavResult.Status.NOT_AVAILABLE, null, moves, replans));
            return;
        }
        int[] c = cellOf(p);
        if (goal.reachedBy(c[0], c[1], c[2]) && movement.onGround()) {
            done.complete(new NavResult(NavResult.Status.ARRIVED, p, moves, replans));
            return;
        }
        PathFinder.Way way = checked(c, p, goal, banned);
        if (trip.get() != id) {
            // The body went back to the player while the way was looked for.
            done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
            return;
        }
        double gap = gap(way, c, goal);
        if (way.steps().isEmpty() || (!way.complete() && gap >= lastGap)) {
            log.info("navigation: no way from {} to {} ({} move(s), {} replan(s))", p, goal, moves, replans);
            done.complete(new NavResult(NavResult.Status.NO_PATH, p, moves, replans));
            return;
        }
        log.info("navigation: {} move(s) towards {}{}", way.steps().size(), goal, way.complete() ? "" : " (as far as known)");
        play(id, goal, done, way, moves, replans, gap, banned);
    }

    /** All the way's moves at once; the first that goes wrong stops the rest, and the way is looked for again. */
    private void play(int id, Goal goal, CompletableFuture<NavResult> done, PathFinder.Way way, int moves, int replans,
                      double gap, Set<String> banned) {
        int door = untilDoor(way.steps());
        if (door < way.steps().size()) {
            throughDoor(id, goal, done, way, door, moves, replans, banned);
            return;
        }
        List<Step> steps = way.steps();
        List<Movement.Move> ms = moves(steps);
        List<CompletableFuture<Movement.MoveResult>> results = new ArrayList<>(ms.size());
        for (Movement.Move m : ms) {
            results.add(movement.move(m));
        }
        follow(id, goal, done, way, results, 0, moves, replans, gap, banned);
    }

    private void follow(int id, Goal goal, CompletableFuture<NavResult> done, PathFinder.Way way,
                        List<CompletableFuture<Movement.MoveResult>> results, int i, int moves, int replans, double gap,
                        Set<String> banned) {
        if (i >= results.size()) {
            // Played to its end: there, or as near as was known - look again from here.
            leg(id, goal, done, moves, way.complete() ? replans : replans + 1, way.complete() ? Double.MAX_VALUE : gap,
                    banned);
            return;
        }
        results.get(i).whenCompleteAsync((r, error) -> {
            if (trip.get() != id) {
                done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
                return;
            }
            Step s = way.steps().get(i);
            if (error != null || r == null) {
                done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
            } else if (r.arrived()) {
                follow(id, goal, done, way, results, i + 1, moves + 1, replans, gap, banned);
            } else if (r.status() == Movement.MoveResult.Status.BLOCKED && replans < MAX_REPLANS) {
                movement.stop();
                log.info("navigation: {} onto ({}, {}, {}) went wrong - looking again", s.kind(), s.x(), s.y(), s.z());
                leg(id, goal, done, moves, replans + 1, Double.MAX_VALUE, banned);
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
    private void throughDoor(int id, Goal goal, CompletableFuture<NavResult> done, PathFinder.Way way, int door,
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
            if (trip.get() != id) {
                done.complete(new NavResult(NavResult.Status.INTERRUPTED, movement.position(), moves, replans));
                return;
            }
            boolean there = e == null && rs.stream().allMatch(f -> f.join().arrived());
            if (!there) {
                movement.stop();
                leg(id, goal, done, moves, replans + 1, Double.MAX_VALUE, banned);
                return;
            }
            open(through.door()).whenCompleteAsync((opened, e2) -> {
                if (!Boolean.TRUE.equals(opened)) {
                    log.info("navigation: the door at {} did not open - going round", through.door());
                    banned.add(PathFinder.edge(from[0], from[1], from[2], through.x(), through.y(), through.z(),
                            through.kind()));
                }
                leg(id, goal, done, moves + door, replans + 1, Double.MAX_VALUE, banned);
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
