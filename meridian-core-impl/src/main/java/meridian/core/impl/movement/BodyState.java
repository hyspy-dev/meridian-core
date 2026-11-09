package meridian.core.impl.movement;

/**
 * What the client keeps of its body from one tick to the next: where it is, how fast it goes on
 * its own and what pushes are carrying it, how far its keys are in, and what it is doing - the
 * movement states it reports.
 */
final class BodyState {

    double x;
    double y;
    double z;
    /** The body's own velocity as the blocks left it after the last move. */
    double vx;
    double vy;
    double vz;
    float yaw;
    float pitch;
    /** Where the body faces: it turns toward its movement, 0.1 rad a tick at most (the client's bodyOrientation). */
    float bodyYaw;
    /** The velocity the body moved with this tick, before the blocks stopped any of it - what the client reports. */
    double mvx;
    double mvy;
    double mvz;
    final Pushes pushes = new Pushes();

    boolean onGround;
    boolean inFluid;
    boolean swimming;
    boolean swimJumping;
    boolean climbing;
    boolean flying;
    boolean crouching;
    boolean forcedCrouching;
    boolean walking;
    boolean running;
    boolean sprinting;
    boolean jumping;
    boolean falling;
    boolean fallingFar;
    boolean sliding;
    boolean rolling;
    boolean mantling;
    /** No movement keys this tick - the client's idle and horizontalIdle. */
    boolean noKeys = true;

    /** The forward and side key axes as they come in and go: half a step a tick. */
    double keyF;
    double keyS;
    /**
     * The speed the keys ask for so far, blocks a second: it rises toward the cap W by
     * acceleration·W²/baseSpeed a tick while they are held, keeps its value across a change of
     * direction, and is gone the tick they are let go (fitted to the client, 08.11.2026).
     */
    double ramp;
    /** Ticks since the sprint began (its speed comes in over 6), -1 when not sprinting. */
    int sprintTicks = -1;
    /** Ticks since the sprint ended (its speed goes over 6), -1 when not easing out. */
    int sprintOutTicks = -1;

    /** Jump held last tick: a jump starts on the press. */
    boolean jumpHeld;
    /** Crouch held last tick: a slide starts on the press. */
    boolean crouchHeld;
    /** A press not yet used, kept for {@code JumpBufferDuration} seconds. */
    double jumpBuffer;
    /** Whole ticks on the ground since the last landing (a jump needs one). */
    int groundTicks;
    /** Ticks in the air since leaving the ground. */
    int airTicks;
    /** This air segment's speed multiplier, fixed at take-off (1.025 for a quick re-jump, 0.975 off an edge). */
    double airMul = 1;
    /** Ticks since the keys came in mid-air (their speed comes in by the air control), -1 when held from take-off. */
    int airKeyTicks = -1;
    /** The body's speed when the keys came in mid-air. */
    double airKeySpeed;
    /** This air segment began with a jump. */
    boolean jumped;
    /** Where the feet left the ground. */
    double takeoffY;
    /** How fast it fell the last tick before landing - what the server takes fall damage from. */
    double landingSpeed;
    /** The falling flag of the tick before: pushes die by the air's resistance after it. */
    boolean wasFalling;

    /** An auto-step's slow-down: the cap's multiplier and ticks left. */
    double slowMultiplier = 1;
    int slowTicks;
    /** Ticks into a roll, -1 when not rolling. */
    int rollTicks = -1;
    double rollDirX;
    double rollDirZ;
    /** Ticks into a slide, -1 when not sliding, and the speed it began at. */
    int slideTicks = -1;
    double slideStart;
    /** Ticks left frozen after pulling up onto a ledge. */
    int mantleTicks;

    /** Flying: the vertical speed left over from before, dying away by 0.5415 a tick. */
    double flyRest;
    /** Climbing: how far the side key is in, -1 to 1, half a step a tick. */
    double climbSide;
    /** Climbing in the tick before (the first tick of a climb rises a step more; the last steps over). */
    boolean wasClimbing;
    double climbNx;
    double climbNz;
    /** Swimming in the tick before (a swim jump turns into a jump the tick the body leaves the water). */
    boolean wasSwimming;
    /** The movement keys of the last tick: the moving state lingers a tick after they go. */
    boolean hadKeys;

    BodyState copy() {
        BodyState c = new BodyState();
        c.x = x;
        c.y = y;
        c.z = z;
        c.vx = vx;
        c.vy = vy;
        c.vz = vz;
        c.yaw = yaw;
        c.pitch = pitch;
        c.bodyYaw = bodyYaw;
        c.mvx = mvx;
        c.mvy = mvy;
        c.mvz = mvz;
        for (Pushes.Push p : pushes.carried) {
            c.pushes.carried.add(p.copy());
        }
        c.onGround = onGround;
        c.inFluid = inFluid;
        c.swimming = swimming;
        c.swimJumping = swimJumping;
        c.climbing = climbing;
        c.flying = flying;
        c.crouching = crouching;
        c.forcedCrouching = forcedCrouching;
        c.walking = walking;
        c.running = running;
        c.sprinting = sprinting;
        c.jumping = jumping;
        c.falling = falling;
        c.fallingFar = fallingFar;
        c.sliding = sliding;
        c.rolling = rolling;
        c.mantling = mantling;
        c.noKeys = noKeys;
        c.keyF = keyF;
        c.keyS = keyS;
        c.ramp = ramp;
        c.sprintTicks = sprintTicks;
        c.sprintOutTicks = sprintOutTicks;
        c.jumpHeld = jumpHeld;
        c.crouchHeld = crouchHeld;
        c.jumpBuffer = jumpBuffer;
        c.groundTicks = groundTicks;
        c.airTicks = airTicks;
        c.airMul = airMul;
        c.airKeyTicks = airKeyTicks;
        c.airKeySpeed = airKeySpeed;
        c.jumped = jumped;
        c.takeoffY = takeoffY;
        c.landingSpeed = landingSpeed;
        c.wasFalling = wasFalling;
        c.slowMultiplier = slowMultiplier;
        c.slowTicks = slowTicks;
        c.rollTicks = rollTicks;
        c.rollDirX = rollDirX;
        c.rollDirZ = rollDirZ;
        c.slideTicks = slideTicks;
        c.slideStart = slideStart;
        c.mantleTicks = mantleTicks;
        c.flyRest = flyRest;
        c.climbSide = climbSide;
        c.wasClimbing = wasClimbing;
        c.climbNx = climbNx;
        c.climbNz = climbNz;
        c.wasSwimming = wasSwimming;
        c.hadKeys = hadKeys;
        return c;
    }

    /** Total velocity: the body's own and every push with a config. */
    double totalX() {
        return vx + pushes.sumX();
    }

    double totalY() {
        return vy + pushes.sumY();
    }

    double totalZ() {
        return vz + pushes.sumZ();
    }
}
