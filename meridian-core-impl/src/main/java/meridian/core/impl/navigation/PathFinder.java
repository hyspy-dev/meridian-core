package meridian.core.impl.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.Set;
import meridian.core.api.BlockPos;
import meridian.core.api.Goal;
import meridian.core.api.Movement.Gait;
import meridian.core.api.Movement.Kind;
import meridian.core.api.Movement;
import meridian.core.api.Navigator.Step;
import meridian.core.api.Vec3;

/**
 * A* over the places the body can be - standing on the ground, swimming in a fluid, holding on to
 * something climbable, in the air where it may fly - the edges being the body's moves: a walk (a
 * step up or down included), a jump up, a pull-up onto a ledge, a drop it survives unhurt (any
 * height into deep water), a running or sprinting jump over a gap, a swim, a climb, a flight. Costs
 * are seconds; the guess is the straight distance at the body's fastest.
 *
 * <p>The reach of each move is the body's own ({@link Movement#capabilities()}, worked out by its
 * physics). A way is still only a plan: the walker plays it on a copy of the body before playing it
 * for real, and a move that would not arrive is banned and the search run again.
 *
 * <p>Diagonal edges are walks and swims only, and only where both corners leave room. A search has
 * a budget of places and of time; out of budget, or out of the world core knows, it gives the way to
 * the place it saw nearest the goal, so the walker can go that far and look again.
 */
final class PathFinder {

    static final int MAX_NODES = 40_000;
    static final long BUDGET_NANOS = 1_500_000_000L;

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /**
     * The room a swimming body needs over the cell it swims in. Navigation's own: core's body is as
     * tall in a fluid as on land ({@link Movement.Capabilities#height}), crouched when it dives.
     */
    static final double SWIM_ROOM = 0.9;
    /**
     * The highest a swimmer gets onto a bank, over the cell it swims in - a jump out of the water.
     * Navigation's own, and short of the body's: core's body leaves the water as a whole jump from
     * a block under the surface.
     */
    static final double BANK_EXIT = 1.6;
    /** Above a step, a little room: the feet stand on a block's top, not a hair under it. */
    private static final double STEP_ROOM = 0.1;

    /** Where the body is at a place. */
    enum Mode { GROUND, WATER, CLIMB, AIR }

    /** A way: its moves, and whether it reaches the goal (or only gets nearer). */
    record Way(List<Step> steps, boolean complete) {}

    private record Node(int x, int y, int z, Mode mode, double feet, double g, double h, Node parent, Kind kind,
                        Gait gait, BlockPos door) {

        Node(int x, int y, int z, Mode mode, double feet, double g, double h, Node parent, Kind kind, Gait gait) {
            this(x, y, z, mode, feet, g, h, parent, kind, gait, null);
        }

        double f() {
            return g + h;
        }
    }

    private final Movement movement;
    /** Where the body stands and fits: the ground, the same for every player. */
    private final meridian.core.api.World ground;

    PathFinder(Movement movement, meridian.core.api.World ground) {
        this.movement = movement;
        this.ground = ground;
    }

    /**
     * The way from the body at {@code (sx, sy, sz)} with the feet at {@code feet} to {@code goal},
     * leaving out the moves in {@code banned} (from one cell to another, see {@link #edge}).
     */
    Way find(int sx, int sy, int sz, double feet, Goal goal, Set<String> banned) {
        Movement.Capabilities caps = movement.capabilities();
        World w = new World(movement, ground);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Movement.State now = movement.state();
        Mode startMode = now.swimming() ? Mode.WATER : now.climbing() ? Mode.CLIMB
                : now.flying() ? Mode.AIR : Mode.GROUND;
        Node start = new Node(sx, sy, sz, startMode, feet, 0, guess(sx, sy, sz, goal, caps), null, null, null);
        open.add(start);
        best.put(key(sx, sy, sz, startMode), 0.0);
        Node nearest = start;
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int expanded = 0;
        while (!open.isEmpty()) {
            Node n = open.poll();
            long k = key(n.x(), n.y(), n.z(), n.mode());
            if (!closed.add(k)) {
                continue;
            }
            if (goal.reachedBy(n.x(), n.y(), n.z()) && n.mode() != Mode.AIR && n.mode() != Mode.CLIMB) {
                return new Way(steps(n), true);
            }
            if (n.h() < nearest.h()) {
                nearest = n;
            }
            if (++expanded > MAX_NODES || System.nanoTime() > deadline) {
                break;
            }
            List<Node> next = new ArrayList<>();
            switch (n.mode()) {
                case GROUND -> {
                    ground(n, caps, w, goal, next);
                    leaps(n, caps, w, goal, next);
                    intoWater(n, caps, w, goal, next);
                    climbs(n, caps, w, goal, next);
                }
                case WATER -> swims(n, caps, w, goal, next);
                case CLIMB -> climbs(n, caps, w, goal, next);
                case AIR -> { }
            }
            if (caps.canFly()) {
                flights(n, caps, w, goal, next);
            }
            for (Node m : next) {
                if (banned.contains(edge(n.x(), n.y(), n.z(), m.x(), m.y(), m.z(), m.kind()))) {
                    continue;
                }
                long nk = key(m.x(), m.y(), m.z(), m.mode());
                if (closed.contains(nk)) {
                    continue;
                }
                Double known = best.get(nk);
                if (known == null || m.g() < known) {
                    best.put(nk, m.g());
                    open.add(m);
                }
            }
        }
        return new Way(steps(nearest), false);
    }

