package meridian.core.impl.movement;

import meridian.core.api.Movement;
import meridian.protocol.packets.setup.ClientFeature;
import meridian.protocol.FlyMode;
import meridian.protocol.MovementSettings;

/**
 * What the body can do, worked out by the body's own physics on open flat ground: how high a jump
 * goes, how far a running jump carries, how far a fall stays below the speed that hurts. A path is
 * planned with the same numbers the body moves by.
 */
final class BodySettings {

    /** The highest ledge pulled up onto: three blocks, as the game's players know it. Not fitted. */
    static final double MANTLE_MAX_HEIGHT = 3.0;

    private final Physics physics;
    private MovementSettings last;
    private Surroundings.BodyShape lastShape;
    private FlyMode lastFly;
    private boolean lastMantle;
    private Movement.Capabilities cached;

    BodySettings(Physics physics) {
        this.physics = physics;
    }

    synchronized Movement.Capabilities capabilities() {
        Surroundings around = physics.surroundings();
        MovementSettings p = around.settings();
        Surroundings.BodyShape shape = around.shape();
        FlyMode fly = around.flyMode();
        boolean mantle = around.feature(ClientFeature.Mantling);
        if (cached != null && p == last && shape.equals(lastShape) && fly == lastFly && mantle == lastMantle) {
            return cached;
        }
        last = p;
        lastShape = shape;
        lastFly = fly;
        lastMantle = mantle;
        cached = p == null ? none(shape) : compute(p, shape, fly, mantle);
        return cached;
    }

    private static Movement.Capabilities none(Surroundings.BodyShape shape) {
        return new Movement.Capabilities(shape.width(), shape.height(), shape.eyeHeight(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0);
    }

    private Movement.Capabilities compute(MovementSettings p, Surroundings.BodyShape shape, FlyMode fly,
                                          boolean mantle) {
        double walk = p.baseSpeed * p.forwardWalkSpeedMultiplier;
        double run = p.baseSpeed * p.forwardRunSpeedMultiplier;
        double sprint = run * p.forwardSprintSpeedMultiplier;
        double apex = apex(p);
        // Onto a block the feet clear at the top with room to come down on it.
        double jump = Math.max(Tuning.STEP_HEIGHT, apex - 0.3);
        double safe = safeDrop(p);
        double runLeap = leap(p, run, shape);
        double sprintLeap = leap(p, sprint, shape);
        double swim = run * 0.6;
        double climb = p.climbSpeed * Tuning.CLIMB_SPEED_PER_TICK;
        double flySpeed = fly == FlyMode.Disabled ? 0 : p.horizontalFlySpeed;
        return new Movement.Capabilities(shape.width(), shape.height(), shape.eyeHeight(), Tuning.STEP_HEIGHT, jump,
                mantle ? MANTLE_MAX_HEIGHT : 0, safe, walk, run, sprint, runLeap, sprintLeap, swim, climb, flySpeed);
    }

    /** The top of a standing jump, above the feet, tick by tick as the body rises. */
    private double apex(MovementSettings p) {
        double vy = p.jumpForce;
        double y = 0;
        double top = 0;
        for (int i = 0; i < 600 && (vy > 0 || y > 0); i++) {
            vy = physics.fall(vy, p, 1);
            y += vy * Physics.DT;
            top = Math.max(top, y);
        }
        return top;
    }

    /**
     * The longest drop whose last falling tick stays at or under the speed that hurts - the speed
     * the server takes the damage from.
     */
    private double safeDrop(MovementSettings p) {
        double hurt = p.minFallSpeedToEngageRoll > 0 ? p.minFallSpeedToEngageRoll : 21;
        double vy = 0;
        double fell = 0;
        for (int i = 0; i < 6000; i++) {
            double nv = physics.fall(vy, p, 1);
            if (-nv > hurt) {
                return fell;
            }
            vy = nv;
            fell -= vy * Physics.DT;
        }
        return fell;
    }

    /**
     * The widest level gap a jump at speed {@code v} clears: off at the last ground under the
     * body, down onto the first ground across - the body is a width wide either side.
     */
    private double leap(MovementSettings p, double v, Surroundings.BodyShape shape) {
        double vy = p.jumpForce;
        double y = 0;
        double[] h = {v, 0};
        double[] wish = {v, 0};
        double x = 0;
        for (int i = 0; i < 600; i++) {
            Physics.airAlong(h, v, p);
            vy = physics.fall(vy, p, 1);
            y += vy * Physics.DT;
            x += h[0] * Physics.DT;
            if (y <= 0 && vy < 0) {
                break;
            }
        }
        return Math.max(0, x + shape.width() - 0.2);
    }
}
