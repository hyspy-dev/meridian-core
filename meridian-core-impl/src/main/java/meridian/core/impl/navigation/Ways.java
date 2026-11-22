package meridian.core.impl.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import meridian.core.api.BlockPos;

/**
 * The way made where no walk goes, as a player makes one: dug where rock is in the way - a tunnel, a
 * staircase up a pit's wall or down into the ground - built where there is nothing: a block put under
 * the feet in a jump (a pillar) or in front of them (a bridge), dropped down where the fall does no harm.
 *
 * <p>It is the join between two walks, and as short as it can be: it starts from any place the body
 * walks to and ends at the nearest place a walk goes on from to the goal - not at the goal. A pit
 * whose way out starts seven blocks up its far wall is left by seven steps cut to that way, not by
 * a staircase of sixty to the chest over it.
 *
 * <p>The search spreads from every place the body walks to at once, each at the cost of walking
 * there, by the cost of what is dug and put down (Dijkstra: the cheapest join first). A place it comes
 * out at on open ground is asked what a walk from there gets to ({@link Walks}): the goal - the join is
 * found; or only more places - those are walked to and the search spreads on from them as well.
 */
final class Ways {

    /** What the way sees of the world. */
    interface Grid {

        /** The world has shown the cell. */
        boolean known(BlockPos p);

        /** Nothing in the cell: walked through as it is. */
        boolean air(BlockPos p);

        /**
         * The body may be in the cell: air, or a block that may be broken and the hotbar breaks - with
         * no fluid in it, nothing that hurts, no lava beside it.
         */
        boolean passable(BlockPos p);

        /** The cell holds up the body standing on it. */
        boolean floor(BlockPos p);

        /** A block may be put into the cell (it is air, and nothing forbids building there). */
        boolean buildable(BlockPos p);

        /** Sand or gravel: it comes down into what is broken under it. */
        boolean falls(BlockPos p);

        /**
         * What breaking the block costs over {@link #PER_BLOCK}: more for one the job was not asked to
         * dig (outside its selection), so the way keeps to the selection, or is built, where that is
         * not much longer - and goes through the rock outside where it is.
         */
        default double toll(BlockPos p) {
            return 0;
        }
    }

    /** What a walk from a place on open ground gets to. */
    interface Walks {

        On from(BlockPos place);
    }

    /**
     * What a walk from a place gets to.
     *
     * @param reaches the walk reaches the goal
     * @param places  when it does not: every place it gets to, each with the cost of walking there
     * @param floors  when it does: the blocks that walk stands on, the one under the place itself too -
     *                a way made to the place must not have broken one of them, nor built over one
     */
    record On(boolean reaches, Map<BlockPos, Double> places, Set<BlockPos> floors) {
    }

    /** How a step is made. */
    enum Kind {
        /** One block along, up or down: walked to after its blocks are broken. */
        MOVE,
        /** A block put in front, at the feet's level less one, then walked onto. */
        BRIDGE,
        /** A block put under the feet in a jump: up one, in place. */
        PILLAR,
        /** A walk the navigator makes, over open ground the way came out on. */
        WALK
    }

    /** One step: what to break, what to put down ({@code null}: nothing), and where the body stands after. */
    record Step(Kind kind, BlockPos to, List<BlockPos> breaks, BlockPos place) {
    }

    /**
     * A way made.
     *
     * @param from    the place it starts at: one the body walks to
     * @param steps   its steps, in order
     * @param to      where it ends
     * @param arrives it ends at the goal itself; else at a place a walk goes on from to the goal
     * @param cost    what it costs, the walk to {@code from} counted
     */
    record Made(BlockPos from, List<Step> steps, BlockPos to, boolean arrives, double cost) {
    }

    /** What a move costs; a block broken costs this much more. */
    static final double PER_BLOCK = 1.5;
    /** ... and sand or gravel over what is broken, which would come down into it. */
    static final double FALLING = 6.0;
    /** A block put in front to walk on. */
    static final double BRIDGE = 4.0;
    /**
     * A block put under the feet: dearer than a step cut into a wall (a move and three blocks), so a
     * pit is left by a staircase - which stays, a way back down and up again - where a wall is near.
     */
    static final double PILLAR = 6.0;
    /** A block fallen, coming down. */
    static final double PER_FALL = 0.5;

