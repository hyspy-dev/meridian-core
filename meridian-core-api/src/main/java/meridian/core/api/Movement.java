package meridian.core.api;

import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;

/**
 * The player's body, moved the way the client moves it - for a session core plays the client of
 * (headless). A game moves its own player; there {@link #available()} is false.
 *
 * <p>The body is simulated as the client simulates it, a tick at a time at 60 Hz, all the time:
 * keys and the look in, gravity, the ground's friction, pushes from the server (knockback, launch
 * pads) and the blocks out. What it does is reported to the server each tick, as the client reports
 * it, so the server sees a player walking, jumping, swimming, climbing and falling - and a fall far
 * enough hurts, as it would.
 *
 * <p>Three ways to drive it. {@link #move} plays one move toward a point - a walk, a jump, a pull-up
 * onto a ledge, a drop, a running jump over a gap, a swim, a climb, a flight - steering every tick
 * until it is there or cannot get there. {@link #hold} holds keys, for a module that steers by
 * itself. With neither, the body stands, and still falls, slides and is pushed.
 *
 * <p>What the ground allows - where the player can stand, whether its body fits, what a cell is to
 * it - is read off the blocks as the server sent them, each block's own collision boxes included.
 * {@link #predict} plays a move on a copy of the body without moving it. Finding a way through the
 * world is not this service's: a module plans the path and plays it move by move.
 */
public interface Movement {

    /** Whether core moves this session's player (a headless one in a world, with its settings). */
    boolean available();

    /** The body and what it can do, from the server's movement settings. */
    Capabilities capabilities();

    /**
     * Where the player's feet are when it stands in cell {@code cell} - on the floor in it or
     * under it, with room for the whole body above - or empty when it cannot stand there: no
     * floor, no room, a fluid at the head, or a part of the world core has not been sent.
     *
     * @deprecated the ground is the same for every player, not only one core moves: use
     *             {@link World#standAt}, which answers the same
     */
    @Deprecated
    OptionalDouble standAt(BlockPos cell);

    /**
     * Whether the player's body, feet at {@code feet} and {@code height} tall, hits no block.
     *
     * @deprecated use {@link World#fits}, which answers the same for every player
     */
    @Deprecated
    boolean fits(Vec3 feet, double height);

    /**
     * Whether core knows the blocks of {@code cell} (its part of the world was sent).
     *
     * @deprecated use {@link World#known}, which answers the same for every player
     */
    @Deprecated
    boolean known(BlockPos cell);

    /** What a cell is to the body: a wall, something to climb, a fluid. */
    Cell cell(BlockPos cell);

    /** Where the player's feet are now. */
    Vec3 position();

    /** Whether the player stands on the ground (not mid-jump or falling). */
    boolean onGround();

    /** The whole body now. */
    State state();

    /**
     * Plays one move from where the player is. Moves queue: each starts where the one before
     * ended. The future completes when the move is over, or straight away when it cannot start.
     */
    CompletableFuture<MoveResult> move(Move move);

    /** Ends the move under way and drops the queued ones ({@code INTERRUPTED}); the keys let go. */
    void stop();

    /**
     * Holds these keys from the next tick until the next call, once the moves under way are over;
     * {@code null} lets go.
     */
    void hold(Controls controls);

