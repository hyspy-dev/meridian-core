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
    CompletableFuture<Dug> dig(BlockPos target, Access access);

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
