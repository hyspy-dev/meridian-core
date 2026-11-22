package meridian.core.api;

import java.util.List;
import java.util.Map;
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
     * @param within  only blocks inside this box may be broken to get at the block from where the
     *                body stands - a selection dug from inside itself; {@code null} for any. The way
     *                to that place is not bound by it: where no walk goes there, one is made
     *                ({@link #travel}), through the selection or built where that is near as short,
     *                else through what lies outside it (never through what is used - a chest, a bench)
     * @param wayIn   for a block in the rock, a way in the body's height from where it is broken
     *                from, so the body can walk in after its drop; else only what is on the line
     *                from the eyes
     */
    record Access(BlockBox within, boolean wayIn) {

        /** Anything in the way, and a way in for a block in the rock. */
        public static final Access ANY = new Access(null, true);

        /** Only blocks inside {@code box} may be broken to get at the block. */
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
        NOT_AVAILABLE,
        /** Placed. */
        PLACED,
        /** Something is in the cell to place into. */
        OCCUPIED,
        /** The player carries none of the item to place. */
        NO_ITEM,
        /** Nothing beside the cell to place against. */
        NO_SUPPORT,
        /** The server did not put the block there. */
        REFUSED,
        /** A container was filled from a fluid. */
        FILLED
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
     * Whether {@link #dig(BlockPos, Access, Tools)} would break the block at {@code target} from
     * where the body stands now: with the tool it would take for it, the place it would pick is this
     * one - no step to another. {@code false} when it would walk somewhere first, when there is no
     * place to break it from, and when the block is not there. Nothing is done: no slot is changed.
     */
    default boolean digsFromHere(BlockPos target, Access access, Tools tools) {
        return false;
    }

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
     * {@link #deposit} telling a container that took nothing because it is full from one that was
     * never opened - not got to, or the server would not open it (a server's guard may want the head
     * on it, and put the player back when it is not): nothing is known of that one.
     *
     * @return how many of each went in; empty when the container was not opened
     */
    default CompletableFuture<java.util.Optional<java.util.Map<String, Integer>>> depositOpened(BlockPos chest,
            java.util.Map<String, Integer> items) {
        return deposit(chest, items).thenApply(java.util.Optional::of);
    }

    /**
     * Walks over the drops lying in {@code area}, nearest first, into each one's own cell (the
     * server picks one up within 1.75 of the feet), until none is left there that can be got to.
     *
     * @return how many were picked up
     */
    CompletableFuture<Integer> pickUp(BlockBox area);

    /**
     * Puts a block of {@code itemId} into the empty {@code cell}, turned as {@code rotation} says (the
     * server's orientation number, 0 to 63; negative: as a player's placing turns it). The item is
     * taken into the hotbar from the storage or the backpack when the hotbar has none. The block is
     * placed against one beside the cell - the one below first - from a place the body walks to
     * with a clear line to that face, never one the block would land in.
     *
     * @return {@link Outcome#PLACED}; {@link Outcome#OCCUPIED} when something is in the cell;
     *         {@link Outcome#NO_ITEM} when the player carries none; {@link Outcome#NO_SUPPORT}
     *         when nothing is beside it to place against; {@link Outcome#NO_PLACE} /
     *         {@link Outcome#UNREACHABLE} when there is no place to do it from or no way there;
     *         {@link Outcome#REFUSED} when the server did not put it there
     */
    default CompletableFuture<Outcome> place(BlockPos cell, String itemId, int rotation) {
        return CompletableFuture.completedFuture(Outcome.NOT_AVAILABLE);
    }

    /**
     * Pours what the held container carries - a bucket of water - into the empty {@code cell}, as a
     * player does: against a block beside it, from a place the body walks to, as {@link #place} puts a
     * block. The container's item is taken into the hotbar as {@code place} takes a block.
     *
     * @return {@link Outcome#PLACED} once the fluid shows in the cell; else as {@link #place}
     */
    default CompletableFuture<Outcome> pour(BlockPos cell, String itemId) {
        return CompletableFuture.completedFuture(Outcome.NOT_AVAILABLE);
    }

    /**
     * Fills an empty container - a bucket - from the fluid in {@code cell}: from a place within the
     * item's use reach, looking at the cell (the server takes the first source its ray along the look
     * meets, which may be one in front of the cell).
     *
     * @return {@link Outcome#FILLED} once the container in the hand is its filled item; {@link Outcome#NO_ITEM},
     *         {@link Outcome#NOTHING} (no fluid there), {@link Outcome#NO_PLACE},
     *         {@link Outcome#UNREACHABLE}, {@link Outcome#REFUSED}
     */
    default CompletableFuture<Outcome> scoop(BlockPos cell, String itemId) {
        return CompletableFuture.completedFuture(Outcome.NOT_AVAILABLE);
    }

    /**
     * Goes to {@code goal} - anywhere in the world - as a player gets there: walks where there is a
     * way; digs one where rock is in the way - a tunnel, a staircase up out of a pit or down into the
     * ground; builds one where there is nothing to stand on - a pillar up, a bridge across - of the
     * plain blocks it carries (stone, soil, planks: cubes that stay put). A leg at a time, each one
     * nearer; the world not shown yet is come near and waited for. Never through a fluid or by lava.
     *
     * @return {@link NavResult.Status#ARRIVED} there; {@link NavResult.Status#NO_PATH} when no leg gets
     *         nearer (shut in by what nothing carried breaks, nothing to build with up in the air);
     *         {@link NavResult.Status#BLOCKED} when the legs stop getting nearer
     */
    default CompletableFuture<NavResult> travel(Goal goal) {
        return CompletableFuture.completedFuture(new NavResult(NavResult.Status.NOT_AVAILABLE, null, 0, 0));
    }

    /**
     * Comes down a pillar the body stands on: the block under the feet broken, the body dropping onto
     * the next, {@code blocks} times - or until the block under the feet is no longer one it may break.
     *
     * @return {@link Outcome#BROKEN} once down; {@link Outcome#WOULD_NOT_BREAK} when a block did not go
     */
    default CompletableFuture<Outcome> pillarDown(int blocks) {
        return CompletableFuture.completedFuture(Outcome.NOT_AVAILABLE);
    }

    /**
     * Builds a pillar under the body, as a player does to get up high: looking down, a jump, and at
     * the top of it a block of {@code itemId} put where the feet were; {@code blocks} times. The body
     * ends on top of it, {@code blocks} higher.
     *
     * @return {@link Outcome#PLACED} once on top; {@link Outcome#NO_ITEM}, {@link Outcome#NO_PLACE}
     *         (no room over the head, or not on the ground), {@link Outcome#REFUSED} when a block
     *         did not land
     */
    default CompletableFuture<Outcome> pillarUp(String itemId, int blocks) {
        return CompletableFuture.completedFuture(Outcome.NOT_AVAILABLE);
    }

    /**
     * Takes items out of a container: walks to it, opens it, moves the stacks of these items into
     * the player's inventory as a shift-click does, and closes it.
     *
     * @return how many of each came out - what the player holds more of afterwards
     */
    default CompletableFuture<Map<String, Integer>> take(BlockPos chest, Map<String, Integer> items) {
        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * Walks over these dropped items wherever they lie, nearest first, into each one's own cell, as
     * {@link #pickUp(BlockBox)} does - those that rolled, fell or floated away from where they were
     * dropped too. One gone already, or that cannot be got to, is left.
     *
     * @return how many were picked up
     */
    default CompletableFuture<Integer> pickUp(java.util.Collection<Integer> items) {
        return CompletableFuture.completedFuture(0);
    }

    /** Ends what is under way, after the swing it is in. */
    void cancel();
}
