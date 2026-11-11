package meridian.core.impl.movement;

import java.util.ArrayList;
import java.util.List;
import meridian.protocol.VelocityConfig;
import meridian.protocol.VelocityThresholdStyle;

/**
 * What the server pushes the body with ({@code ChangeVelocity}): knockback, a launch pad, being
 * shoved by a crowd. The client keeps a push that comes with a {@link VelocityConfig} apart from its
 * own movement, as a velocity of its own that dies away every client tick by the config's
 * resistance (the server's copy of this, "matches client", is
 * {@code MotionControllerBase.dampAppliedVelocities}); one without a config goes into the body's own
 * velocity, scaled down by the ground's friction and the body's {@code velocityResistance}
 * ({@code MotionControllerBase:1316}, "client null-path parity").
 */
final class Pushes {

    /** A push being carried: what is left of it, how it started, and how it dies away. */
    static final class Push {
        double x;
        double y;
        double z;
        final boolean startedUp;
        final VelocityConfig config;

        Push(double x, double y, double z, VelocityConfig config) {
            this(x, y, z, y > 0, config);
        }

        private Push(double x, double y, double z, boolean startedUp, VelocityConfig config) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.startedUp = startedUp;
            this.config = config;
        }

        Push copy() {
            return new Push(x, y, z, startedUp, config);
        }
    }

    final List<Push> carried = new ArrayList<>();

    /** Takes in a push with a config: a channel of its own; a set replaces the channels. */
    void take(float x, float y, float z, boolean set, VelocityConfig config) {
        if (set) {
            carried.clear();
        }
        carried.add(new Push(x, y, z, config));
    }

    /**
     * One client tick of dying away. The ground's resistance applies unless the body was falling
     * the tick before (recorded: the ground's on the take-off tick, the air's on the landing one),
     * and when gravity does not (swimming, climbing, flying, pulling up), when y dies away too.
     */
    void decay(boolean wasFalling, boolean affectedByGravity) {
        boolean ground = !wasFalling || !affectedByGravity;
        for (Push p : carried) {
            VelocityConfig c = p.config;
            float min = ground ? c.groundResistance : c.airResistance;
            float max = ground ? c.groundResistanceMax : c.airResistanceMax;
            double r = min;
            if (max >= 0) {
                if (c.style == VelocityThresholdStyle.Exp) {
                    double len = p.x * p.x + p.y * p.y + p.z * p.z;
                    double t2 = (double) c.threshold * c.threshold;
                    if (len < t2) {
                        double m = len / t2;
                        r = min * m + max * (1 - m);
                    }
                } else {
                    double len = Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
                    if (len < c.threshold) {
                        double m = len / c.threshold;
                        r = min * m + max * (1 - m);
                    }
                }
            }
            p.x *= r;
            p.z *= r;
            if (!affectedByGravity) {
                p.y *= r;
            }
        }
        carried.removeIf(p -> p.x * p.x + p.y * p.y + p.z * p.z < 0.001);
    }

    /** On the ground, a push's fall is over once it no longer lifts the body. */
    void landed(double ownVy) {
        for (Push p : carried) {
            if (p.y + ownVy <= 0 || p.y < 0) {
                p.y = 0;
            }
        }
    }

    boolean anyStartedUp() {
        for (Push p : carried) {
            if (p.startedUp) {
                return true;
            }
        }
        return false;
    }

    double sumX() {
        double s = 0;
        for (Push p : carried) {
            s += p.x;
        }
        return s;
    }

    double sumY() {
        double s = 0;
        for (Push p : carried) {
            s += p.y;
        }
        return s;
    }

    double sumZ() {
        double s = 0;
        for (Push p : carried) {
            s += p.z;
        }
        return s;
    }

    void clear() {
        carried.clear();
    }
}
