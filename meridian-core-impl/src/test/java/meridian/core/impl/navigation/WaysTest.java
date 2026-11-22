package meridian.core.impl.navigation;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import meridian.core.api.BlockPos;

/**
 * The way made where no walk goes, on worlds in memory. The owner's night of 21.11: the miner had dug
 * the pit's floor down from under the foot of the staircase out, the chests stood over the far corner,
 * and the bot - with no walk - made for the corner nearest the chests instead of the few steps up to
 * the staircase. A way made is the JOIN to the nearest place a walk goes on from, not a way to the goal.
 */
public class WaysTest {

    /** Stone where it was put, air elsewhere; known within a box. A walk here: a step up, a drop of three. */
    static class World implements Ways.Grid {
        final Set<BlockPos> stone = new HashSet<>();
        final Set<BlockPos> kept = new HashSet<>();
        /** The job's selection: a block outside it costs a way more. {@code null}: no selection. */
        Predicate<BlockPos> selection;
        final int min;
        final int max;

        World(int min, int max) {
            this.min = min;
            this.max = max;
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        stone.add(new BlockPos(x, y, z));
                    }
                }
            }
        }

        void clear(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        stone.remove(new BlockPos(x, y, z));
                    }
                }
            }
        }

        @Override public boolean known(BlockPos p) {
            return p.x() >= min && p.x() <= max && p.y() >= min && p.y() <= max && p.z() >= min && p.z() <= max;
        }
        @Override public boolean air(BlockPos p) { return !stone.contains(p); }
        @Override public boolean passable(BlockPos p) { return !kept.contains(p); }
        @Override public boolean floor(BlockPos p) { return stone.contains(p); }
        @Override public boolean buildable(BlockPos p) { return known(p) && air(p); }
        @Override public boolean falls(BlockPos p) { return false; }
        @Override public double toll(BlockPos p) { return selection != null && !selection.test(p) ? 3.0 : 0; }

        boolean stands(BlockPos p) {
            return known(p) && air(p) && air(up(p, 1)) && floor(up(p, -1));
        }

        /** The blocks a walk from {@code from} to {@code goal} stands on; {@code null} when none goes there. */
        Set<BlockPos> floors(BlockPos from, BlockPos goal) {
            Map<BlockPos, BlockPos> came = new HashMap<>();
            if (!walk(from, came).containsKey(goal)) {
                return null;
            }
            Set<BlockPos> out = new HashSet<>();
            for (BlockPos p = goal; p != null; p = came.get(p)) {
                out.add(up(p, -1));
            }
            return out;
        }

        /** Every place a walk from {@code from} gets to, with the steps to it. */
        Map<BlockPos, Double> walk(BlockPos from) {
            return walk(from, new HashMap<>());
        }

        Map<BlockPos, Double> walk(BlockPos from, Map<BlockPos, BlockPos> came) {
            Map<BlockPos, Double> out = new HashMap<>();
            ArrayDeque<BlockPos> todo = new ArrayDeque<>();
            out.put(from, 0.0);
            todo.add(from);
            while (!todo.isEmpty()) {
                BlockPos a = todo.poll();
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    for (int dy = 1; dy >= -3; dy--) {
                        BlockPos to = new BlockPos(a.x() + d[0], a.y() + dy, a.z() + d[1]);
                        if (!stands(to) || (dy == 1 && !air(up(a, 2)))) {
                            continue;
                        }
                        boolean clearColumn = true;     // a drop: the column over the landing is air
                        for (int y = to.y() + 1; y <= a.y() + 1; y++) {
                            clearColumn &= air(new BlockPos(to.x(), y, to.z()));
                        }
                        if (clearColumn && out.putIfAbsent(to, out.get(a) + 1) == null) {
                            came.put(to, a);
                            todo.add(to);
                        }
                        break;
                    }
                }
            }
            return out;
        }
    }

    static BlockPos up(BlockPos p, int dy) {
        return new BlockPos(p.x(), p.y() + dy, p.z());
    }

    /** What the way made came to, played on the world: its blocks broken and put, each step stood on. */
    record Played(Ways.Made way, int broken, int placed, int pillars, boolean walksOn) {}

    static Played make(World w, BlockPos body, BlockPos goal, Predicate<BlockPos> arrived, int fillers) {
        Map<BlockPos, Double> sources = w.walk(body);
        check(!sources.containsKey(goal), "a walk goes there already");
        Ways.Made way = Ways.connect(w, sources, arrived, place -> {
            Set<BlockPos> floors = w.floors(place, goal);
            return floors != null ? new Ways.On(true, Map.of(), floors) : new Ways.On(false, w.walk(place), Set.of());
        }, fillers, 3, 400_000);
        if (way == null) {
            return null;
        }
        check(sources.containsKey(way.from()), "starts where the body does not walk to: " + way.from());
        int broken = 0, placed = 0, pillars = 0;
        for (Ways.Step st : way.steps()) {
            for (BlockPos b : st.breaks()) {
                check(!w.kept.contains(b), "broke what was to be kept: " + b);
                if (w.stone.remove(b)) {
                    broken++;
                }
            }
            if (st.place() != null) {
                check(w.air(st.place()), "put a block where one was: " + st);
                w.stone.add(st.place());
                placed++;
                pillars += st.kind() == Ways.Kind.PILLAR ? 1 : 0;
            }
            check(w.stands(st.to()), "cannot stand at " + st.to() + " after " + st);
        }
        return new Played(way, broken, placed, pillars, w.walk(way.to()).containsKey(goal));
    }

    /** The night's pit: the staircase out starts six blocks up the far wall; the goal is over the near corner. */
    public void testAFewStepsToTheWayOutNotAStaircaseToTheGoal() {
        World w = new World(-5, 70);
        w.fill(-5, 0, -5, 70, 40, 15);
        w.clear(0, 11, 0, 20, 40, 10);                  // the pit, its floor's feet at 11
        for (int i = 0; i < 24; i++) {
            w.clear(21 + i, 17 + i, 5, 21 + i, 19 + i, 5);   // the owner's staircase, its foot at (21, 17, 5)
        }
        BlockPos goal = new BlockPos(-3, 41, 5);        // on the surface, over the near (west) side
        Played p = make(w, new BlockPos(2, 11, 5), goal, a -> a.equals(goal), 0);
        check(p != null, "no way made");
        check(!p.way().arrives() && p.walksOn(), "it does not end where a walk goes on to the goal: " + p.way());
        check(p.way().from().x() >= 14, "it starts on the near side, far from the way out: " + p.way().from());
        check(p.way().steps().size() <= 8 && p.broken() <= 24,
                "more than a few steps to the staircase: " + p.way().steps().size() + " step(s), " + p.broken() + " block(s)");
    }

    /** In the air on a single block, with no wall anywhere: up only by a pillar; with nothing to put down, no way. */
    public void testUpInTheOpenByAPillar() {
        World w = new World(-10, 40);
        w.fill(0, 0, 0, 0, 0, 0);
        BlockPos goal = new BlockPos(0, 20, 0);
        Played p = make(w, new BlockPos(0, 1, 0), goal, a -> a.equals(goal), 30);
        check(p != null && p.way().arrives() && p.pillars() == 19, "not up by a pillar of 19: " + p);
        World bare = new World(-10, 40);
        bare.fill(0, 0, 0, 0, 0, 0);
        check(make(bare, new BlockPos(0, 1, 0), goal, a -> a.equals(goal), 0) == null, "up into the air with nothing to put down");
    }

    /** Over a chasm with no floor within a safe drop: a bridge to the far side, where the walk goes on. */
    public void testOverAChasmByABridge() {
        World w = new World(-10, 40);
        w.fill(-5, 10, 0, 0, 10, 0);
        w.fill(6, 10, 0, 10, 10, 0);
        BlockPos goal = new BlockPos(9, 11, 0);
        Played p = make(w, new BlockPos(-3, 11, 0), goal, a -> a.equals(goal), 10);
        check(p != null && p.placed() == 5 && p.walksOn(), "not over by a bridge of 5: " + p);
    }

    /** Down from a height with nothing to cut steps in: steps put down, block by block. */
    public void testDownByStepsPutDown() {
        World w = new World(-20, 40);
        w.fill(-20, 0, -20, 20, 0, 20);                 // the ground
        w.fill(0, 1, 0, 0, 12, 0);                      // a tower, not to be broken
        for (int y = 1; y <= 12; y++) {
            w.kept.add(new BlockPos(0, y, 0));
        }
        BlockPos goal = new BlockPos(10, 1, 0);
        Played p = make(w, new BlockPos(0, 13, 0), goal, a -> a.equals(goal), 40);
        check(p != null && p.walksOn() && p.broken() == 0 && p.placed() > 0, "not down by steps put down: " + p);
    }

    /**
     * A block at the bottom of a pit with sheer walls, the selection that one block, the body on the
     * rim: steps are put down inside the pit when there is something to put; with nothing, a staircase
     * is cut in the rock beside it - outside the selection, as there is no other way down.
     */
    public void testIntoAPitOutsideTheSelectionOnlyWhenNothingElseGoes() {
        BlockPos goal = new BlockPos(0, 2, 0);
        for (int fillers : new int[] {40, 0}) {
            World w = new World(-10, 40);
            w.fill(-3, 1, -3, 3, 12, 3);
            w.clear(-1, 2, -1, 1, 12, 1);               // the pit: 3x3, eleven deep
            w.selection = p -> p.equals(new BlockPos(0, 1, 0));
            Played p = make(w, new BlockPos(3, 13, 3), goal, a -> a.equals(goal), fillers);
            check(p != null && (p.way().arrives() || p.walksOn()), "no way down with " + fillers + " to put: " + p);
            if (fillers > 0) {
                // (the rim's own edge may go for the first step: the search keeps one best way per place)
                check(p.broken() <= 2 && p.placed() >= 8,
                        "a staircase cut outside the selection with steps to put down: " + p.broken() + " broken, " + p.placed() + " put");
            } else {
                check(p.broken() > 0 && p.placed() == 0, "not a staircase cut down: " + p);
            }
        }
    }

    /**
     * A place in the sky over open ground, with too little to build up to it: no way - and said without
     * asking a walk on from every step of the ground around (each of those is a search of the world; the
     * stand's bot, dead on a ledge in the air, looked for the way back for ten minutes and more).
     */
    public void testNoWayIntoTheSkyIsSaidWithoutWalkingTheWorld() {
        World w = new World(-60, 60);
        w.fill(-60, 0, -60, 60, 0, 60);                 // open ground, as far as is known
        BlockPos goal = new BlockPos(0, 40, 0);
        // The walk's own search stopped a little way out: the ground goes on past what it gave.
        Map<BlockPos, Double> sources = new HashMap<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                sources.put(new BlockPos(x, 1, z), (double) Math.abs(x) + Math.abs(z));
            }
        }
        int[] asked = {0};
        Ways.Made way = Ways.connect(w, sources, a -> a.equals(goal), place -> {
            asked[0]++;
            return new Ways.On(false, w.walk(place), Set.of());
        }, 10, 3, 60_000);
        check(way == null, "a way into the sky with ten blocks: " + way);
        check(asked[0] <= Ways.WALKS, "asked a walk on from " + asked[0] + " place(s)");
    }

    /**
     * Out of a shaft sixty deep with sheer walls and nothing to build with: a staircase cut all the way
     * up - found within a small count of places, looked for towards the goal (in every direction alike
     * the rock within sixty steps is past any count).
     */
    public void testAStaircaseSixtyUpIsFoundTowardsTheGoal() {
        World w = new World(-40, 100);
        w.fill(-40, 0, -40, 40, 61, 40);
        w.clear(0, 1, 0, 2, 61, 2);                     // the shaft: 3x3, its floor's feet at 1
        BlockPos goal = new BlockPos(20, 62, 1);        // on the surface, twenty to the east
        Map<BlockPos, Double> sources = w.walk(new BlockPos(1, 1, 1));
        Ways.Made way = Ways.connect(w, sources, a -> a.equals(goal), place -> {
            Set<BlockPos> floors = w.floors(place, goal);
            return floors != null ? new Ways.On(true, Map.of(), floors) : new Ways.On(false, w.walk(place), Set.of());
        }, 0, 3, 30_000, goal);
        check(way != null, "no staircase out of the shaft within 30 000 places");
        int broken = 0;
        for (Ways.Step st : way.steps()) {
            for (BlockPos c : st.breaks()) {
                broken += w.stone.remove(c) ? 1 : 0;
            }
            check(w.stands(st.to()), "cannot stand at " + st.to());
        }
        check(w.walk(way.to()).containsKey(goal), "it does not come out where a walk goes on to the goal");
        check(broken < 400, "broke " + broken + " blocks for sixty steps");
    }

    /** Shut in with nothing that may be broken and nothing to build with: no way, said at once. */
    public void testNoWayWhenNothingBreaks() {
        World w = new World(-5, 5) {
            @Override public boolean passable(BlockPos p) { return air(p); }
        };
        w.fill(-5, -5, -5, 5, 5, 5);
        w.clear(0, 0, 0, 0, 1, 0);
        w.clear(3, 0, 0, 3, 1, 0);
        BlockPos goal = new BlockPos(3, 0, 0);
        check(make(w, new BlockPos(0, 0, 0), goal, a -> a.equals(goal), 0) == null, "a way through what does not break");
    }

    /** A cave on the way that leads nowhere: walked through, and the way made on from it. */
    public void testThroughADeadEndAndOn() {
        World w = new World(-5, 40);
        w.fill(-5, 0, -5, 40, 10, 5);
        w.clear(0, 1, 0, 2, 2, 0);                      // where the body is shut in
        w.clear(5, 1, 0, 14, 2, 0);                     // a cave: ten along, no way out
        w.clear(17, 1, 0, 30, 2, 0);                    // the hall the goal is in
        BlockPos goal = new BlockPos(30, 1, 0);
        Played p = make(w, new BlockPos(0, 1, 0), goal, a -> a.equals(goal), 0);
        check(p != null && p.walksOn(), "not through to the hall: " + p);
        check(p.way().steps().stream().anyMatch(st -> st.kind() == Ways.Kind.WALK), "the cave was not walked through");
        check(p.broken() <= 8, "dug " + p.broken() + " blocks: the cave was dug round, not walked through");
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
