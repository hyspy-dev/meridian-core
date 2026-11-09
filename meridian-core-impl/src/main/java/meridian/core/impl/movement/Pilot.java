package meridian.core.impl.movement;

import meridian.core.api.Movement.Controls;
import meridian.core.api.Movement.Gait;
import meridian.core.api.Movement.Kind;
import meridian.core.api.Movement.Move;
import meridian.core.api.Movement.MoveResult;
import meridian.core.api.Vec3;

/**
 * Plays one move the way a player would: every tick it looks at the body and picks the keys - turn
 * toward the target, run, jump at the edge, hold on to the ledge and pull up, let go of forward in
 * time to stop on the spot. Closed-loop: a push, a step it did not plan for or a slower ground just
 * changes the next tick's keys. It gives up when the body stops getting nearer.
 */
final class Pilot {

    private static final double ARRIVE = 0.08;
    private static final double PASS = 0.45;
    /** Ticks without getting nearer before the move is given up. */
    private static final int STUCK_TICKS = 75;
    /** The longest a move may take. */
    private static final int MAX_TICKS = 60 * 30;

    private final Move move;
    private final Physics physics;
    /** Another move follows: go through the point instead of stopping on it. */
    private boolean through;

    private int ticks;
    private int sinceBetter;
    private double bestFlat = Double.MAX_VALUE;
    private double bestRise = Double.MAX_VALUE;
    private boolean leftGround;
    private boolean jumped;
    private MoveResult result;

    Pilot(Move move, Physics physics, boolean through) {
        this.move = move;
        this.physics = physics;
        this.through = through;
    }

    void through(boolean through) {
        this.through = through;
    }

    Move move() {
        return move;
    }

    MoveResult result() {
        return result;
    }

    /** The keys for this tick, or {@code null} when the move is over and {@link #result()} says how. */
    Controls next(BodyState s) {
        if (result != null) {
            return null;
        }
        Vec3 to = move.to();
        double dx = to.x() - s.x;
        double dz = to.z() - s.z;
        double dy = to.y() - s.y;
        double flat = Math.hypot(dx, dz);
        double dist = Math.sqrt(flat * flat + dy * dy);
        float yaw = flat > 1e-3 ? meridian.core.api.Look.yawAlong(dx, dz) : s.yaw;
        if (!s.onGround) {
            leftGround = true;
        }

        if (arrived(s, flat, dy, dist)) {
            return done(MoveResult.Status.ARRIVED, s);
        }
        if (++ticks > MAX_TICKS) {
            return done(MoveResult.Status.BLOCKED, s);
        }
        // Getting nearer across, or up or down: either counts.
        boolean better = false;
        if (flat < bestFlat - 0.02) {
            bestFlat = flat;
            better = true;
        }
        if (Math.abs(dy) < bestRise - 0.02) {
            bestRise = Math.abs(dy);
            better = true;
        }
        if (better) {
            sinceBetter = 0;
        } else if (++sinceBetter > STUCK_TICKS && !s.mantling) {
            return done(MoveResult.Status.BLOCKED, s);
        }
        // A move that needed the ground and landed somewhere else is over.
        if ((move.kind() == Kind.JUMP || move.kind() == Kind.DROP || move.kind() == Kind.LEAP) && leftGround
                && s.onGround && Math.abs(dy) > 0.6) {
            return done(MoveResult.Status.BLOCKED, s);
        }

        Gait gait = move.gait() == null ? Gait.RUN : move.gait();
        boolean sprint = gait == Gait.SPRINT;
        boolean walk = gait == Gait.WALK;
        return switch (move.kind()) {
            case WALK -> new Controls(forward(s, flat), 0, yaw, 0, false, false, sprint, walk, s.flying);
            case DROP -> new Controls(forward(s, flat), 0, yaw, 0, false, false, sprint && s.onGround, walk, s.flying);
            case JUMP -> jump(s, yaw, flat, dy, sprint, walk);
            case LEAP -> leap(s, yaw, flat, sprint || gait == Gait.RUN);
            case MANTLE -> mantle(s, yaw, flat, dy);
            case SWIM -> s.onGround && !s.inFluid
                    ? new Controls(forward(s, flat), 0, yaw, 0, false, false, false, false, false)
                    : new Controls(flat > 0.3 ? 1 : 0, 0, yaw, 0, dy > 0.3, dy < -0.3, false, false, false);
            case CLIMB -> climb(s, yaw, flat, dy);
            case FLY -> new Controls(flat > 0.3 ? Math.min(1, flat) : 0, 0, yaw, 0, dy > 0.25, dy < -0.25, sprint, false,
                    true);
        };
    }

    private boolean arrived(BodyState s, double flat, double dy, double dist) {
        double near = through ? PASS : ARRIVE;
        return switch (move.kind()) {
            case FLY -> dist < (through ? 0.6 : 0.35);
            // In the water near is enough; out of it, onto a bank, the body has to have come down.
            case SWIM -> (s.inFluid && !s.onGround && dist < 0.6) || (s.onGround && flat < near && Math.abs(dy) < 0.6);
            case CLIMB -> flat < Math.max(near, 0.2) && Math.abs(dy) < 0.3 && (s.onGround || s.climbing);
            case MANTLE -> !s.mantling && s.onGround && flat < near && Math.abs(dy) < 0.3
                    && (through || Math.hypot(s.vx, s.vz) < 0.4);
            default -> s.onGround && flat < near && Math.abs(dy) < 0.6
                    && (through || Math.hypot(s.vx, s.vz) < 0.4);
        };
    }

