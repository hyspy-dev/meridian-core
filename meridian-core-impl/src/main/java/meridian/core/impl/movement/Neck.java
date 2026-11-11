package meridian.core.impl.movement;

import meridian.core.api.Movement.Controls;

/**
 * The head turns the way a player's does with a mouse: fast while it is far from where it wants to
 * look, slowing as it gets there - a share of what is left each tick, no faster than a cap, no slower
 * than a crawl. Yaw and pitch turn together, so a turn goes straight across the screen.
 *
 * <p>The keys a pilot picks are for its own heading. While the head is still turning they are
 * turned with it ({@link #follow}): the body goes where the pilot sends it, the forward key taking a
 * side key's share - a player running one way while the mouse swings the look round.
 */
final class Neck {

    /** The share of what is left of a turn the head turns in a tick. */
    static final double SHARE = 0.18;
    /** The most the head turns in a tick: 540° a second. */
    static final double MOST = Math.toRadians(9);
    /** The least, so a turn ends. */
    static final double LEAST = Math.toRadians(0.3);

    private Neck() {
    }

    /** How far apart two looks are, radians. */
    static double apart(float yaw, float pitch, float toYaw, float toPitch) {
        double dy = Math.IEEEremainder(toYaw - yaw, 2 * Math.PI);
        double dp = toPitch - pitch;
        return Math.hypot(dy, dp);
    }

    /** The look one tick on from {@code yaw, pitch} toward {@code toYaw, toPitch}: yaw, pitch. */
    static float[] turn(float yaw, float pitch, float toYaw, float toPitch) {
        double dy = Math.IEEEremainder(toYaw - yaw, 2 * Math.PI);
        double dp = toPitch - pitch;
        double left = Math.hypot(dy, dp);
        if (left < 1e-6) {
            return new float[] {toYaw, toPitch};
        }
        double step = Math.min(left, Math.max(LEAST, Math.min(MOST, left * SHARE)));
        double r = step / left;
        float y = (float) Math.IEEEremainder(yaw + dy * r, 2 * Math.PI);
        return new float[] {y, (float) (pitch + dp * r)};
    }

    /**
     * The tick's controls with the head turned a tick toward the look wanted. With
     * {@code carryKeys}, the keys are turned from the pilot's heading ({@code wanted.yaw}) into the
     * head's: the same way over the ground, whatever the head does. Without, they stay as they are,
     * for the head's own heading (on a ladder forward is up, whichever way the head is).
     */
    static Controls follow(BodyState s, Controls wanted, float lookYaw, float lookPitch, boolean carryKeys) {
        float[] look = turn(s.yaw, s.pitch, lookYaw, lookPitch);
        double f = wanted.forward();
        double st = wanted.strafe();
        if (carryKeys && (Math.abs(f) > 1e-9 || Math.abs(st) > 1e-9)) {
            double d = Math.IEEEremainder(wanted.yaw() - look[0], 2 * Math.PI);
            double cos = Math.cos(d);
            double sin = Math.sin(d);
            double nf = f * cos + st * sin;
            double ns = -f * sin + st * cos;
            f = Math.abs(nf) < 1e-9 ? 0 : nf;
            st = Math.abs(ns) < 1e-9 ? 0 : ns;
        }
        return new Controls(f, st, look[0], look[1], wanted.jump(), wanted.crouch(), wanted.sprint(), wanted.walk(),
                wanted.fly());
    }
}