    /**
     * Turns the head to {@code look}, the body with it, the way a player turns to aim - and keeps
     * it there while nothing steers. The body reports the turn to the server on its next tick, as
     * the client reports a turn of the camera, so whatever the server aims by the look (a tool's
     * area, the block a command acts on) aims there. A move under way or queued, or held keys,
     * steer the look themselves: then the head is not turned.
     *
     * <p>A forge lands without it - the server checks only that a block is within reach - but a
     * player that hits a block looks at it.
     *
     * <p>A core too old to turn the head answers {@code false}.
     *
     * @return {@code true} once the tick that turned the head has gone out to the server (which
     *         takes it on its own next tick); {@code false} when a move or held keys steer, or core
     *         does not move this player
     */
    default CompletableFuture<Boolean> look(Look look) {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Turns the head to look at {@code point} from the eyes - {@link Capabilities#eyeHeight} over
     * the feet - as {@link #look} does. A point straight over or under the eyes keeps the yaw.
     *
     * @return as {@link #look}; {@code false} as well when the point is where the eyes are
     */
    default CompletableFuture<Boolean> face(Vec3 point) {
        Vec3 feet = position();
        if (point == null || feet == null || !available()) {
            return CompletableFuture.completedFuture(false);
        }
        double dx = point.x() - feet.x();
        double dy = point.y() - (feet.y() + capabilities().eyeHeight());
        double dz = point.z() - feet.z();
        if (dx * dx + dy * dy + dz * dz < 1e-12) {
            return CompletableFuture.completedFuture(false);
        }
        Look l = Look.along(dx, dy, dz);
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
            l = new Look(state().yaw(), l.pitch());
        }
        return look(l);
    }

    /**
     * Turns the head to look at the middle of a block - where a hit, a use or a dig on it aims - as
     * {@link #look} does.
     */
    default CompletableFuture<Boolean> face(BlockPos block) {
        if (block == null) {
            return CompletableFuture.completedFuture(false);
        }
        return face(new Vec3(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5));
    }

    /** Plays a move on a copy of the body as it is now, without moving it. */
    Prediction predict(Move move);

    /**
     * Plays moves one after another on a copy of the body, each through its point into the next as
     * {@link #move} would, until one does not arrive.
     */
    Prediction predict(java.util.List<Move> moves);

    /**
     * The keys and the look of one tick.
     *
     * @param forward forward (1) or back (-1), and anything between
     * @param strafe  right (1) or left (-1)
     * @param yaw     where the body faces, radians; 0 faces -z, a quarter turn faces -x
     * @param pitch   the look up or down, radians
     * @param jump    the jump key held (swims up, flies up)
     * @param crouch  crouch held (slides at a sprint, swims and flies down, lets go of a ledge)
     * @param sprint  sprint held
     * @param walk    walking, not running
     * @param fly     wants to fly, where flying is allowed
     */
    record Controls(double forward, double strafe, float yaw, float pitch, boolean jump, boolean crouch,
                    boolean sprint, boolean walk, boolean fly) {

        public static Controls idle(float yaw, float pitch) {
            return new Controls(0, 0, yaw, pitch, false, false, false, false, false);
        }
    }

    /**
     * The body in one tick: where, how fast, and what it is doing - the movement states it reports.
     */
    record State(Vec3 position, Vec3 velocity, float yaw, float pitch, boolean onGround, boolean inFluid,
                 boolean swimming, boolean climbing, boolean flying, boolean crouching, boolean sprinting,
                 boolean sliding, boolean rolling, boolean mantling, boolean hanging) {}

    /**
     * What a cell is to the body.
     *
     * @param solid     its block stops the body (some of the cell may still be free: a slab)
     * @param climbable a ladder, a vine, a rope - held on to and climbed
     * @param fluid     the fluid in it, {@code null} for none
     * @param swimmable a fluid the body swims in
     * @param hurts     lava, fire and their kin
     * @param door      a door in it, and whether it is open; {@code door} is the cell holding the
     *                  door's block (its lower half), to use it
     */
    record Cell(boolean solid, boolean climbable, String fluid, boolean swimmable, boolean hurts, Door doorState,
                BlockPos door) {

        /** Whether the cell holds a door that is shut. */
        public boolean closedDoor() {
            return doorState == Door.CLOSED;
        }
    }

    /** A door's state, for a cell. */
    enum Door { NONE, CLOSED, OPEN }

    /**
     * The body and its reach.
     *
     * @param width        the body's width and depth
     * @param height       its height
     * @param eyeHeight    the eyes above the feet
     * @param stepHeight   the most it walks up without jumping
     * @param jumpHeight   the most it jumps up onto
     * @param mantleHeight the highest ledge it pulls itself up onto ({@code 0} where the server has
     *                     pulling up switched off)
     * @param safeDrop     the furthest it drops without being hurt
     * @param walkSpeed    blocks a second, walking
     * @param runSpeed     running
     * @param sprintSpeed  sprinting
     * @param runLeap      the widest gap a running jump clears, level
     * @param sprintLeap   a sprinting jump's
     * @param swimSpeed    blocks a second, swimming
     * @param climbSpeed   climbing up
     * @param flySpeed     flying, where it is allowed; {@code 0} where not
     * @param gravity      how fast a fall gathers speed, blocks a second each second - the server's
     *                     ({@code PhysicsConstants.GRAVITY_ACCELERATION}); the air holds a fall back
     *                     only near its terminal speed, far beyond any fall a path takes
     */
    record Capabilities(double width, double height, double eyeHeight, double stepHeight, double jumpHeight,
                        double mantleHeight, double safeDrop, double walkSpeed, double runSpeed, double sprintSpeed,
                        double runLeap, double sprintLeap, double swimSpeed, double climbSpeed, double flySpeed,
                        double gravity) {

        /**
         * The capabilities without the gravity, which is then the server's 32.
         *
         * @deprecated core gives the gravity: use the canonical constructor
         */
        @Deprecated
        public Capabilities(double width, double height, double eyeHeight, double stepHeight, double jumpHeight,
                            double mantleHeight, double safeDrop, double walkSpeed, double runSpeed,
                            double sprintSpeed, double runLeap, double sprintLeap, double swimSpeed,
                            double climbSpeed, double flySpeed) {
            this(width, height, eyeHeight, stepHeight, jumpHeight, mantleHeight, safeDrop, walkSpeed, runSpeed,
                    sprintSpeed, runLeap, sprintLeap, swimSpeed, climbSpeed, flySpeed, 32);
        }

        /**
         * How long a fall of {@code height} blocks takes, seconds, from standing still:
         * {@code √(2·height / gravity)} - what the body's own fall gives to the hundredth of a
         * second over any drop it survives.
         */
        public double fallSeconds(double height) {
            return height <= 0 || gravity <= 0 ? 0 : Math.sqrt(2 * height / gravity);
        }

        public double speed(Gait gait) {
            return switch (gait) {
                case WALK -> walkSpeed;
                case RUN -> runSpeed;
                case SPRINT -> sprintSpeed;
            };
        }

        public double leap(Gait gait) {
            return gait == Gait.SPRINT ? sprintLeap : gait == Gait.RUN ? runLeap : 0;
        }

        public boolean canFly() {
            return flySpeed > 0;
        }
    }

    /** How fast the player goes. */
    enum Gait { WALK, RUN, SPRINT }

    /** The kinds of move. */
    enum Kind {
        /** Along the ground to a point no higher than a step (or a block the body hops up by itself). */
        WALK,
        /** A jump up onto a point. */
        JUMP,
        /** A jump to a ledge, then pulling up onto it. */
        MANTLE,
        /** Walking off an edge and falling to a point below. */
        DROP,
        /** A running jump over a gap, onto a point across it. */
        LEAP,
        /** Through a fluid, to a point in it or on its bank. */
        SWIM,
        /** Up or down something climbable, to a point on it or off its top. */
        CLIMB,
        /** Flying to a point, where flying is allowed. */
        FLY
    }

    /** One move: of {@code kind}, to feet at {@code to}, at {@code gait}. */
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
            /** It could not get there: the way, or the target, is not free, or it fell short. */
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

    /**
     * Moves played on a copy of the body.
     *
     * @param result  how the last one played would go
     * @param seconds how long they would take
     * @param hurt    whether the body would land hard enough to be hurt
     * @param arrived how many of the moves would arrive before one does not
     */
    record Prediction(MoveResult result, double seconds, boolean hurt, int arrived) {}
}