    /**
     * Forward, or not, or back, this tick, to stop on the point - the way a player does it. With
     * the key in, the speed goes to v' = 0.82·v + 0.18·S (S the asked-for speed one ramp step on);
     * let go, it dies by the ground's drag d, carrying v·dt·d/(1−d) more. Press while even a press
     * now stops short of the point; tap back when letting go would carry past it.
     */
    private double forward(BodyState s, double flat) {
        if (through) {
            return 1;
        }
        if (!s.onGround) {
            return airForward(s, flat);
        }
        meridian.protocol.MovementSettings p = physics.surroundings().settings();
        double d = physics.groundDrag(s);
        Vec3 to = move.to();
        double ux = flat > 1e-6 ? (to.x() - s.x) / flat : 0;
        double uz = flat > 1e-6 ? (to.z() - s.z) / flat : 0;
        double v = s.vx * ux + s.vz * uz;
        double base = p == null ? 5.5 : p.baseSpeed;
        double acc = p == null ? 0.1 : p.acceleration;
        double full = p == null ? 5.5 : base * (move.gait() == Gait.WALK ? p.forwardWalkSpeedMultiplier
                : p.forwardRunSpeedMultiplier * (move.gait() == Gait.SPRINT ? p.forwardSprintSpeedMultiplier : 1));
        double keep = Tuning.KEY_KEEP;
        double next = Math.min(full, s.ramp + acc * full * full / base);
        double pressed = keep * v + Math.min((1 - keep) * next, Math.max(0, full - keep * v));
        double carryPressed = pressed * Physics.DT / (1 - d);
        double carryLetGo = v * d * Physics.DT / (1 - d);
        if (carryPressed <= flat + 0.01) {
            return 1;
        }
        if (carryLetGo > flat + 0.08) {
            return -1;
        }
        return 0;
    }

    /**
     * In the air: forward when the body would come down short of the point, back when past it -
     * how far it carries is its speed toward the point times the time left until it is down at
     * the point's height.
     */
    private double airForward(BodyState s, double flat) {
        Vec3 to = move.to();
        double h = s.y - to.y();
        double vy = s.totalY();
        double g = Physics.GRAVITY;
        double disc = vy * vy + 2 * g * Math.max(h, 0);
        double t = h >= 0 ? (vy + Math.sqrt(disc)) / g : (vy > 0 ? 2 * vy / g : 0);
        double ux = flat > 1e-6 ? (to.x() - s.x) / flat : 0;
        double uz = flat > 1e-6 ? (to.z() - s.z) / flat : 0;
        double v = s.totalX() * ux + s.totalZ() * uz;
        double carry = v * t;
        if (flat > carry + 0.1) {
            return 1;
        }
        if (flat < carry - 0.1) {
            return -1;
        }
        return 0;
    }

    private Controls jump(BodyState s, float yaw, double flat, double dy, boolean sprint, boolean walk) {
        // Jump once the target is within a jump's reach; hold it until the top.
        boolean press = s.onGround && !jumped && flat < 1.4;
        if (press) {
            jumped = true;
        }
        boolean hold = !s.onGround && s.vy > 0;
        double f = leftGround && s.onGround ? forward(s, flat) : s.onGround ? 1 : airForward(s, flat);
        return new Controls(f, 0, yaw, 0, press || hold, false, sprint, walk, false);
    }

    private Controls leap(BodyState s, float yaw, double flat, boolean sprint) {
        // At the last bit of ground before the gap: jump.
        boolean edge = s.onGround && !jumped && physics.edgeAhead(s, yaw);
        if (edge) {
            jumped = true;
        }
        boolean hold = !s.onGround && s.vy > 0;
        double f = leftGround && s.onGround ? forward(s, flat) : s.onGround ? 1 : airForward(s, flat);
        return new Controls(f, 0, yaw, 0, edge || hold, false, sprint && s.onGround, false, false);
    }

    private Controls mantle(BodyState s, float yaw, double flat, double dy) {
        if (s.mantling) {
            // Pulled up: held still for a second.
            return new Controls(0, 0, yaw, 0, false, false, false, false, false);
        }
        if (leftGround && s.onGround && Math.abs(move.to().y() - s.y) < 0.3) {
            // Up: onto the point.
            return new Controls(forward(s, flat), 0, yaw, 0, false, false, false, false, false);
        }
        // Jump at the wall and keep moving into it: the hands take the ledge as the head passes it.
        boolean press = s.onGround && flat < 1.2;
        boolean hold = !s.onGround && s.vy > 0;
        return new Controls(1, 0, yaw, 0, press || hold, false, false, false, false);
    }

    private Controls climb(BodyState s, float yaw, double flat, double dy) {
        if (s.climbing || dy > 0.3 || dy < -0.3) {
            // Facing the climbable, forward climbs up and back climbs down; at the top forward
            // carries on over the edge. Jump would let go.
            boolean up = dy > 0.15;
            boolean down = dy < -0.15;
            double f = up ? 1 : down ? -1 : (flat > 0.1 ? 1 : 0);
            return new Controls(f, 0, yaw, 0, false, false, false, false, false);
        }
        return new Controls(flat > 0.1 ? forward(s, flat) : 0, 0, yaw, 0, false, false, false, false, false);
    }

    private Controls done(MoveResult.Status status, BodyState s) {
        result = new MoveResult(status, new Vec3(s.x, s.y, s.z));
        return null;
    }

    /** Ends the move from outside: stopped, or the server moved the player. */
    void interrupt(BodyState s) {
        if (result == null) {
            result = new MoveResult(MoveResult.Status.INTERRUPTED, new Vec3(s.x, s.y, s.z));
        }
    }
}
