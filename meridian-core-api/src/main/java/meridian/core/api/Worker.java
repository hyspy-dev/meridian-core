package meridian.core.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The player at work on blocks: goes where a block is within reach of the tool that breaks it best,
 * takes that tool, breaks what is in the way and then the block, and picks up what was dropped -
 * core's {@link Navigator} for the walking, {@link InteractionControl} for the hands. For a module
 * that decides what to work on: a miner, a builder.
 *
 * <p>One thing at a time. A game's player's body is taken from the game for each walk
 * ({@link Movement#take}) unless the caller holds it already; a module working through many
 * blocks takes it once for the whole job, so the player does not get it back between them, and
 * the player taking it back (a key that moves them) ends the walk under way.
 */
public interface Worker {

    /**
     * What may be broken to get at a block.
     *
     * @param within  only blocks inside this box may be broken on the way - a selection dug from
     *                inside itself; {@code null} for any
     * @param wayIn   for a block in the rock, a way in the body's height from where it is broken
     *                from, so the body can walk in after its drop; else only what is on the line
     *                from the eyes
     */
    record Access(BlockBox within, boolean wayIn) {

        /** Anything in the way, and a way in for a block in the rock. */
        public static final Access ANY = new Access(null, true);

        /** Only blocks inside {@code box} may be broken on the way. */
        public static Access inside(BlockBox box) {
            return new Access(box, false);
        }
    }

    /**
     * Which tool to take for a block: the hotbar's best for it ({@link Player#bestToolSlot}), unless
     * the block's gather type has a tool preferred for it here and the hotbar holds one that breaks
     * it.
     *
     * <p>Near the edge of {@link Access#within} a tool whose dig is a sweep ({@link
     * InteractionControl#digSweeps} - a shovel's arc breaks every block in it that it breaks) is
     * passed over, preferred or best, for one that breaks only the block it aims at, when a block
     * just outside the box would go with it. With no such tool it is taken all the same.
     *
     * @param prefer a gather type ({@code Soils}) to a part of the id of the tool to take for it
     *               ({@code Shovel})
     */
    record Tools(java.util.Map<String, String> prefer) {

        /** The best tool for each block. */
        public static final Tools BEST = new Tools(java.util.Map.of());
    }

    /** How getting at a block and breaking it went. */
    enum Outcome {
        /** Broken. */
        BROKEN,
        /** There was no block there to begin with, or the cell is not known. */
        NOTHING,
        /** No place to break it from: none within reach of the tool, or none it may get at it from. */
        NO_PLACE,
        /** Places to break it from, but no way to any of them. */
        UNREACHABLE,
        /** It, or a block in the way, did not break in its swings. */
        WOULD_NOT_BREAK,
        /** Lava is next to it, or next to the way in: left. */
        LAVA,
        /** Cancelled, or the player took the body back. */
        INTERRUPTED,
        /** The player cannot be moved here. */
        NOT_AVAILABLE
    }

    /**
     * How a dig went.
     *
     * @param outcome how it ended
     * @param broken  every block it broke, in order - those in the way too, so a caller knows where
     *                the drops lie; the block itself last when it was broken
     */
    record Dug(Outcome outcome, List<BlockPos> broken) {

        public boolean done() {
            return outcome == Outcome.BROKEN;
        }
    }

    /** Whether the player can be put to work here (as {@link Navigator#available}). */
    boolean available();

    /**
     * Breaks the block at {@code target}. With the tool in the hotbar that breaks it best
     * ({@link Player#bestToolSlot}), from a place within that tool's reach
     * ({@link InteractionControl#digReach}) - where the body is when it can, else one it walks to,
     * never standing on the block nor on one in the way. What {@code access} allows is broken
     * first; a block next to lava is left.
     */
    default CompletableFuture<Dug> dig(BlockPos target, Access access) {
        return dig(target, access, Tools.BEST);
    }

    /** {@link #dig(BlockPos, Access)}, with the tool for each block it breaks chosen by {@code tools}. */
    CompletableFuture<Dug> dig(BlockPos target, Access access, Tools tools);

    /**
     * Puts items into the container at {@code chest} - a chest, a barrel: walks to within reach,
     * opens it, moves the stacks in from the hotbar, the storage and the backpack as a shift-click
     * does, and closes it. What does not fit stays with the player.
     *
     * @param items how many of each item, by id, to put in at most
     * @return how many of each went in; none when the container could not be got to, did not open,
     *         or is full
     */
    default CompletableFuture<java.util.Map<String, Integer>> deposit(BlockPos chest,
                                                                     java.util.Map<String, Integer> items) {
        return CompletableFuture.completedFuture(java.util.Map.of());
    }

    /**
     * Walks over the drops lying in {@code area}, nearest first, into each one's own cell (the
     * server picks one up within 1.75 of the feet), until none is left there that can be got to.
     *
     * @return how many were picked up
     */
    CompletableFuture<Integer> pickUp(BlockBox area);

    /** Ends what is under way, after the swing it is in. */
    void cancel();
}
