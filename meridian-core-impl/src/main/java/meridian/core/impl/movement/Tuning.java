package meridian.core.impl.movement;

/**
 * The numbers of the client's own movement that nothing sent or published gives: how input turns
 * into speed, how a step is taken, when a ledge is grabbed. Everything the server tells the client
 * is in {@code MovementSettings} and used from there; what is here is the client's code, which is
 * closed. Each one is fitted to recordings of the real client ({@code meridian-tests/movement}).
 *
 * <p>Until a value is fitted it holds the reading of the settings it belongs with - the names say
 * what they scale - and the comment says so.
 */
final class Tuning {
    private Tuning() {}

    /** Ground: how much of the gap to the wished velocity is closed per tick, at the default ground's friction. Not fitted: {@code acceleration}. */
    static double groundBlend(double acceleration, double friction) {
        return clamp01(acceleration * friction / DEFAULT_FRICTION);
    }

    static final double DEFAULT_FRICTION = 0.18;

    /** Air: the gap to the wished velocity closed per tick is {@code airFriction} over the speed range. Not fitted. */
    static final boolean AIR_FRICTION_IS_BLEND = true;

    /** The highest rise taken without a jump, when the block allows it ({@code DisableAutoStep}). Not fitted: a slab. */
    static final double STEP_HEIGHT = 0.5625;

    /** How long the auto-jump's slow-down lasts is the settings'; the obstacle it jumps is up to this high. Not fitted. */
    static final double AUTO_JUMP_HEIGHT = 1.0625;

    /** Swimming: the vertical speed closes on its target by this much per tick. Not fitted. */
    static final double SWIM_BLEND = 0.1;

    /** The body swims once a fluid reaches this far up it (of its height). Not fitted: chest. */
    static final double SWIM_DEPTH = 0.6;

    /** Climbing speeds in the settings are per client tick ({@code ClimbSpeed} 0.035 = 2.1 blocks/s). Not fitted. */
    static final double CLIMB_SPEED_PER_TICK = 60;

    /** A climbable block within this of the body is held on to. Not fitted. */
    static final double CLIMB_REACH = 0.1;

    /** Flying: the velocity closes on the wished one by this much per tick. Not fitted. */
    static final double FLY_BLEND = 0.15;

    /**
     * A ledge is grabbed when the jump falls short of it: the body near the top of its jump or
     * coming down (vertical speed at most {@link #MANTLE_GRAB_MAX_VY}), the ledge's top above the
     * feet by this, and below the eyes + {@link #MANTLE_REACH_ABOVE_EYES}. Not fitted.
     */
    static final double MANTLE_MIN_ABOVE_FEET = 0.3;
    static final double MANTLE_GRAB_MAX_VY = 1.5;
    static final double MANTLE_REACH_ABOVE_EYES = 0.6;
    /** A wall is this close for a grab. Not fitted. */
    static final double MANTLE_WALL_DISTANCE = 0.3;
    /** Pulling up takes this long: the {@code Mantle_Up} animation, 60 frames. */
    static final double MANTLE_UP_SECONDS = 1.0;
    /** Moving along a ledge while hanging. Not fitted. */
    static final double MANTLE_SHUFFLE_SPEED = 1.5;

    /** A ledge is grabbed only with jump held in the air, as most games have it. Not fitted. */
    static final boolean MANTLE_NEEDS_JUMP = true;

    /**
     * The roll's speeds ({@code RollStartSpeedModifier} 3.5, {@code RollExitSpeedModifier} 2.2) are
     * blocks a second, not a share of the base speed - a share would send a roll of 0.2 s four
     * blocks. Not fitted.
     */
    static final boolean ROLL_SPEEDS_ABSOLUTE = true;

    /** A safety roll is taken when crouch is held on landing. Not fitted. */
    static final boolean ROLL_NEEDS_CROUCH = true;

    /** Sliding: the ground's friction slows the slide by this share of the walk's blend. Not fitted. */
    static final double SLIDE_FRICTION_SHARE = 0.15;

    static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** The settings' linear range map ({@code convertToNewRange}): {@code v} over [a, b] onto [lo, hi], clamped. */
    static double range(double v, double a, double b, double lo, double hi) {
        if (b <= a) {
            return lo;
        }
        double f = (v - a) / (b - a);
        f = f < 0 ? 0 : f > 1 ? 1 : f;
        return lo + (hi - lo) * f;
    }
}