    /** The name of a move from one cell to another, for banning it. */
    static String edge(int x0, int y0, int z0, int x1, int y1, int z1, Kind kind) {
        return x0 + "," + y0 + "," + z0 + ">" + x1 + "," + y1 + "," + z1 + ":" + kind;
    }

    // ------------------------------------------------------------------
    // The ground
    // ------------------------------------------------------------------

    private void ground(Node n, Movement.Capabilities caps, World w, Goal goal, List<Node> out) {
        int up = (int) Math.ceil(Math.max(caps.mantleHeight(), caps.jumpHeight()));
        int down = (int) Math.floor(caps.safeDrop());
        for (int[] d : DIRS) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            for (int dy = up; dy >= -down; dy--) {
                int x = n.x() + d[0];
                int y = n.y() + dy;
                int z = n.z() + d[1];
                OptionalDouble stand = w.stand(x, y, z);
                if (stand.isEmpty()) {
                    // A shut door on the level: through it, once it is opened.
                    if (!diagonal && Math.abs(y - n.feet()) <= caps.stepHeight() + 1e-6) {
                        BlockPos door = w.closedDoor(x, y, z);
                        if (door != null && w.cell(x, y - 1, z).solid()) {
                            out.add(new Node(x, y, z, Mode.GROUND, y, n.g() + 1.0 / caps.runSpeed() + 1.0,
                                    guess(x, y, z, goal, caps), n, Kind.WALK, Gait.RUN, door));
                        }
                    }
                    continue;
                }
                double feet = stand.getAsDouble();
                double rise = feet - n.feet();
                Kind kind;
                double cost;
                double dist = diagonal ? Math.sqrt(2) : 1;
                if (Math.abs(rise) <= caps.stepHeight() + 1e-6) {
                    kind = Kind.WALK;
                    cost = dist / caps.runSpeed();
                } else if (rise > 0 && rise <= caps.jumpHeight() + 1e-6) {
                    kind = Kind.JUMP;
                    cost = (dist + rise) / caps.runSpeed();
                } else if (rise > 0 && rise <= caps.mantleHeight() + 1e-6) {
                    // By its length, not its time (a pull-up takes 1.5 s): a shorter way over a ledge
                    // is liked better than a longer one round it.
                    kind = Kind.MANTLE;
                    cost = (dist + rise) / caps.runSpeed();
                } else if (rise < 0 && -rise <= caps.safeDrop()) {
                    kind = Kind.DROP;
                    cost = 0.3 + caps.fallSeconds(-rise) + 0.3 * -rise / caps.safeDrop();
                } else {
                    continue;
                }
                if (diagonal) {
                    if (kind != Kind.WALK) {
                        continue;
                    }
                    double top = Math.max(feet, n.feet());
                    // Both corners leave room for the body: no cutting past a block.
                    if (!w.fits(n.x() + d[0] + 0.5, top, n.z() + 0.5, caps.height())
                            || !w.fits(n.x() + 0.5, top, n.z() + d[1] + 0.5, caps.height())) {
                        continue;
                    }
                }
                if (kind == Kind.JUMP || kind == Kind.MANTLE) {
                    // Room to rise where it stands.
                    if (!w.fits(n.x() + 0.5, n.feet(), n.z() + 0.5, caps.height() + rise)) {
                        continue;
                    }
                } else if (kind == Kind.DROP) {
                    // A clear fall over the landing, from the height it steps off at.
                    if (!w.fits(x + 0.5, feet, z + 0.5, caps.height() - rise)) {
                        continue;
                    }
                }
                out.add(new Node(x, y, z, Mode.GROUND, feet, n.g() + cost, guess(x, y, z, goal, caps), n, kind,
                        Gait.RUN));
            }
        }
    }

    /**
     * Running jumps over a gap along an axis: no ground under the cells between, room for the arc
     * over them, ground across - level, a block up or down - within what a running jump carries,
     * else what a sprinting one does.
     */
    private void leaps(Node n, Movement.Capabilities caps, World w, Goal goal, List<Node> out) {
        double far = caps.sprintLeap() > 0 ? caps.sprintLeap() : caps.runLeap();
        if (far < 1.5) {
            return;
        }
        int maxGap = (int) Math.floor(far);
        for (int i = 0; i < 4; i++) {
            int[] d = DIRS[i];
            for (int gap = 1; gap <= maxGap; gap++) {
                // Every cell crossed: open below at the feet' level, room for the body and the arc.
                int gx = n.x() + d[0] * gap;
                int gz = n.z() + d[1] * gap;
                if (!w.fits(gx + 0.5, n.feet(), gz + 0.5, caps.height() + 1)) {
                    break;
                }
                if (w.stand(gx, n.y(), gz).isPresent()) {
                    // Ground here: a walk, not a gap.
                    break;
                }
                int lx = n.x() + d[0] * (gap + 1);
                int lz = n.z() + d[1] * (gap + 1);
                for (int dy = 1; dy >= -2; dy--) {
                    OptionalDouble stand = w.stand(lx, n.y() + dy, lz);
                    if (stand.isEmpty()) {
                        continue;
                    }
                    double feet = stand.getAsDouble();
                    double rise = feet - n.feet();
                    // Gap and landing: the distance from the edge to the landing's near side.
                    double span = gap + (rise > 0 ? 0.6 : 0) - (rise < 0 ? 0.4 * -rise : 0);
                    Gait gait;
                    if (span <= caps.runLeap() - 0.2) {
                        gait = Gait.RUN;
                    } else if (span <= caps.sprintLeap() - 0.2) {
                        gait = Gait.SPRINT;
                    } else {
                        continue;
                    }
                    if (rise > 0 && !w.fits(lx + 0.5, n.feet(), lz + 0.5, caps.height() + rise + 0.5)) {
                        continue;
                    }
                    double speed = gait == Gait.SPRINT ? caps.sprintSpeed() : caps.runSpeed();
                    double cost = (gap + 1) / speed + 0.4;
                    out.add(new Node(lx, n.y() + dy, lz, Mode.GROUND, feet, n.g() + cost,
                            guess(lx, n.y() + dy, lz, goal, caps), n, Kind.LEAP, gait));
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Water
    // ------------------------------------------------------------------

    /** Off the ground into a fluid the body swims in: stepped or dropped into, any height into deep water. */
    private void intoWater(Node n, Movement.Capabilities caps, World w, Goal goal, List<Node> out) {
        int down = (int) Math.floor(caps.safeDrop()) * 3;
        for (int i = 0; i < 4; i++) {
            int[] d = DIRS[i];
            int x = n.x() + d[0];
            int z = n.z() + d[1];
            for (int dy = 0; dy >= -down; dy--) {
                int y = n.y() + dy;
                if (!w.fits(x + 0.5, y, z + 0.5, SWIM_ROOM)) {
                    break;
                }
                if (w.swim(x, y, z)) {
                    double fall = n.feet() - y;
                    // A long fall needs deep water to come down in.
                    if (fall > caps.safeDrop() && !(w.swim(x, y - 1, z) && w.swim(x, y - 2, z))) {
                        break;
                    }
                    double cost = 1 / caps.swimSpeed() + caps.fallSeconds(fall);
                    out.add(new Node(x, y, z, Mode.WATER, y, n.g() + cost, guess(x, y, z, goal, caps), n, Kind.SWIM,
                            Gait.RUN));
                    break;
                }
            }
        }
    }

    private void swims(Node n, Movement.Capabilities caps, World w, Goal goal, List<Node> out) {
        // Through the fluid: across (with diagonals), up and down.
        for (int[] d : DIRS) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            int x = n.x() + d[0];
            int z = n.z() + d[1];
            if (w.swim(x, n.y(), z) && w.fits(x + 0.5, n.y(), z + 0.5, SWIM_ROOM)) {
                if (diagonal && (!w.fits(n.x() + d[0] + 0.5, n.y(), n.z() + 0.5, SWIM_ROOM)
                        || !w.fits(n.x() + 0.5, n.y(), n.z() + d[1] + 0.5, SWIM_ROOM))) {
                    continue;
                }
                double cost = (diagonal ? Math.sqrt(2) : 1) / caps.swimSpeed();
                out.add(new Node(x, n.y(), z, Mode.WATER, n.y(), n.g() + cost, guess(x, n.y(), z, goal, caps), n,
                        Kind.SWIM, Gait.RUN));
            }
        }
        for (int dy : new int[] {1, -1}) {
            int y = n.y() + dy;
            if (w.swim(n.x(), y, n.z()) && w.fits(n.x() + 0.5, y, n.z() + 0.5, SWIM_ROOM)) {
                out.add(new Node(n.x(), y, n.z(), Mode.WATER, y, n.g() + 1 / 2.5, guess(n.x(), y, n.z(), goal, caps),
                        n, Kind.SWIM, Gait.RUN));
            }
        }
        // Out onto a bank: up to a jump out of the water.
        for (int i = 0; i < 4; i++) {
            int[] d = DIRS[i];
            int x = n.x() + d[0];
            int z = n.z() + d[1];
            for (int dy = 2; dy >= -1; dy--) {
                int y = n.y() + dy;
                OptionalDouble stand = w.stand(x, y, z);
                if (stand.isEmpty() || w.swim(x, y, z)) {
                    continue;
                }
                double feet = stand.getAsDouble();
                if (feet - n.feet() > BANK_EXIT) {
                    continue;
                }
                out.add(new Node(x, y, z, Mode.GROUND, feet, n.g() + 1.0, guess(x, y, z, goal, caps), n, Kind.SWIM,
                        Gait.RUN));
                break;
            }
        }
        // Standing in shallow water at its bottom.
        OptionalDouble here = w.stand(n.x(), n.y(), n.z());
        if (here.isPresent()) {
            out.add(new Node(n.x(), n.y(), n.z(), Mode.GROUND, here.getAsDouble(), n.g() + 0.2,
                    guess(n.x(), n.y(), n.z(), goal, caps), n, Kind.SWIM, Gait.RUN));
        }
    }

    // ------------------------------------------------------------------
    // Climbing
    // ------------------------------------------------------------------

    /** Onto, along and off something climbable: a ladder, a vine, a rope. */
    private void climbs(Node n, Movement.Capabilities caps, World w, Goal goal, List<Node> out) {
        double per = 1 / Math.max(0.5, caps.climbSpeed());
        if (n.mode() == Mode.GROUND) {
            // Onto it: in this cell or the next, at the feet or a block up.
            for (int i = -1; i < 4; i++) {
                int x = n.x() + (i < 0 ? 0 : DIRS[i][0]);
                int z = n.z() + (i < 0 ? 0 : DIRS[i][1]);
                for (int dy = 0; dy <= 1; dy++) {
                    int y = n.y() + dy;
                    if (i < 0 && dy == 0) {
                        continue;
                    }
                    if (w.climb(x, y, z) && w.fits(x + 0.5, y, z + 0.5, caps.height())) {
                        out.add(new Node(x, y, z, Mode.CLIMB, y, n.g() + per + 0.2, guess(x, y, z, goal, caps), n,
                                Kind.CLIMB, Gait.RUN));
                    }
                }
            }
            return;
        }
        // Along it, up and down.
        for (int dy : new int[] {1, -1}) {
            int y = n.y() + dy;
            if (w.climb(n.x(), y, n.z()) && w.fits(n.x() + 0.5, y, n.z() + 0.5, caps.height())) {
                out.add(new Node(n.x(), y, n.z(), Mode.CLIMB, y, n.g() + per, guess(n.x(), y, n.z(), goal, caps), n,
                        Kind.CLIMB, Gait.RUN));
            }
        }
        // Off it: onto ground beside it or at its foot, up to a step above (the body itself gets past
        // the top with an air step more: core's AIR_STEP_HEIGHT, 0.5).
        for (int i = -1; i < 4; i++) {
            int x = n.x() + (i < 0 ? 0 : DIRS[i][0]);
            int z = n.z() + (i < 0 ? 0 : DIRS[i][1]);
            for (int dy = 1; dy >= -1; dy--) {
                int y = n.y() + dy;
                OptionalDouble stand = w.stand(x, y, z);
                if (stand.isEmpty()) {
                    continue;
                }
                double feet = stand.getAsDouble();
                if (feet - n.feet() > caps.stepHeight() + STEP_ROOM) {
                    continue;
                }
                out.add(new Node(x, y, z, Mode.GROUND, feet, n.g() + per + 0.3, guess(x, y, z, goal, caps), n,
                        Kind.CLIMB, Gait.RUN));
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Flying
    // ------------------------------------------------------------------

    private void flights(Node n, Movement.Capabilities caps, World w, Goal goal, List<Node> out) {
        double speed = caps.flySpeed();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    int x = n.x() + dx;
                    int y = n.y() + dy;
                    int z = n.z() + dz;
                    if (!w.fits(x + 0.5, y, z + 0.5, caps.height())) {
                        continue;
                    }
                    if ((dx != 0 && dz != 0) && (!w.fits(n.x() + dx + 0.5, y, n.z() + 0.5, caps.height())
                            || !w.fits(n.x() + 0.5, y, n.z() + dz + 0.5, caps.height()))) {
                        continue;
                    }
                    double cost = Math.sqrt(dx * dx + dy * dy + dz * dz) / speed;
                    OptionalDouble stand = w.stand(x, y, z);
                    if (stand.isPresent() && dy <= 0) {
                        // Down onto ground: landed.
                        out.add(new Node(x, y, z, Mode.GROUND, stand.getAsDouble(), n.g() + cost + 0.2,
                                guess(x, y, z, goal, caps), n, Kind.FLY, Gait.RUN));
                    } else {
                        out.add(new Node(x, y, z, Mode.AIR, y, n.g() + cost, guess(x, y, z, goal, caps), n, Kind.FLY,
                                Gait.RUN));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /** The least time left: the straight distance to the goal's edge at the body's fastest. */
    private static double guess(int x, int y, int z, Goal goal, Movement.Capabilities caps) {
        double dx = x - goal.x();
        double dy = y - goal.y();
        double dz = z - goal.z();
        double d = Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - goal.radius());
        return d / Math.max(caps.sprintSpeed(), caps.flySpeed());
    }

    private static List<Step> steps(Node last) {
        List<Step> out = new ArrayList<>();
        for (Node n = last; n != null && n.parent() != null; n = n.parent()) {
            out.add(new Step(n.kind(), n.x(), n.y(), n.z(), n.feet(), n.gait(), n.door()));
        }
        Collections.reverse(out);
        return out;
    }

    private static long key(int x, int y, int z, Mode mode) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((long) (y & 0x3FF) << 2)
                | mode.ordinal();
    }

    /** What the search asks of the world, each answer kept for the search. */
    private static final class World {
        private final Movement movement;
        private final meridian.core.api.World ground;
        private final Map<Long, OptionalDouble> stands = new HashMap<>();
        private final Map<Long, Movement.Cell> cells = new HashMap<>();
        private final Map<String, Boolean> fits = new HashMap<>();

        World(Movement movement, meridian.core.api.World ground) {
            this.movement = movement;
            this.ground = ground;
        }

        OptionalDouble stand(int x, int y, int z) {
            return stands.computeIfAbsent(key(x, y, z, Mode.GROUND), k -> ground.standAt(new BlockPos(x, y, z)));
        }

        Movement.Cell cell(int x, int y, int z) {
            return cells.computeIfAbsent(key(x, y, z, Mode.GROUND), k -> movement.cell(new BlockPos(x, y, z)));
        }

        boolean swim(int x, int y, int z) {
            Movement.Cell c = cell(x, y, z);
            return c.swimmable() && !c.hurts();
        }

        boolean climb(int x, int y, int z) {
            return cell(x, y, z).climbable();
        }

        /** The shut door filling a cell (at it, or its upper half over it), or {@code null}. */
        BlockPos closedDoor(int x, int y, int z) {
            Movement.Cell c = cell(x, y, z);
            if (c.closedDoor()) {
                return c.door();
            }
            Movement.Cell up = cell(x, y + 1, z);
            return up.closedDoor() ? up.door() : null;
        }

        boolean fits(double x, double feet, double z, double height) {
            String k = x + "," + feet + "," + z + "," + height;
            return fits.computeIfAbsent(k, kk -> ground.fits(new Vec3(x, feet, z), height));
        }
    }
}
