package meridian.core.impl.movement;

/**
 * The numbers of the client's own movement that nothing it is sent gives - each fitted to a
 * recording of the real client (08.11.2026, {@code meridian-tests/movement/recordings}). Everything
 * the server tells the client is in {@code MovementSettings} and used from there; what is here is
 * the client's code, which is closed.
 */
final class Tuning {
    private Tuning() {}

    /** On the ground with keys in: the velocity keeps 0.82 a tick and gains 0.18 of the asked-for speed, on any ground. */
    static final double KEY_KEEP = 0.82;
    /** A key axis comes in and goes half a step a tick (diagonals pass 63.43° on the way). */
    static final double KEY_STEP = 0.5;
    /** A sprint's speed comes in, and goes, over this many ticks, on a cubic ease. */
    static final int SPRINT_EASE_TICKS = 6;
    /** After landing from a jump or a fall the asked-for speed starts again at about this share of the cap (1.77 of 5.5). */
    static final double LANDING_RAMP = 0.322;

    /** The highest rise stepped up on the ground, in one tick (recorded +1.0001 and +0.5001). */
    static final double STEP_HEIGHT = 1.0;
    /** The highest rise stepped up in the air (at the top of a jump, landing beside a slab). */
    static final double AIR_STEP_HEIGHT = 0.5;
    /** A rise above this is a full block: the step slows the body (AutoJumpObstacleSpeedLoss) for a while. */
    static final double SLOW_STEP_ABOVE = 0.5 + 1e-3;
    /** How far above a stepped-onto top the client puts the feet. */
    static final double STEP_LIFT = 1e-4;

    /** A ledge is taken when its top is above the head by no more than this (fitted: 1.8724..2.0068 over the feet). */
    static final double MANTLE_OVER_HEAD = 0.157;
    /** The pull-up puts the body this far past where it met the wall. */
    static final double MANTLE_REACH = 0.5;
    /** Then the body is held still this many ticks (61 packets in all, 9 of 9). */
    static final int MANTLE_FREEZE_TICKS = 60;
    /** Mantling is a pull-up at three blocks, as the game's players know it (a higher one is not verified). */
    static final double MANTLE_MAX_HEIGHT = 3.0;

    /** A roll lasts this many ticks after the landing (rollTimeToComplete 0.2 s is 12). */
    static final int ROLL_TICKS = 13;
    /** A roll's push comes in by this share a tick (0.3201, 0.6424, 0.9649). */
    static final double ROLL_RAMP = 0.3216;
    /** A safety roll is taken when crouch is held on landing. */
    static final boolean ROLL_NEEDS_CROUCH = true;

    /** A slide's asked-for speed, tick by tick, from 7.0015 (identical in all four recorded slides). */
    static final double[] SLIDE_CURVE = {7.0015, 7.0014, 7.0013, 7.0006, 6.9991, 6.9961, 6.9907, 6.9819, 6.9684,
            6.9484, 6.9201, 6.8813, 6.8293, 6.7613, 6.6738, 6.5632, 6.4251, 6.2551, 6.0478, 5.7978, 5.4988, 5.1442,
            4.7267, 4.2384, 3.6709, 3.0150, 2.2610, 1.3985, 0.4163};
    /** After the slide the crouch ramp starts again here. */
    static final double SLIDE_AFTER = 0.5384;
    /** The sliding flag clears this many ticks into the slide. */
    static final int SLIDE_FLAG_TICKS = 30;

    /** Swimming starts with this much fluid over the feet (fitted: between 0.947 and 1.058). */
    static final double SWIM_DEPTH = 1.0;
    /** A body floats with its feet this far under the surface (fitted: 1.4000). */
    static final double FLOAT_DEPTH = 1.4;
    /** The float's pull: 10 a block, 1/6 of the gap a tick. */
    static final double FLOAT_SPRING = 10.0;

    /** Climbing speeds in the settings are per client tick ({@code ClimbSpeed} 0.035 = 2.1 blocks/s). */
    static final double CLIMB_SPEED_PER_TICK = 60;
    /** A climbable within this of the body is held on to (fitted: 0.078..0.114). */
    static final double CLIMB_REACH = 0.1;
    /** Jumping off a climbable: straight off at this share of jumpForce. */
    static final double CLIMB_JUMP_OFF = 0.4;
    /** ...or, with a side key, this much off and this much to the side. */
    static final double CLIMB_JUMP_SIDE = 0.25;

    /** Flying: across, the velocity closes this much of the gap a tick (fitted: 0.27793, 2239 ticks). */
    static final double FLY_BLEND = 0.27793;
    /** Flying: what is left of the vertical speed keeps this much a tick (fitted: 0.541548). */
    static final double FLY_REST_DECAY = 0.541548;

    /** The body turns toward its movement at most this much a tick, to at most 45° off the look. */
    static final double BODY_TURN = 0.1;
    static final double BODY_OFFSET = Math.toRadians(45);

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
