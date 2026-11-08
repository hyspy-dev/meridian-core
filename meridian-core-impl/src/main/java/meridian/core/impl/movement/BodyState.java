package meridian.core.impl.movement;

/**
 * What the client keeps of its body from one tick to the next: where it is, how fast it goes on
 * its own and what pushes are carrying it, and what it is doing - the movement states it reports.
 */
final class BodyState {

    double x;
    double y;
    double z;
    /** The body's own velocity: what its input and gravity made. */
    double vx;
    double vy;
    double vz;
    /**
     * A push that came without a config, horizontally: it rides on the body's own velocity and
     * dies away by the ground's drag, or the air's ({@code MotionControllerBase:1316}).
     */
    double kx;
    double kz;
    float yaw;
    float pitch;
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
    boolean sliding;
    boolean rolling;
    boolean mantling;

    /** Jump held last tick: a jump starts on the press. */
    boolean jumpHeld;
    /** A press not yet used, kept for {@code JumpBufferDuration} seconds. */
    double jumpBuffer;
    /** Seconds the body has been off the ground. */
    double airTime;
    /** How fast it fell the last tick before landing - what the server takes fall damage from. */
    double landingSpeed;
    /** A slow-down under way (an auto-jump's, a landing's): the multiplier and seconds left. */
    double slowMultiplier = 1;
    double slowTime;
    double rollTime;

    /** A ledge being held: where the hands are and where the body stands once up. */
    Ledge ledge;
    /** Seconds into pulling up, when {@link #ledge} is set and the pull-up started. */
    double pullTime = -1;

    /** A ledge the hands hold: its top, and the wall's outward normal on the ground plane. */
    record Ledge(double top, double nx, double nz, double hangX, double hangY, double hangZ) {}

    BodyState copy() {
        BodyState c = new BodyState();
        c.x = x;
        c.y = y;
        c.z = z;
        c.vx = vx;
        c.vy = vy;
        c.vz = vz;
        c.kx = kx;
        c.kz = kz;
        c.yaw = yaw;
        c.pitch = pitch;
        for (Pushes.Push p : pushes.carried) {
            Pushes.Push q = new Pushes.Push(p.x, p.y, p.z, p.config);
            c.pushes.carried.add(q);
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
        c.sliding = sliding;
        c.rolling = rolling;
        c.mantling = mantling;
        c.jumpHeld = jumpHeld;
        c.jumpBuffer = jumpBuffer;
        c.airTime = airTime;
        c.landingSpeed = landingSpeed;
        c.slowMultiplier = slowMultiplier;
        c.slowTime = slowTime;
        c.rollTime = rollTime;
        c.ledge = ledge;
        c.pullTime = pullTime;
        return c;
    }

    /** Total velocity: the body's own, a push without a config, and every push with one. */
    double totalX() {
        return vx + kx + pushes.sumX();
    }

    double totalY() {
        return vy + pushes.sumY();
    }

    double totalZ() {
        return vz + kz + pushes.sumZ();
    }
}
