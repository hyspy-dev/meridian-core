package meridian.core.api;

import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;

/**
 * The player's body, moved the way the client moves it - for a session core plays the client of
 * (headless). A game moves its own player; there {@link #available()} is false.
 *
 * <p>Two halves. What the ground allows - where the player can stand, whether its body fits
 * somewhere - read off the blocks as the server sent them, each block's own collision boxes
 * included (a slab, a fence, a door). And single moves along the ground: walk to a point, jump
 * up onto one, pull up onto a ledge, step off and drop. A move plays out a tick at a time at 60
 * Hz with the client's own numbers - the server's movement settings, its gravity - so the
 * server sees a player walking, jumping and falling: a fall far enough hurts, as it would.
 *
 * <p>Finding a way through the world is not this service's: a module plans the path and plays
 * it move by move.
 */
public interface Movement {

    /** Whether core moves this session's player (a headless one). */
    boolean available();

    /** The body and what it can do, from the server's movement settings. */
    Capabilities capabilities();

    /**
     * Where the player's feet are when it stands in cell {@code cell} - on the floor in it or
     * under it, with room for the whole body above - or empty when it cannot stand there: no
     * floor, no room, a fluid, or a part of the world core has not been sent.
     */
    OptionalDouble standAt(BlockPos cell);

    /** Whether the player's body, feet at {@code feet} and {@code height} tall, hits no block. */
    boolean fits(Vec3 feet, double height);

    /** Whether core knows the blocks of {@code cell} (its part of the world was sent). */
    boolean known(BlockPos cell);

    /** Where the player's feet are now, as core moves them. */
    Vec3 position();

    /** Whether the player stands on the ground (not mid-jump or falling). */
    boolean onGround();

    /**
     * Plays one move from where the player is. Moves queue: each starts where the one before
     * ended. The future completes when the move is over, or straight away when it cannot start.
     */
    CompletableFuture<MoveResult> move(Move move);

    /** Stops after the move under way, dropping the queued ones ({@code INTERRUPTED}). */
    void stop();

    /**
     * The body and its reach.
     *
     * @param width        the body's width and depth
     * @param height       its height
     * @param eyeHeight    the eyes above the feet
     * @param stepHeight   the most it walks up without jumping
     * @param jumpHeight   the most it jumps up onto
     * @param mantleHeight the highest ledge it pulls itself up onto
     * @param safeDrop     the furthest it drops without being hurt
     * @param walkSpeed    blocks a second, walking
     * @param runSpeed     running
     * @param sprintSpeed  sprinting
     */
    record Capabilities(double width, double height, double eyeHeight, double stepHeight, double jumpHeight,
                        double mantleHeight, double safeDrop, double walkSpeed, double runSpeed, double sprintSpeed) {

        public double speed(Gait gait) {
            return switch (gait) {
                case WALK -> walkSpeed;
                case RUN -> runSpeed;
                case SPRINT -> sprintSpeed;
            };
        }
    }

    /** How fast the player goes. */
    enum Gait { WALK, RUN, SPRINT }

    /** The kinds of move. */
    enum Kind {
        /** Along the ground to a point no higher than a step. */
        WALK,
        /** A jump up (or across) onto a point. */
        JUMP,
        /** A jump to a ledge, then pulling up onto it. */
        MANTLE,
        /** Walking off an edge and falling to a point below. */
        DROP
    }

    /**
     * One move: of {@code kind}, to feet at {@code to}, at {@code gait}.
     */
    record Move(Kind kind, Vec3 to, Gait gait) {
        public static Move walk(Vec3 to) {
            return new Move(Kind.WALK, to, Gait.RUN);
        }
    }

    /** How a move went, and where the feet ended up. */
    record MoveResult(Status status, Vec3 at) {
        public enum Status {
            /** It played out: the feet are at the target. */
            ARRIVED,
            /** It could not start: the way, or the target, is not free. */
            BLOCKED,
            /** It was cut short: stopped, or the server moved the player. */
            INTERRUPTED,
            /** Core does not move this session's player. */
            NOT_AVAILABLE
        }

        public boolean arrived() {
            return status == Status.ARRIVED;
        }
    }
}
