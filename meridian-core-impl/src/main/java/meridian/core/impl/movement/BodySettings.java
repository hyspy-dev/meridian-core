package meridian.core.impl.movement;

import meridian.core.api.Movement;
import meridian.protocol.packets.setup.ClientFeature;
import meridian.protocol.MovementSettings;

/**
 * What the body can do, worked out by the body's own physics on open flat ground: how high a jump
 * goes, how far a running jump carries, how far a fall stays below the speed that hurts. A path is
 * planned with the same numbers the body moves by.
 */
final class BodySettings {

    private final Physics physics;
    private MovementSettings last;
    private Surroundings.BodyShape lastShape;
    private FlyMode lastFly;
    private boolean lastMantle;
    private Movement.Capabilities cached;
    private RunUps cachedRunUps;

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
        cachedRunUps = p == null ? null : runUps(p, shape);
        return cached;
    }

    /** How a sprinting jump flies after each run-up ({@link RunUps}); {@code null} before the settings. */
    synchronized RunUps runUps() {
        capabilities();
        return cachedRunUps;
    }

    /**
     * Each run-up of 0 to {@link RunUps#MOST} blocks, from a standstill in the middle of a block: the
     * ground's law with forward and sprint held (the asked-for speed rising by acceleration·W²/base,
     * the velocity keeping {@code KEY_KEEP} and gaining the rest, the sprint easing in over six
     * ticks) until the floor ends a tick and a half ahead - as the pilot jumps - then the jump and
     * the air's law, sprint still held, until the feet are 4 under the edge.
     */
    private RunUps runUps(MovementSettings p, Surroundings.BodyShape shape) {
        int n = RunUps.MOST + 1;
        double[][] xs = new double[n][];
        double[][] ys = new double[n][];
        double base = Math.max(p.baseSpeed, 1e-3);
        double runCap = base * p.forwardRunSpeedMultiplier;
        double sprintMul = p.forwardSprintSpeedMultiplier > 0 ? p.forwardSprintSpeedMultiplier : 1;
        double acc = Math.max(p.acceleration, 1e-3);
        double keep = Tuning.KEY_KEEP;
        double gain = 1 - keep;
        double halfX = shape.halfX();
        for (int k = 0; k < n; k++) {
            double edge = k + 0.5;
            double v = 0;
            double ramp = 0;
            double x = 0;
            int ticks = 0;
            while (x < edge + halfX - Math.max(v, 1) * Physics.DT * 1.5 && ticks < 600) {
                double e = Math.min(1.0, (double) ticks / Tuning.SPRINT_EASE_TICKS);
                double cap = runCap * (1 + (sprintMul - 1) * e * e * e);
                ramp = Math.min(cap, ramp + acc * cap * cap / base);
                double along = keep * v;
                if (along >= cap) {
                    v *= keep;
                } else {
                    double add = Math.min(gain * ramp, cap - along);
                    v = keep * v + add;
                    ramp = add / gain;
                }
                x += v * Physics.DT;
                ticks++;
            }
            java.util.List<double[]> flight = new java.util.ArrayList<>();
            double vy = p.jumpForce;
            double y = 0;
            double[] h = {v, 0};
            flight.add(new double[] {x - edge, 0});
            for (int i = 0; i < 600; i++) {
                Physics.airAlong(h, runCap * sprintMul, p);
                vy = physics.fall(vy, p, 1);
                y += vy * Physics.DT;
                x += h[0] * Physics.DT;
                flight.add(new double[] {x - edge, y});
                if (y < -4) {
                    break;
                }
            }
            xs[k] = new double[flight.size()];
            ys[k] = new double[flight.size()];
            for (int i = 0; i < flight.size(); i++) {
                xs[k][i] = flight.get(i)[0];
                ys[k][i] = flight.get(i)[1];
            }
        }
        return new RunUps(xs, ys, halfX);
    }

    private static Movement.Capabilities none(Surroundings.BodyShape shape) {
        return new Movement.Capabilities(shape.width(), shape.height(), shape.eyeHeight(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, Physics.GRAVITY);
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
                mantle ? Tuning.MANTLE_MAX_HEIGHT : 0, safe, walk, run, sprint, runLeap, sprintLeap, swim, climb, flySpeed,
                Physics.GRAVITY);
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
