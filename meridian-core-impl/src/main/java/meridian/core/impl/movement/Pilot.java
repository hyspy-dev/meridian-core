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
 *
 * <p>Where the head looks is its own ({@link #lookYaw}): along the way, and ahead of the point when
 * the next move's is near, so it starts turning before the corner. {@link Neck} turns it there a
 * tick at a time and carries the keys along.
 */
final class Pilot {

    private static final double ARRIVE = 0.08;
    private static final double PASS = 0.45;
    /** Nearer than this the way to the point is not taken as the move's heading: the aim swings. */
    private static final double HEADING_FROM = 0.5;
    /** A point gone past by no more than this is backed onto, not turned round to. */
    private static final double BACK_UP_TO = 1.5;
    /**
     * A point gone past onto the next move's way - on its level, no further than this off its line -
     * is passed, not backed onto: a drop at a run lands a block on, and a player runs on from there.
     */
    private static final double LANE = 0.45;
    /** A rise a jump takes instead of the step-up: from half a block up to a block. */
    private static final double RISE_LOW = 0.5;
    private static final double RISE_HIGH = 1.05;
    /** The feet clear a rise by this much in the jump taken at it. */
    private static final double RISE_CLEAR = 0.05;
    /** Taken up to this much before the body would just clear the edge. */
    private static final double RISE_EARLY = 0.35;
    /** The jump at a rise is held until the feet are this much over its top, then let go: a hop. */
    private static final double RISE_HOLD_OVER = 0.2;
    /** Under this speed toward a rise the step-up takes it: a standing jump gains nothing. */
    private static final double RISE_SPEED = 2.0;
    /** The way goes down through the water by more than this: dive. Less, the body floats. */
    private static final double DIVE = 0.6;
    /** Ticks without getting nearer before the move is given up. */
    private static final int STUCK_TICKS = 75;
    /** The longest a move may take. */
    private static final int MAX_TICKS = 60 * 30;
    /** The head looks at the way this far ahead of the body, round the corner into the next move. */
    static final double LOOK_AHEAD = 2.0;
    /**
     * A walk shorter than this, with no move after it, leaves the head where it is: a step to stand
     * on a point, taken sideways or back as a player does, the look kept on what it is on.
     */
    static final double STILL_UNDER = 1.2;
    /** While the body goes somewhere the head keeps its pitch, brought no lower than this... */
    static final double PITCH_LOW = Math.toRadians(-35);
    /** ... and no higher than this: it looks where it looked, not up at the horizon. */
    static final double PITCH_HIGH = Math.toRadians(15);

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
    /** Jumped at the next move's rise: this move is over once the body is off the ground. */
    private boolean jumpedAhead;
    /** Which way the move goes, across: toward the point while it was still ahead; 0, 0 before. */
    private double headX;
    private double headZ;
    /** Gone past the point this tick: facing on the way the move went, backing onto it. */
    private boolean back;
    private MoveResult result;
    /** The next move's point, when one follows, and what kind of move it is. */
    private Vec3 ahead;
    private Kind aheadKind;
    /** How far the move went across, at its first tick; {@code NaN} before it. */
    private double length = Double.NaN;
    /** Where the head wants to look this tick. */
    private float lookYaw;
    private float lookPitch;

    Pilot(Move move, Physics physics, boolean through) {
        this.move = move;
        this.physics = physics;
        this.through = through;
    }

    void through(boolean through) {
        this.through = through;
    }

    /** The point of the move after this one, or {@code null}. */
    void ahead(Vec3 ahead) {
        this.ahead = ahead;
    }

    /** The next move: its point and its kind ({@code null}: none). */
    void ahead(Move next) {
        this.ahead = next == null ? null : next.to();
        this.aheadKind = next == null ? null : next.kind();
    }

    /** A short step on its own: the head stays as it is. */
    private boolean still() {
        return move.kind() == Kind.WALK && ahead == null && length < STILL_UNDER;
    }

    /** Where the head wants to look, as of the last {@link #next}: yaw. */
    float lookYaw() {
        return lookYaw;
    }

    /** ... and pitch: as it was, within a band - looking neither at the feet nor at the sky. */
    float lookPitch() {
        return lookPitch;
    }

    /**
     * Whether the keys go along the ground whichever way the head looks ({@link Neck#follow}). Not
     * on a ladder, where forward is up.
     */
    boolean carriesKeys() {
        return move.kind() != Kind.CLIMB;
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
        // Gone past the point (a drop carries on): back onto it, as a player does, looking on the
        // way the move went - not turned round to it. Seen from a game's body, the camera.
        back = backs(move.kind()) && (headX != 0 || headZ != 0) && dx * headX + dz * headZ < 0
                && flat < BACK_UP_TO;
        if (!back && flat > HEADING_FROM) {
            headX = dx / flat;
            headZ = dz / flat;
        }
        float yaw = back ? meridian.core.api.Look.yawAlong(-dx, -dz)
                : flat > 1e-3 ? meridian.core.api.Look.yawAlong(dx, dz) : s.yaw;
        if (Double.isNaN(length)) {
            length = flat;
        }
        if (still()) {
            lookYaw = s.yaw;
            lookPitch = s.pitch;
        } else {
            lookYaw = back ? yaw : look(s, to, flat, yaw);
            lookPitch = (float) Math.max(PITCH_LOW, Math.min(PITCH_HIGH, s.pitch));
        }
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
        if (better || s.mantling) {
            // Held still on a pull-up is not stuck: the count starts again once it is up.
            sinceBetter = 0;
        } else if (++sinceBetter > STUCK_TICKS) {
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
            case WALK -> new Controls(key(forward(s, flat)), 0, yaw, 0, riseJump(s) || heldJump(s), false,
                    sprint && !back, walk, keepFlying(s));
            case DROP -> new Controls(key(forward(s, flat)), 0, yaw, 0, false, false, sprint && s.onGround && !back,
                    walk, keepFlying(s));
            case JUMP -> jump(s, yaw, flat, dy, sprint, walk);
            case LEAP -> leap(s, yaw, flat, sprint || gait == Gait.RUN);
            case MANTLE -> mantle(s, yaw, flat, dy);
            case SWIM -> swim(s, yaw, flat, dy);
            case CLIMB -> climb(s, yaw, flat, dy);
            // Down to a point is down until it - onto the ground under it, where the trip ends standing.
            case FLY -> new Controls(flat > 0.3 ? Math.min(1, flat) : 0, 0, yaw, 0, dy > 0.25,
                    dy < -0.25 || (flat < 0.3 && dy < -0.02 && !s.onGround), sprint, false, true);
        };
    }

    /** A move afoot keeps a flight under way only while flight is switched on: off, the body comes down. */
    private boolean keepFlying(BodyState s) {
        return s.flying && physics.flightOn.getAsBoolean();
    }

    /**
     * Where the head looks: at the way {@link #LOOK_AHEAD} ahead of the body - toward the point, and
     * once that is nearer, as far again along the way to the next one.
     */
    private float look(BodyState s, Vec3 to, double flat, float yaw) {
        if (ahead == null || flat >= LOOK_AHEAD || move.kind() == Kind.CLIMB) {
            return yaw;
        }
        double nx = ahead.x() - to.x();
        double nz = ahead.z() - to.z();
        double next = Math.hypot(nx, nz);
        if (next < 1e-3) {
            return yaw;
        }
        double on = Math.min(LOOK_AHEAD - flat, next);
        double px = to.x() + nx / next * on - s.x;
        double pz = to.z() + nz / next * on - s.z;
        return Math.hypot(px, pz) < 1e-3 ? yaw : meridian.core.api.Look.yawAlong(px, pz);
    }

    /** The moves over ground and through the air, where a point gone past is backed onto. */
    private static boolean backs(Kind kind) {
        return kind == Kind.WALK || kind == Kind.DROP || kind == Kind.JUMP || kind == Kind.LEAP
                || kind == Kind.MANTLE;
    }

    /**
     * Gone through the point onto the next move's way: past it, on the next point's level and within
     * {@link #LANE} of the line to it - a drop at a run landing a block on, a jump over a single
     * block coming down behind it. Then it is passed, as a player runs on; backing onto it would
     * only lose the speed.
     */
    private boolean onward(BodyState s) {
        if (!through || ahead == null || aheadKind != Kind.WALK || Math.abs(ahead.y() - s.y) > 0.6) {
            return false;
        }
        Vec3 to = move.to();
        double nx = ahead.x() - to.x();
        double nz = ahead.z() - to.z();
        double len = Math.hypot(nx, nz);
        if (len < 0.5) {
            return false;
        }
        double ox = s.x - to.x();
        double oz = s.z - to.z();
        double along = (ox * nx + oz * nz) / len;
        double side = Math.abs(ox * nz - oz * nx) / len;
        return along > 0 && along < len + PASS && side < LANE;
    }

    /**
     * How much further than the point the body may come down: the next move's way, when it goes on
     * straight at the point's level - landing anywhere along it is passing the point (see
     * {@link #onward}). 0 otherwise: come down on the point.
     */
    private double onwardRoom(double ux, double uz) {
        if (!through || ahead == null || aheadKind != Kind.WALK) {
            return 0;
        }
        Vec3 to = move.to();
        double nx = ahead.x() - to.x();
        double nz = ahead.z() - to.z();
        double len = Math.hypot(nx, nz);
        if (len < 0.5 || Math.abs(ahead.y() - to.y()) > 0.6 || (nx * ux + nz * uz) / len < 0.9) {
            return 0;
        }
        return len;
    }

    /**
     * Jump now, at a rise of up to a block ahead - this move's point or the next one's - instead of
     * walking into it: the step-up takes it, but the game takes nearly all the speed for that (the
     * auto-jump's speed loss, 95% at a run for 0.2 s); a player jumps a little before it. The jump
     * goes when the body, at its speed toward the rise, would just clear the edge, and a bit before.
     */
    private boolean riseJump(BodyState s) {
        if (!s.onGround || jumped || back) {
            return false;
        }
        Vec3 to = move.to();
        Vec3 rise = null;
        if (to.y() - s.y >= RISE_LOW && to.y() - s.y <= RISE_HIGH) {
            rise = to;
        } else if (through && ahead != null && aheadKind == Kind.WALK && Math.abs(to.y() - s.y) < RISE_LOW
                && ahead.y() - to.y() >= RISE_LOW && ahead.y() - to.y() <= RISE_HIGH) {
            rise = ahead;
        }
        if (rise == null) {
            return false;
        }
        Surroundings.BodyShape shape = physics.surroundings().shape();
        double hx = shape == null ? 0.325 : shape.halfX();
        double hz = shape == null ? 0.325 : shape.halfZ();
        // The rise's cell, as a box across: how far the body's edge is from it, and how fast it goes there.
        double cx = Math.floor(rise.x()) + 0.5;
        double cz = Math.floor(rise.z()) + 0.5;
        double gx = Math.max(Math.abs(cx - s.x) - 0.5 - hx, 0);
        double gz = Math.max(Math.abs(cz - s.z) - 0.5 - hz, 0);
        double gap = Math.hypot(gx, gz);
        double dx = cx - s.x;
        double dz = cz - s.z;
        double d = Math.hypot(dx, dz);
        double v = d < 1e-6 ? 0 : (s.vx * dx + s.vz * dz) / d;
        if (v < RISE_SPEED) {
            return false;
        }
        double t = riseTime(rise.y() - s.y + RISE_CLEAR);
        if (Double.isNaN(t)) {
            return false;
        }
        double clearAt = v * t;
        // Past where it would just clear: too late, the edge is hit on the way up - the step-up then.
        if (gap > clearAt + RISE_EARLY || gap < clearAt) {
            return false;
        }
        jumped = true;
        jumpedAhead = rise != to;
        return true;
    }

    /**
     * The jump at a rise held until the feet are over its top (this move's point): then let go, as
     * a player hops onto a block - held to its top it goes two blocks up and comes down late.
     */
    private boolean heldJump(BodyState s) {
        return !s.onGround && s.jumped && s.vy > 0 && s.y < move.to().y() + RISE_HOLD_OVER;
    }

    /** Seconds from a jump until the feet are {@code h} up, holding it; NaN when they never are. */
    private double riseTime(double h) {
        meridian.protocol.MovementSettings p = physics.surroundings().settings();
        if (p == null || p.jumpForce <= 0) {
            return Double.NaN;
        }
        double v = physics.fall(p.jumpForce, p, 1);
        double y = 0;
        for (int i = 1; i <= 60; i++) {
            y += v * Physics.DT;
            if (y >= h) {
                return i * Physics.DT;
            }
            if (v <= 0) {
                return Double.NaN;
            }
            v = physics.fall(v, p, 1);
        }
        return Double.NaN;
    }

    /**
     * Swimming to the point. Across, the body floats - no key up or down for a point a little above
     * or below it: jump with the eyes out of the water is a jump out of it, and down is a crouch,
     * which lowers the eyes. But the eyes are kept out of the water: the server takes the breath
     * at them, by the cell they are in. Down only where the way really goes deeper; up where it
     * goes up, out onto a bank or a level higher.
     */
    private Controls swim(BodyState s, float yaw, double flat, double dy) {
        if (s.onGround && !s.inFluid) {
            return new Controls(forward(s, flat), 0, yaw, 0, false, false, false, false, false);
        }
        Vec3 to = move.to();
        Terrain t = physics.terrain();
        int px = (int) Math.floor(to.x());
        int py = (int) Math.floor(to.y() + 0.5);
        int pz = (int) Math.floor(to.z());
        boolean wet = t.fluidAt(px, py, pz) != null;
        // Down only to a point under the top of the water: one at its top is floated to, also from
        // above - coming into the water off a bank is not a dive.
        boolean dive = wet && t.fluidAt(px, py + 1, pz) != null && dy < -DIVE;
        Surroundings.BodyShape shape = physics.surroundings().shape();
        double eyes = shape == null ? 1.6 : shape.eyeHeight();
        boolean breathe = !dive && t.fluidAt((int) Math.floor(s.x), (int) Math.floor(s.y + eyes),
                (int) Math.floor(s.z)) != null;
        boolean up = breathe || (!wet && dy > 0.3) || (wet && dy > DIVE);
        boolean down = dive && s.inFluid;
        return new Controls(flat > 0.3 ? 1 : 0, 0, yaw, 0, up, down, false, false, false);
    }

    /** The forward key toward the point: the back key while backing onto it. */
    private double key(double towardPoint) {
        return back ? -towardPoint : towardPoint;
    }

    private boolean arrived(BodyState s, double flat, double dy, double dist) {
        double near = through ? PASS : ARRIVE;
        if (jumpedAhead && !s.onGround) {
            return true;                    // off at the next move's rise: that move takes the flight
        }
        if (backs(move.kind()) && move.kind() != Kind.MANTLE && s.onGround && onward(s)) {
            return true;
        }
        return switch (move.kind()) {
            // The last of a flight is over standing on the point - not hovering over it: a trip ends on its feet.
            case FLY -> through ? dist < 0.6 : dist < 0.35 && (dy > -0.05 || s.onGround);
            // In the water near is enough; out of it, onto a bank, the body has to have come down.
            // On a bank it has to have stopped, as on any ground: a jump out lands still moving.
            case SWIM -> (s.inFluid && !s.onGround && dist < 0.6) || (s.onGround && flat < near && Math.abs(dy) < 0.6
                    && (through || s.inFluid || Math.hypot(s.vx, s.vz) < 0.4));
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
     * now stops short of the point; tap back when letting go would carry past it. In the air it
     * steers to come down on the point, going through it or not: a drop at a run carries a block
     * past it otherwise.
     */
    private double forward(BodyState s, double flat) {
        if (!s.onGround) {
            return airForward(s, flat);
        }
        if (through) {
            return 1;
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
        // Coming down past the point along a way that goes on straight is passing it: no braking.
        double room = onwardRoom(ux, uz);
        if (flat + room < carry - 0.1) {
            return -1;
        }
        return room > 0 ? 1 : 0;
    }

    private Controls jump(BodyState s, float yaw, double flat, double dy, boolean sprint, boolean walk) {
        // Jump once the target is within a jump's reach; hold it until the top.
        boolean press = s.onGround && !jumped && flat < 1.4;
        if (press) {
            jumped = true;
        }
        boolean hold = !s.onGround && s.vy > 0;
        double f = leftGround && s.onGround ? forward(s, flat) : s.onGround ? 1 : airForward(s, flat);
        return new Controls(key(f), 0, yaw, 0, press || hold, false, sprint && !back, walk, false);
    }

    private Controls leap(BodyState s, float yaw, double flat, boolean sprint) {
        // At the last bit of ground before the gap: jump.
        boolean edge = s.onGround && !jumped && physics.edgeAhead(s, yaw);
        if (edge) {
            jumped = true;
        }
        boolean hold = !s.onGround && s.vy > 0;
        double f = leftGround && s.onGround ? forward(s, flat) : s.onGround ? 1 : airForward(s, flat);
        // Sprint held through the flight, as a player holds it: let go in the air, the speed falls
        // to a run's within the sprint's easing out, and a sprinting leap comes down a block short.
        return new Controls(key(f), 0, yaw, 0, edge || hold, false, sprint && !back, false, false);
    }

    private Controls mantle(BodyState s, float yaw, double flat, double dy) {
        if (s.mantling) {
            // Pulled up: held still for a second, forward still held - as a player does (recorded),
            // and the others are shown a pull-up, not a player standing on the ledge.
            return new Controls(1, 0, yaw, 0, false, false, false, false, false);
        }
        if (leftGround && s.onGround && Math.abs(move.to().y() - s.y) < 0.3) {
            // Up: onto the point.
            return new Controls(key(forward(s, flat)), 0, yaw, 0, false, false, false, false, false);
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