    private record Node(BlockPos at, double g, int placed, Node parent, Step step) {
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * The most places one search asks a walk on from ({@link Walks#from} - each a search of its own,
     * seconds on open ground): the openings a way comes out on - a cave, the far side of a wall. Past
     * that the search goes on by its own steps alone.
     */
    static final int WALKS = 24;

    private Ways() {
    }

    /**
     * The cheapest way made from one of {@code sources} to a place {@code arrived} takes or a walk
     * goes on from to the goal.
     *
     * @param sources  the places the body walks to, each with the cost of walking there
     * @param fillers  blocks at hand to put down - a pillar's, a bridge's
     * @param safeDrop the most blocks the body may fall with no harm
     * @param nodes    the most places looked at
     * @return the way; {@code null} when there is none within {@code nodes} places - shut in by what
     *         may not be broken, or by the world not shown
     */
    static Made connect(Grid world, Map<BlockPos, Double> sources, Predicate<BlockPos> arrived, Walks walks,
                        int fillers, int safeDrop, int nodes) {
        return connect(world, sources, arrived, walks, fillers, safeDrop, nodes, null);
    }

    /**
     * What a step towards the goal is taken to cost when the way is looked for towards it: a little
     * over a step cut in rock (a move and three blocks, 5.5) - so the place nearest the goal is the one
     * gone on from, and one staircase is cut, not every staircase there could be weighed.
     */
    static final double TOWARDS = 6.0;

    /**
     * {@link #connect(Grid, Map, Predicate, Walks, int, int, int)} in two goes, each with half of
     * {@code nodes}. First the cheapest way wherever it lies - a join a few steps from where the body
     * walks, though it lead away from the goal (the owner's passage out of the pit lay on its far
     * side). Rock can be cut in every direction alike, though, and that search looks at the whole ball
     * of it: a staircase forty blocks up is past any count of places. So, with nothing found, a way
     * straight towards {@code toward} - the goal's cell: of two places as dear to get to, the one
     * nearer the goal is gone on from ({@link #TOWARDS}).
     */
    static Made connect(Grid world, Map<BlockPos, Double> sources, Predicate<BlockPos> arrived, Walks walks,
                        int fillers, int safeDrop, int nodes, BlockPos toward) {
        if (toward == null) {
            return search(world, sources, arrived, walks, fillers, safeDrop, nodes, null);
        }
        Made near = search(world, sources, arrived, walks, fillers, safeDrop, nodes / 2, null);
        return near != null ? near : search(world, sources, arrived, walks, fillers, safeDrop, nodes / 2, toward);
    }

    private static Made search(Grid world, Map<BlockPos, Double> sources, Predicate<BlockPos> arrived, Walks walks,
                               int fillers, int safeDrop, int nodes, BlockPos toward) {
        PriorityQueue<Node> open = new PriorityQueue<>(
                (a, b) -> Double.compare(a.g() + left(a.at(), toward), b.g() + left(b.at(), toward)));
        Map<BlockPos, Double> best = new HashMap<>();
        // Places a walk gets to with no walk on to the goal: the sources, and what the way came out on.
        Set<BlockPos> walked = new HashSet<>(sources.keySet());
        for (Map.Entry<BlockPos, Double> s : sources.entrySet()) {
            open.add(new Node(s.getKey(), s.getValue(), 0, null, null));
            best.put(s.getKey(), s.getValue());
        }
        java.util.function.BooleanSupplier wanted = PathFinder.WANTED.get();
        int seen = 0;
        int asked = 0;
        while (!open.isEmpty() && seen++ < nodes) {
            if ((seen & 1023) == 0 && wanted != null && !wanted.getAsBoolean()) {
                return null;        // whoever asked for the way no longer wants it
            }
            Node n = open.poll();
            if (n.g() > best.getOrDefault(n.at(), Double.MAX_VALUE)) {
                continue;
            }
            if (n.step() != null && n.step().kind() != Kind.WALK) {
                if (arrived.test(n.at())) {
                    return made(n, true);
                }
                if (!walked.contains(n.at()) && open(world, n.at())) {
                    if (n.step().kind() == Kind.MOVE && n.step().breaks().isEmpty() && walked.contains(n.parent().at())) {
                        // A step on from walked ground with nothing broken and nothing put down: a walk
                        // still - the same ground, past where the walk's own search stopped looking. No
                        // walk goes on from that ground, or the body would be walking it: nothing to ask.
                        // (Asked, each such place is a search of the open world, and there is no end of them.)
                        walked.add(n.at());
                    } else if (asked++ < WALKS) {
                    // Out on open ground: where does a walk from here get to?
                    On on = walks.from(n.at());
                    if (on.reaches()) {
                        if (spoils(n, on.floors())) {
                            continue;       // it broke what the walk on stands on, or built in its way: not this way in
                        }
                        return made(n, false);
                    }
                    walked.add(n.at());
                    for (Map.Entry<BlockPos, Double> w : on.places().entrySet()) {
                        // Walked to from here, and the search spreads on from there as well.
                        walked.add(w.getKey());
                        double g = n.g() + w.getValue();
                        if (g < best.getOrDefault(w.getKey(), Double.MAX_VALUE)) {
                            best.put(w.getKey(), g);
                            open.add(new Node(w.getKey(), g, n.placed(), n,
                                    new Step(Kind.WALK, w.getKey(), List.of(), null)));
                        }
                    }
                    }
                }
            }
            for (Node next : moves(world, n, fillers, safeDrop)) {
                if (next.g() < best.getOrDefault(next.at(), Double.MAX_VALUE)) {
                    best.put(next.at(), next.g());
                    open.add(next);
                }
            }
        }
        return null;
    }

    /**
     * What the way on from {@code at} to {@code toward} is taken to cost: a move gets one block nearer
     * along the ground, and one up at the most (down costs next to nothing - a fall), each at
     * {@link #TOWARDS}. {@code 0} with nowhere to look towards.
     */
    private static double left(BlockPos at, BlockPos toward) {
        if (toward == null) {
            return 0;
        }
        int flat = Math.abs(toward.x() - at.x()) + Math.abs(toward.z() - at.z());
        return TOWARDS * Math.max(flat, toward.y() - at.y());
    }

    /**
     * Whether the way to {@code n} spoils the walk on from it, which stands on {@code floors}: it broke
     * one of them, or put a block where that walk's body goes - the feet or the head over one (the
     * goal's own cell among them: a step down put there would stand in it).
     */
    private static boolean spoils(Node n, Set<BlockPos> floors) {
        for (Node k = n; k.parent() != null; k = k.parent()) {
            for (BlockPos b : k.step().breaks()) {
                if (floors.contains(b)) {
                    return true;
                }
            }
            BlockPos put = k.step().place();
            if (put != null && (floors.contains(new BlockPos(put.x(), put.y() - 1, put.z()))
                    || floors.contains(new BlockPos(put.x(), put.y() - 2, put.z())))) {
                return true;
            }
        }
        return false;
    }

    /** The place stands as the world is: floor under it, air for the feet and the head. */
    private static boolean open(Grid world, BlockPos at) {
        BlockPos head = new BlockPos(at.x(), at.y() + 1, at.z());
        BlockPos under = new BlockPos(at.x(), at.y() - 1, at.z());
        return world.air(at) && world.air(head) && world.known(under) && world.floor(under);
    }

    private static Made made(Node end, boolean arrives) {
        List<Step> out = new ArrayList<>();
        Node k = end;
        for (; k.parent() != null; k = k.parent()) {
            out.add(0, k.step());
        }
        return new Made(k.at(), out, end.at(), arrives, end.g());
    }

    /** {@code world} with the blocks the way to {@code n} broke taken out and the ones it put down in. */
    private static Grid afterPath(Grid world, Node n) {
        Set<BlockPos> broken = new HashSet<>();
        Set<BlockPos> put = new HashSet<>();
        for (Node k = n; k.parent() != null; k = k.parent()) {
            broken.addAll(k.step().breaks());
            if (k.step().place() != null) {
                put.add(k.step().place());
            }
        }
        if (broken.isEmpty() && put.isEmpty()) {
            return world;
        }
        broken.removeAll(put);     // put down after it was broken: there
        return new Grid() {
            @Override public boolean known(BlockPos p) { return world.known(p); }
            @Override public boolean air(BlockPos p) { return !put.contains(p) && (broken.contains(p) || world.air(p)); }
            @Override public boolean passable(BlockPos p) { return put.contains(p) || broken.contains(p) || world.passable(p); }
            @Override public boolean floor(BlockPos p) { return put.contains(p) || (!broken.contains(p) && world.floor(p)); }
            @Override public boolean buildable(BlockPos p) { return !put.contains(p) && (broken.contains(p) || world.buildable(p)); }
            @Override public boolean falls(BlockPos p) { return !broken.contains(p) && world.falls(p); }
            @Override public double toll(BlockPos p) { return world.toll(p); }
        };
    }

    private static List<Node> moves(Grid world, Node n, int fillers, int safeDrop) {
        List<Node> out = new ArrayList<>();
        BlockPos a = n.at();
        // The world as the way so far leaves it: what it broke is air - a step's floor among it is no
        // floor - and what it put down stands.
        Grid grid = afterPath(world, n);
        for (int[] d : DIRS) {
            int x = a.x() + d[0];
            int z = a.z() + d[1];
            for (int dy = -1; dy <= 1; dy++) {
                int y = a.y() + dy;
                List<BlockPos> cells = new ArrayList<>();
                if (dy > 0) {
                    cells.add(new BlockPos(a.x(), a.y() + 2, a.z()));     // room to jump
                } else if (dy < 0) {
                    cells.add(new BlockPos(x, a.y() + 1, z));              // the head, stepping down
                }
                cells.add(new BlockPos(x, y + 1, z));
                cells.add(new BlockPos(x, y, z));
                List<BlockPos> breaks = breaks(grid, cells);
                if (breaks == null) {
                    continue;
                }
                double extra = breaks.isEmpty() ? 0 : falling(grid, cells);
                BlockPos to = new BlockPos(x, y, z);
                BlockPos under = new BlockPos(x, y - 1, z);
                if (!grid.known(under)) {
                    continue;
                }
                if (grid.floor(under)) {
                    out.add(node(n, to, 1 + breaking(grid, breaks) + extra, 0,
                            new Step(Kind.MOVE, to, breaks, null)));
                } else if (dy <= 0 && n.placed() < fillers && grid.buildable(under)) {
                    // Nothing to stand on: a block put there - level, against the side of the one under
                    // the feet; a step down, against the side of the one under that.
                    out.add(node(n, to, 1 + BRIDGE + breaking(grid, breaks) + extra, 1,
                            new Step(Kind.BRIDGE, to, breaks, under)));
                } else if (dy < 0 && grid.air(under)) {
                    // Down off an edge: the fall, onto the first floor within a safe drop.
                    Node drop = drop(grid, n, x, y, z, breaks, extra, safeDrop);
                    if (drop != null) {
                        out.add(drop);
                    }
                }
            }
        }
        if (n.placed() < fillers && grid.buildable(a)) {
            // Up in place: a block put under the feet in a jump - room for the head above first.
            BlockPos over = new BlockPos(a.x(), a.y() + 2, a.z());
            List<BlockPos> breaks = breaks(grid, List.of(over));
            if (breaks != null) {
                double extra = breaks.isEmpty() ? 0 : falling(grid, List.of(over));
                BlockPos to = new BlockPos(a.x(), a.y() + 1, a.z());
                out.add(node(n, to, 1 + PILLAR + breaking(grid, breaks) + extra, 1,
                        new Step(Kind.PILLAR, to, breaks, a)));
            }
        }
        return out;
    }

    /** The fall after a step down, onto the first floor: {@code safeDrop} blocks in all at most. */
    private static Node drop(Grid grid, Node n, int x, int y, int z, List<BlockPos> breaks, double extra, int safeDrop) {
        for (int fall = 1; fall + 1 <= safeDrop; fall++) {
            BlockPos feet = new BlockPos(x, y - fall, z);
            BlockPos under = new BlockPos(x, y - fall - 1, z);
            if (!grid.known(feet) || !grid.air(feet) || !grid.known(under)) {
                return null;
            }
            if (grid.floor(under)) {
                return node(n, feet, 1 + breaking(grid, breaks) + extra + PER_FALL * fall, 0,
                        new Step(Kind.MOVE, feet, breaks, null));
            }
        }
        return null;
    }

    /** The cells of the move to break, in order; {@code null} when the body may not go through one. */
    private static List<BlockPos> breaks(Grid grid, List<BlockPos> cells) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : cells) {
            if (!grid.known(c) || !grid.passable(c)) {
                return null;
            }
            if (!grid.air(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** What breaking the blocks costs. */
    private static double breaking(Grid grid, List<BlockPos> breaks) {
        double cost = 0;
        for (BlockPos b : breaks) {
            cost += PER_BLOCK + grid.toll(b);
        }
        return cost;
    }

    /** Sand or gravel over the highest cell broken: it would come down into the way. */
    private static double falling(Grid grid, List<BlockPos> cells) {
        BlockPos top = cells.get(0);
        for (BlockPos c : cells) {
            if (c.y() > top.y()) {
                top = c;
            }
        }
        BlockPos over = new BlockPos(top.x(), top.y() + 1, top.z());
        return grid.known(over) && grid.falls(over) ? FALLING : 0;
    }

    private static Node node(Node from, BlockPos to, double cost, int placed, Step step) {
        return new Node(to, from.g() + cost, from.placed() + placed, from, step);
    }
}
