package meridian.core.impl.movement;

import java.util.List;
import meridian.core.api.Movement.Controls;
import meridian.protocol.BlockMovementSettings;
import meridian.protocol.FlyMode;
import meridian.protocol.MovementEffects;
import meridian.protocol.MovementSettings;
import meridian.protocol.packets.setup.ClientFeature;

/**
 * One client tick of the player's body: the keys and the look in, the body moved, as the client
 * moves it. Every law here is fitted to a recording of the real client (08.11.2026) on top of the
 * numbers the server sends ({@code MovementSettings}, the blocks', the fluids'), and gravity and
 * how pushes die away are the server's own copies of the client's ({@code PhysicsMath},
 * {@code MotionControllerBase}). What only the client knows is in {@link Tuning}.
 *
 * <p>Pure: a state and controls in, the state changed. The live body and a prediction run the same
 * code. The laws are decided from where the body is at the start of the tick.
 */
final class Physics {

    static final double DT = 1.0 / 60.0;
    /** The server's gravity ({@code PhysicsConstants.GRAVITY_ACCELERATION}). */
    static final double GRAVITY = 32;
    /** Air density as the server takes it ({@code PhysicsMath.AIR_DENSITY}). */
    private static final double AIR_DENSITY = 0.001225;
    private static final double EPS = 1e-6;
    /** A block's drag when it sets none ({@code BlockMovementSettings} default). */
    static final double DEFAULT_DRAG = 0.82;

    private final Terrain terrain;
    private final Collider collider;
    private final Surroundings around;

    Physics(Terrain terrain, Surroundings around) {
        this.terrain = terrain;
        this.collider = new Collider(terrain);
        this.around = around;
    }

    Surroundings surroundings() {
        return around;
    }

    Terrain terrain() {
        return terrain;
    }

    /** What the ground and the blocks about the body are doing to it this tick. */
    record Place(BlockMovementSettings ground, double speedMul, double jumpMul, double terminalMul,
                 boolean climbable, double climbUpMul, double climbDownMul, double climbLateralMul,
                 double climbNx, double climbNz, double climbTop,
                 Surroundings.FluidMotion fluid, double fluidDepth, boolean bouncy, double bounce) {}

    /** The keys' wish this tick: the unit direction in the world and the cap of the speed they ask for. */
    private record Wish(double dx, double dz, double cap, boolean keys) {}

    // ------------------------------------------------------------------
    // The tick
    // ------------------------------------------------------------------

    /**
     * Moves the body one tick. {@code effects}: what the player's active effects do to movement.
     * Returns false when there are no settings yet and nothing moved.
     */
    boolean step(BodyState s, Controls c, Surroundings.EffectMotion effects) {
        MovementSettings p = around.settings();
        if (p == null) {
            return false;
        }
        Surroundings.BodyShape shape = around.shape();
        MovementEffects me = effects == null ? null : effects.movement();
        s.yaw = c.yaw();
        s.pitch = c.pitch();
        Controls k = restrict(c, me);
        Place place = sense(s, shape);
        double feet0 = s.y;
        double vy0 = s.totalY();
        boolean ground0 = s.onGround;

        // --- the keys ------------------------------------------------------------
        double tf = clamp(k.forward(), -1, 1);
        double ts = clamp(k.strafe(), -1, 1);
        s.keyF = approach(s.keyF, tf, Tuning.KEY_STEP);
        s.keyS = approach(s.keyS, ts, Tuning.KEY_STEP);
        // Let go of every key, the speed stops being asked for at once; the axes still ease out.
        boolean keys = Math.abs(tf) > EPS || Math.abs(ts) > EPS;

        // --- pulled up onto a ledge: still for a second ----------------------------
        if (s.mantleTicks > 0) {
            s.mantleTicks--;
            s.vx = s.vy = s.vz = 0;
            s.mvx = s.mvy = s.mvz = 0;
            s.mantling = true;
            s.onGround = false;
            if (s.mantleTicks == 0) {
                s.mantling = false;
                s.onGround = true;
                s.groundTicks = 1;
            }
            states(s, k, keys);
            return true;
        }

        // --- what the body is doing ------------------------------------------------
        FlyMode fly = around.flyMode();
        boolean wasFlying = s.flying;
        if (fly == FlyMode.Forced) {
            s.flying = true;
        } else if (fly == FlyMode.Disabled) {
            s.flying = false;
        } else if (k.fly() != s.flying) {
            s.flying = k.fly();
        }
        if (s.flying && !wasFlying) {
            s.flyRest = s.vy;
        }
        boolean wantCrouch = k.crouch() && !s.flying;
        double fullHeight = shape.height();
        double crouchHeight = shape.height() + shape.crouchOffset();
        // A slide starts on the crouch key's press, not on the crouching state coming back (a roll's end).
        boolean wasCrouching = s.crouchHeld;
        s.crouchHeld = wantCrouch;
        if (s.crouching && !wantCrouch && !roomFor(s, shape, fullHeight)) {
            s.forcedCrouching = true;
        } else {
            s.forcedCrouching = false;
            s.crouching = wantCrouch;
        }
        if (s.forcedCrouching) {
            s.crouching = true;
        }
        double height = s.crouching || s.sliding || s.rolling ? crouchHeight : fullHeight;

        s.inFluid = place.fluid() != null && place.fluidDepth() > EPS;
        s.wasSwimming = s.swimming;
        s.swimming = !s.flying && place.fluid() != null && place.fluidDepth() >= Tuning.SWIM_DEPTH;

        boolean pressedJump = k.jump() && !s.jumpHeld;
        s.jumpHeld = k.jump();
        if (pressedJump) {
            s.jumpBuffer = p.jumpBufferDuration;
        } else {
            s.jumpBuffer = Math.max(0, s.jumpBuffer - DT);
        }

        // Climbing: it starts against a climbable from the ground with forward, or in the air; it
        // goes on while the climbable is held; back onto the floor ends it.
        s.wasClimbing = s.climbing;
        boolean canClimb = place.climbable() && !s.flying && !s.swimming;
        if (s.wasClimbing) {
            s.climbing = canClimb && !(ground0 && k.forward() < 0);
        } else {
            s.climbing = canClimb && (!ground0 || k.forward() > 0);
        }
        if (s.climbing) {
            s.climbNx = place.climbNx();
            s.climbNz = place.climbNz();
        }

        // --- the wish ------------------------------------------------------------------
        boolean sprintKey = keys && k.sprint() && tf > 0 && !s.crouching && !s.swimming && !s.flying;
        if (sprintKey) {
            s.sprintTicks = s.sprintTicks < 0 ? 0 : s.sprintTicks + 1;
            s.sprintOutTicks = -1;
        } else if (s.sprintTicks >= 0) {
            s.sprintOutTicks = 1;
            s.sprintTicks = -1;
        } else if (s.sprintOutTicks >= 0) {
            s.sprintOutTicks = s.sprintOutTicks + 1 > Tuning.SPRINT_EASE_TICKS ? -1 : s.sprintOutTicks + 1;
        }
        s.sprinting = sprintKey;
        Wish ground = wish(s, k, p, place, effects, keys, false);
        Wish airWish = wish(s, k, p, place, effects, keys, true);
        if (keys) {
            turnBody(s);
        }

        // --- the ways of moving ----------------------------------------------------------
        if (s.wasClimbing && !s.climbing && !ground0 && !s.jumping) {
            // Off the top (or the side) of a climbable: one tick at the climb's own velocity,
            // straight into it and no gravity, which takes the body over the edge.
            s.vx = s.climbNx * p.baseSpeed;
            s.vz = s.climbNz * p.baseSpeed;
            s.vy = 0;
        } else if (s.flying) {
            fly(s, k, p, airWish);
        } else if (s.climbing) {
            climb(s, k, p, place);
        } else if (s.swimming) {
            swim(s, k, p, place, ground);
        } else if (ground0) {
            ground(s, k, p, place, ground, airWish, wasCrouching);
        } else {
            air(s, k, p, place, airWish, vy0);
        }

        // --- moving it through the blocks ------------------------------------------------
        expel(s, shape, height);
        s.mvx = s.totalX();
        s.mvy = s.totalY();
        s.mvz = s.totalZ();
        if (s.climbing) {
            reportClimb(s, k, p);
        }
        double vyBefore = s.totalY();
        double[] box = box(s, shape, height);
        double dx = s.totalX() * DT;
        double dy = s.totalY() * DT;
        double dz = s.totalZ() * DT;
        List<double[]> boxes = collider.boxesAround(box, dx, dy + (ground0 ? -0.05 : 0), dz);
        double[] start = box.clone();
        Collider.Moved m = Collider.move(box, dx, dy, dz, boxes);
        boolean stepped = false;
        if (m.horizontal() && !s.flying && !s.climbing) {
            double most = ground0 ? Tuning.STEP_HEIGHT : Tuning.AIR_STEP_HEIGHT;
            Collider.Moved up = stepUp(start, dx, dy, dz, shape, height, s, most);
            if (up != null) {
                stepped = true;
                m = up;
                System.arraycopy(start, 0, box, 0, 6);
                box[1] += m.dy();
                box[4] += m.dy();
                box[0] += m.dx();
                box[3] += m.dx();
                box[2] += m.dz();
                box[5] += m.dz();
                if (ground0 && m.dy() > Tuning.SLOW_STEP_ABOVE) {
                    // A whole block: the step costs speed for a while (AutoJumpObstacleSpeedLoss).
                    boolean sprint = s.sprinting;
                    s.slowMultiplier = 1 - (sprint ? p.autoJumpObstacleSprintSpeedLoss : p.autoJumpObstacleSpeedLoss);
                    double secs = sprint ? p.autoJumpObstacleSprintEffectDuration : p.autoJumpObstacleEffectDuration;
                    s.slowTicks = (int) Math.round(secs / DT);
                }
            }
        }
        double cx = (box[0] + box[3]) / 2;
        double cz = (box[2] + box[5]) / 2;
        if (!stepped && m.horizontal() && !ground0 && !s.flying && !s.climbing && !s.swimming
                && s.keyF > 0 && around.feature(ClientFeature.Mantling)
                && mantle(s, shape, fullHeight, m, feet0, dx, dz, cx, cz)) {
            states(s, k, keys);
            return true;
        }
        s.x = cx;
        s.y = box[1];
        s.z = cz;

        // A wall stops the body's own velocity; a push with a config carries on dying away by its
        // own resistance (recorded: a thrust against a wall kept decaying 4.21, 3.96, 3.72...).
        if (m.hitX()) {
            s.vx = 0;
        }
        if (m.hitZ()) {
            s.vz = 0;
        }
        double ownVyDown = s.vy;
        boolean landed = m.landed(dy);
        if (m.ceiling(dy)) {
            s.vy = Math.min(0, s.vy);
            for (Pushes.Push q : s.pushes.carried) {
                q.y = Math.min(0, q.y);
            }
        }
        s.onGround = landed || (dy <= EPS && floorUnder(s, shape, height)) || (stepped && ground0);
        if (s.onGround && !ground0) {
            land(s, k, p, place, vyBefore, ground);
        }
        if (s.onGround) {
            if (ground0) {
                s.groundTicks++;
            }
            s.airTicks = 0;
            s.pushes.landed(Math.min(ownVyDown, s.vy));
            if (s.vy < 0) {
                s.vy = 0;
            }
        } else {
            if (ground0) {
                s.airTicks = 0;
                s.takeoffY = feet0;
                s.airKeyTicks = keys ? -1 : 0;
                if (!s.jumping) {
                    s.jumped = false;
                    s.airMul = 1;
                }
            }
            s.airTicks++;
            s.groundTicks = 0;
        }
        if (s.slowTicks > 0 && --s.slowTicks == 0) {
            s.slowMultiplier = 1;
        }
        flags(s, vy0, ground0, shape);
        states(s, k, keys);
        boolean affectedByGravity = !(s.flying || s.inFluid || s.swimming || s.climbing || s.mantling);
        s.pushes.decay(s.wasFalling, affectedByGravity);
        s.wasFalling = s.falling;
        return true;
    }

    /** The settings' effects that switch keys off. */
    private static Controls restrict(Controls c, MovementEffects me) {
        if (me == null) {
            return c;
        }
        double f = c.forward();
        double st = c.strafe();
        if (me.disableForward && f > 0 || me.disableBackward && f < 0) {
            f = 0;
        }
        if (me.disableRight && st > 0 || me.disableLeft && st < 0) {
            st = 0;
        }
        return new Controls(f, st, c.yaw(), c.pitch(), c.jump() && !me.disableJump, c.crouch() && !me.disableCrouch,
                c.sprint() && !me.disableSprint, c.walk(), c.fly());
    }

    // ------------------------------------------------------------------
    // Where the body is
    // ------------------------------------------------------------------

    /** The ground under the feet, blocks the body is in, climbables it touches, and fluid it is in. */
    Place sense(BodyState s, Surroundings.BodyShape shape) {
        double hx = shape.halfX();
        double hz = shape.halfZ();
        BlockMovementSettings ground = terrain.groundSettings(s.x, s.y, s.z, hx, hz);
        double speed = ground == null || ground.horizontalSpeedMultiplier <= 0 ? 1 : ground.horizontalSpeedMultiplier;
        double jump = ground == null || ground.jumpForceMultiplier <= 0 ? 1 : ground.jumpForceMultiplier;
        double terminal = ground == null || ground.terminalVelocityModifier <= 0 ? 1 : ground.terminalVelocityModifier;
        boolean bouncy = ground != null && ground.isBouncy;
        double bounce = ground == null ? 0 : ground.bounceVelocity;
        Terrain.Inside in = terrain.inside(s.x, s.y, s.z, hx, hz, shape.height(), Tuning.CLIMB_REACH);
        speed *= in.speedMul();
        jump *= in.jumpMul();
        terminal *= in.terminalMul();
        Terrain.Wet wet = terrain.wet(s.x, s.y, s.z, hx, hz, shape.height(), around);
        return new Place(ground, speed, jump, terminal, in.climbable(), in.climbUp(), in.climbDown(), in.climbLateral(),
                in.climbNx(), in.climbNz(), in.climbTop(),
                wet == null ? null : wet.motion(), wet == null ? 0 : wet.depth(), bouncy, bounce);
    }

    /**
     * A body inside a block (one that came after the body was placed - a teleport's answer
     * outruns the blocks, which come on another stream) is put out of it, onto its top when there
     * is room there: the client's collision expulsion.
     */
    private void expel(BodyState s, Surroundings.BodyShape shape, double height) {
        double[] b = box(s, shape, height);
        List<double[]> boxes = collider.boxesAround(b, 0, 0, 0);
        double top = Double.NaN;
        for (double[] o : boxes) {
            if (o[0] < b[3] - Collider.SKIN && o[3] > b[0] + Collider.SKIN && o[1] < b[4] - Collider.SKIN
                    && o[4] > b[1] + Collider.SKIN && o[2] < b[5] - Collider.SKIN && o[5] > b[2] + Collider.SKIN) {
                top = Double.isNaN(top) ? o[4] : Math.max(top, o[4]);
            }
        }
        if (Double.isNaN(top) || top - s.y > 1.0) {
            return;
        }
        if (terrain.fits(s.x, top, s.z, shape.halfX(), shape.halfZ(), height)) {
            s.y = top;
            s.vy = Math.max(0, s.vy);
            s.onGround = true;
        }
    }

    private boolean roomFor(BodyState s, Surroundings.BodyShape shape, double height) {
        double[] b = box(s, shape, height);
        return !Collider.overlaps(b, collider.boxesAround(b, 0, 0, 0));
    }

    private boolean floorUnder(BodyState s, Surroundings.BodyShape shape, double height) {
        double[] b = box(s, shape, height);
        List<double[]> boxes = collider.boxesAround(b, 0, -0.01, 0);
        double d = -0.01;
        for (double[] o : boxes) {
            d = Collider.clipY(o, b, d);
        }
        return d > -0.01 + 1e-9;
    }

    /** How far the ground is under the feet, up to {@code most}; {@code most} when there is none that near. */
    private double groundBelow(BodyState s, Surroundings.BodyShape shape, double most) {
        double top = terrain.floorTop(s.x, s.z, shape.halfX(), shape.halfZ(), s.y - most, s.y + 1e-3);
        return Double.isNaN(top) ? most : s.y - top;
    }

    static double[] box(BodyState s, Surroundings.BodyShape shape, double height) {
        return new double[] {s.x - shape.halfX(), s.y, s.z - shape.halfZ(), s.x + shape.halfX(), s.y + height,
                s.z + shape.halfZ()};
    }

    // ------------------------------------------------------------------
    // The keys
    // ------------------------------------------------------------------

    /**
     * The keys' wish: the direction of the key axes, turned into the world by the look - a
     * diagonal at 45° - and the cap of the speed they ask for: the base speed times the gait's
     * multiplier for the direction (on the ground the forward or back one whenever the forward axis
     * is in, else the side one; in the air the side one whenever the side axis is in), the
     * sprint's eased in, times the ground's, the fluid's, the effects' and a step's slow-down.
     */
    private Wish wish(BodyState s, Controls k, MovementSettings p, Place place, Surroundings.EffectMotion effects,
                      boolean keys, boolean air) {
        double f = s.keyF;
        double st = s.keyS;
        double len = Math.hypot(f, st);
        if (!keys || len < EPS) {
            return new Wish(0, 0, 0, false);
        }
        double lf = f / len;
        double ls = st / len;
        double fm;
        double bm;
        double sm;
        if (s.crouching) {
            fm = p.forwardCrouchSpeedMultiplier;
            bm = p.backwardCrouchSpeedMultiplier;
            sm = p.strafeCrouchSpeedMultiplier;
        } else if (k.walk() && !s.sprinting) {
            fm = p.forwardWalkSpeedMultiplier;
            bm = p.backwardWalkSpeedMultiplier;
            sm = p.strafeWalkSpeedMultiplier;
        } else {
            fm = p.forwardRunSpeedMultiplier;
            bm = p.backwardRunSpeedMultiplier;
            sm = p.strafeRunSpeedMultiplier;
            fm *= 1 + (p.forwardSprintSpeedMultiplier - 1) * sprintEase(s);
        }
        double m;
        if (air) {
            m = Math.abs(st) > EPS ? sm : f > 0 ? fm : bm;
        } else {
            m = Math.abs(f) > EPS ? (f > 0 ? fm : bm) : sm;
        }
        double mul = place.speedMul() * s.slowMultiplier;
        if (s.inFluid && place.fluid() != null && place.fluid().horizontalMultiplier() > 0) {
            mul *= place.fluid().horizontalMultiplier();
        }
        if (effects != null) {
            mul *= effects.speedMultiplier();
            if (effects.movement() != null) {
                mul *= effects.movement().speedMultiplier;
            }
        }
        if (p.maxSpeedMultiplier > 0) {
            mul = Math.min(mul, p.maxSpeedMultiplier);
        }
        mul = Math.max(mul, p.minSpeedMultiplier);
        double sin = Math.sin(s.yaw);
        double cos = Math.cos(s.yaw);
        // yaw 0 faces -z; right is +x.
        double dx = -sin * lf + cos * ls;
        double dz = -cos * lf - sin * ls;
        return new Wish(dx, dz, p.baseSpeed * m * mul, true);
    }

    /** How far the sprint's speed is in: a cubic ease over six ticks, in and out. */
    private static double sprintEase(BodyState s) {
        int n = Tuning.SPRINT_EASE_TICKS;
        if (s.sprintTicks >= 0) {
            double e = Math.min(1.0, (double) s.sprintTicks / n);
            return e * e * e;
        }
        if (s.sprintOutTicks >= 0) {
            double e = Math.max(0.0, (double) (n - s.sprintOutTicks) / n);
            return e * e * e;
        }
        return 0;
    }

    /**
     * The body turns toward its movement: to the look, or 45° off it for a side key (back and a
     * side key the other way), at most {@code BODY_TURN} a tick.
     */
    private static void turnBody(BodyState s) {
        double off = 0;
        if (Math.abs(s.keyS) > EPS) {
            double side = Math.signum(s.keyS);
            off = (s.keyF < -EPS ? side : -side) * Tuning.BODY_OFFSET;
        }
        double target = s.yaw + off;
        double d = Math.IEEEremainder(target - s.bodyYaw, 2 * Math.PI);
        double turn = Math.max(-Tuning.BODY_TURN, Math.min(Tuning.BODY_TURN, d));
        s.bodyYaw = (float) (s.bodyYaw + turn);
    }

    /** The asked-for speed one tick on: toward the cap by acceleration·W²/base, never past it. */
    private static double rampUp(BodyState s, MovementSettings p, double cap) {
        double step = Math.max(p.acceleration, 1e-3) * cap * cap / Math.max(p.baseSpeed, EPS);
        return Math.min(cap, s.ramp + step);
    }

    /**
     * The ground's law with keys in: the velocity keeps {@code KEY_KEEP} and gains 0.18 of the
     * asked-for speed along the keys' direction - but never past the cap along it, the asked-for
     * speed keeping what it really gave.
     */
    private static void keyed(BodyState s, Wish w, double asked) {
        double keep = Tuning.KEY_KEEP;
        double gain = 1 - keep;
        double along = keep * (s.vx * w.dx() + s.vz * w.dz());
        if (along >= w.cap()) {
            s.vx *= keep;
            s.vz *= keep;
            return;
        }
        double add = Math.min(gain * asked, w.cap() - along);
        s.vx = keep * s.vx + add * w.dx();
        s.vz = keep * s.vz + add * w.dz();
        s.ramp = add / gain;
    }

    // ------------------------------------------------------------------
    // The ways of moving
    // ------------------------------------------------------------------

    /**
     * On the ground. With keys: the asked-for speed rises and the velocity follows it
     * ({@link #keyed}); let go, the velocity dies by the ground's drag (0.82 on stone, 0.99 on ice).
     * Gravity works here too and the floor takes it back (the client reports −0.533 standing). A
     * crouch at a sprint's speed slides; a jump, once the body has had a tick on the ground, leaves
     * at jumpForce less that tick's gravity and moves by the air's law on its own tick.
     */
    private void ground(BodyState s, Controls k, MovementSettings p, Place place, Wish w, Wish airWish,
                        boolean wasCrouching) {
        double drag = groundDrag(place);
        double speed = Math.hypot(s.vx, s.vz);
        if (place.bouncy() && place.bounce() > 0) {
            s.vy = place.bounce();
            s.jumping = false;
            return;
        }
        boolean canJump = s.groundTicks >= 1 && s.rollTicks < 0;
        if (s.jumpBuffer > 0 && canJump) {
            // The jump's own tick already falls, and already moves by the air's law.
            s.airMul = s.groundTicks == 1 ? 1.025 : 1.0;
            s.vy = fall(p.jumpForce * place.jumpMul(), p, place.terminalMul());
            s.jumpBuffer = 0;
            s.jumping = true;
            s.jumped = true;
            s.sliding = false;
            s.slideTicks = -1;
            s.airKeyTicks = -1;
            double[] v = {s.vx, s.vz};
            double cap = airWish.cap() * s.airMul;
            airHorizontal(v, airWish, cap, cap, p);
            s.vx = v[0];
            s.vz = v[1];
            return;
        }
        // A slide: crouch pressed at a sprint's speed.
        if (s.slideTicks < 0 && s.crouching && !wasCrouching && speed >= p.minSlideEntrySpeed
                && around.feature(ClientFeature.CrouchSlide)) {
            s.slideTicks = 0;
            s.slideStart = Math.max(s.ramp, speed);
            s.sliding = true;
        }
        if (s.rollTicks >= 0) {
            roll(s, p);
        } else if (s.slideTicks >= 0) {
            slide(s, w);
        } else if (w.keys()) {
            s.ramp = rampUp(s, p, w.cap());
            keyed(s, w, s.ramp);
        } else {
            s.ramp = 0;
            s.vx *= drag;
            s.vz *= drag;
        }
        s.vy = fall(0, p, place.terminalMul());
    }

    /** A slide: the asked-for speed follows the slide's own curve, the direction the keys' (or the look's). */
    private void slide(BodyState s, Wish w) {
        int t = s.slideTicks++;
        double[] curve = Tuning.SLIDE_CURVE;
        double dx = w.keys() ? w.dx() : -Math.sin(s.yaw);
        double dz = w.keys() ? w.dz() : -Math.cos(s.yaw);
        if (t < curve.length) {
            double asked = curve[t] * s.slideStart / curve[0];
            s.vx = Tuning.KEY_KEEP * s.vx + (1 - Tuning.KEY_KEEP) * asked * dx;
            s.vz = Tuning.KEY_KEEP * s.vz + (1 - Tuning.KEY_KEEP) * asked * dz;
            s.ramp = asked;
        } else {
            if (t == curve.length) {
                s.ramp = Tuning.SLIDE_AFTER;
            }
            if (w.keys()) {
                keyed(s, w, s.ramp);
            } else {
                s.vx *= Tuning.KEY_KEEP;
                s.vz *= Tuning.KEY_KEEP;
            }
        }
        if (t + 1 >= Tuning.SLIDE_FLAG_TICKS) {
            s.sliding = false;
            s.slideTicks = -1;
        }
    }

    /**
     * A safety roll, for {@code ROLL_TICKS} ticks after the landing: v' = 0.82·v + 0.18·base·M·r
     * along the way the body came, M easing from rollStartSpeedModifier to rollExitSpeedModifier
     * on a cubic over the roll's time, r coming in by a third a tick.
     */
    private void roll(BodyState s, MovementSettings p) {
        int n = ++s.rollTicks;
        double span = Math.max(1, Math.round(p.rollTimeToComplete / DT));
        double e = Math.max(0, 1 - n / span);
        double mm = p.rollExitSpeedModifier + (p.rollStartSpeedModifier - p.rollExitSpeedModifier) * e * e * e;
        double r = Math.min(1, Tuning.ROLL_RAMP * n);
        double push = (1 - Tuning.KEY_KEEP) * p.baseSpeed * mm * r;
        s.vx = Tuning.KEY_KEEP * s.vx + push * s.rollDirX;
        s.vz = Tuning.KEY_KEEP * s.vz + push * s.rollDirZ;
        if (n >= Tuning.ROLL_TICKS) {
            s.rolling = false;
            s.rollTicks = -1;
            s.ramp = Math.hypot(s.vx, s.vz);
        }
    }

    /**
     * In the air: the speed dies by the air's drag over its range; with keys in, it is pushed
     * along the keys' direction - Quake's way: only the part along it is capped at the wished
     * speed, by the air's friction (falling from 0.045 to 0.02 as the speed rises) times its
     * control (1 + 3.13 at a standstill, nothing from 3 up). Keys that came in mid-air come in by
     * the control. Gravity, and once jump is let go while rising, variableJumpFallForce more.
     */
    private void air(BodyState s, Controls k, MovementSettings p, Place place, Wish w, double vy0) {
        if (s.wasSwimming && s.swimJumping && k.jump()) {
            // Out of the water: the swim jump leaves as a jump.
            s.vy = fall(p.jumpForce, p, place.terminalMul());
            s.jumping = true;
            s.jumped = true;
        } else {
            double vy = fall(s.vy, p, place.terminalMul());
            if (s.jumping && !k.jump() && vy0 > 0) {
                vy -= p.variableJumpFallForce * DT;
            }
            s.vy = vy;
        }
        double[] v = {s.vx, s.vz};
        double cap = w.cap() * s.airMul;
        double effective = cap;
        if (w.keys()) {
            if (s.airKeyTicks >= 0) {
                if (s.airKeyTicks == 0) {
                    s.airKeySpeed = Math.hypot(s.vx, s.vz);
                }
                s.airKeyTicks++;
                double control = airControl(s.airKeySpeed, p);
                effective = Math.min(cap, s.airKeyTicks * Math.max(p.acceleration, 1e-3) * cap * cap
                        / Math.max(p.baseSpeed, EPS) * control);
            }
        } else if (s.airKeyTicks < 0) {
            s.airKeyTicks = 0;
        } else {
            s.airKeyTicks = 0;
        }
        airHorizontal(v, w, cap, effective, p);
        s.vx = v[0];
        s.vz = v[1];
        if (s.vy <= 0) {
            s.swimJumping = false;
        }
    }

    /** The air's control: 1 + airControlMaxMultiplier at a standstill down to 1 at airControlMaxSpeed. */
    private static double airControl(double speed, MovementSettings p) {
        return 1 + Tuning.range(speed, p.airControlMinSpeed, p.airControlMaxSpeed, p.airControlMaxMultiplier,
                p.airControlMinMultiplier);
    }

    /** One tick of the air's horizontal law on {@code v} (x, z) - see {@link #air}. */
    static void airHorizontal(double[] v, Wish w, double cap, double effective, MovementSettings p) {
        double speed = Math.hypot(v[0], v[1]);
        double drag = Tuning.range(speed, p.airDragMinSpeed, p.airDragMaxSpeed, p.airDragMin, p.airDragMax);
        double friction = Tuning.range(speed, p.airFrictionMinSpeed, p.airFrictionMaxSpeed, p.airFrictionMax,
                p.airFrictionMin);
        double control = airControl(speed, p);
        v[0] *= drag;
        v[1] *= drag;
        if (w != null && w.keys() && cap > EPS) {
            double wished = cap * (p.airSpeedMultiplier > 0 ? p.airSpeedMultiplier : 1);
            double along = v[0] * w.dx() + v[1] * w.dz();
            double add = wished - along;
            if (add > 0) {
                double a = Math.min(friction * control * effective, add);
                v[0] += a * w.dx();
                v[1] += a * w.dz();
            }
        }
    }

    /** For {@link BodySettings}: the air's law along a straight line, keys held from take-off. */
    static void airAlong(double[] v, double speed, MovementSettings p) {
        airHorizontal(v, new Wish(1, 0, speed, true), speed, speed, p);
    }

    /** The share of its speed the body keeps a tick on the ground under it, with no keys ({@code BlockMovementSettings.drag}). */
    double groundDrag(BodyState s) {
        Surroundings.BodyShape shape = around.shape();
        BlockMovementSettings g = terrain.groundSettings(s.x, s.y, s.z, shape.halfX(), shape.halfZ());
        return drag(g);
    }

    private static double groundDrag(Place place) {
        return drag(place.ground());
    }

    private static double drag(BlockMovementSettings g) {
        double d = g == null || g.drag <= 0 || g.drag >= 1 ? DEFAULT_DRAG : g.drag;
        return Math.min(0.999, d);
    }

    /** Whether the ground ends within the next ticks' travel, straight ahead along {@code yaw}. */
    boolean edgeAhead(BodyState s, float yaw) {
        Surroundings.BodyShape shape = around.shape();
        double v = Math.max(Math.hypot(s.vx, s.vz), 1);
        double ahead = v * DT * 1.5;
        double x = s.x - Math.sin(yaw) * ahead;
        double z = s.z - Math.cos(yaw) * ahead;
        return Double.isNaN(terrain.floorTop(x, z, shape.halfX(), shape.halfZ(), s.y - 0.6, s.y + 0.05));
    }

    /** Gravity on a fall speed, toward the body's terminal velocity ({@code computeNewFallSpeed}). */
    double fall(double v, MovementSettings p, double terminalMul) {
        Surroundings.BodyShape shape = around.shape();
        double mass = p.mass > 0 ? p.mass : 68;
        double drag = p.dragCoefficient > 0 ? p.dragCoefficient : 0.5;
        double area = shape.width() * shape.depth();
        double terminal = Math.sqrt(2 * GRAVITY * mass * 1000 / (AIR_DENSITY * area * 1_000_000 * drag));
        terminal *= terminalMul;
        double t = (p.invertedGravity ? 1 : -1) * terminal;
        double ratio = Math.abs(v / t);
        double acc = GRAVITY * (Math.signum(t) - Math.signum(v) * ratio * ratio * ratio);
        double step = acc * DT;
        if (v < t && step > 0) {
            return Math.min(v + step, t);
        }
        if (v > t && step < 0) {
            return Math.max(v + step, t);
        }
        return v;
    }

    /**
     * Flying: across, the velocity closes {@code FLY_BLEND} of the gap to the fly speed in the keys'
     * direction - the same speed whichever way, no ramp; up and down are the fly speed at once plus
     * what was left of the vertical speed, dying away by {@code FLY_REST_DECAY} a tick.
     */
    private void fly(BodyState s, Controls k, MovementSettings p, Wish w) {
        double tx = w.keys() ? w.dx() * p.horizontalFlySpeed : 0;
        double tz = w.keys() ? w.dz() * p.horizontalFlySpeed : 0;
        s.vx += (tx - s.vx) * Tuning.FLY_BLEND;
        s.vz += (tz - s.vz) * Tuning.FLY_BLEND;
        double u = k.jump() ? 1 : k.crouch() ? -1 : 0;
        s.flyRest *= Tuning.FLY_REST_DECAY;
        if (Math.abs(s.flyRest) < 1e-4) {
            s.flyRest = 0;
        }
        s.vy = u * p.verticalFlySpeed + s.flyRest;
        s.jumping = false;
        s.ramp = 0;
    }

    /**
     * Climbing: the body moves by the climb's steps - up (forward) and down (back)
     * {@code climbSpeed} a tick, up at a sprint {@code climbUpSprintSpeed}, sideways
     * {@code climbSpeedLateral} with the side key coming in over two ticks; the first tick of a
     * climb rises a step more. Jump lets go: straight off at 0.4·jumpForce, or 0.25·jumpForce off
     * and as much to the side held, rising at jumpForce.
     */
    private void climb(BodyState s, Controls k, MovementSettings p, Place place) {
        double perTick = Tuning.CLIMB_SPEED_PER_TICK;
        double side = Math.signum(k.strafe());
        s.climbSide = approach(s.climbSide, side, Tuning.KEY_STEP);
        double rx = -s.climbNz;
        double rz = s.climbNx;
        if (k.jump()) {
            double off = side == 0 ? Tuning.CLIMB_JUMP_OFF : Tuning.CLIMB_JUMP_SIDE;
            s.vx = (-s.climbNx * off + rx * Tuning.CLIMB_JUMP_SIDE * Math.abs(side) * Math.signum(side)) * p.jumpForce;
            s.vz = (-s.climbNz * off + rz * Tuning.CLIMB_JUMP_SIDE * Math.abs(side) * Math.signum(side)) * p.jumpForce;
            s.vy = p.jumpForce * place.jumpMul();
            s.climbing = false;
            s.wasClimbing = false;
            s.jumping = true;
            s.jumped = true;
            s.climbSide = 0;
            return;
        }
        double up = k.sprint() ? p.climbUpSprintSpeed : p.climbSpeed;
        double down = k.sprint() ? p.climbDownSprintSpeed : p.climbSpeed;
        double vertical = 0;
        if (k.forward() > 0) {
            vertical = up * place.climbUpMul();
            if (!s.wasClimbing) {
                vertical += p.climbSpeed;
            }
        } else if (k.forward() < 0) {
            vertical = -down * place.climbDownMul();
        }
        double lateral = s.climbSide * p.climbSpeedLateral * place.climbLateralMul();
        s.vx = rx * lateral * perTick;
        s.vz = rz * lateral * perTick;
        s.vy = vertical * perTick;
        s.jumping = false;
        if (s.climbNx != 0 || s.climbNz != 0) {
            s.bodyYaw = (float) Math.atan2(-s.climbNx, -s.climbNz);
        }
    }

    /** What the client reports while climbing: the base speed into the climbable, turned 45° by a side key, none up or down. */
    private static void reportClimb(BodyState s, Controls k, MovementSettings p) {
        double speed = p.baseSpeed * (k.sprint() ? p.forwardSprintSpeedMultiplier : 1);
        double ix = s.climbNx;
        double iz = s.climbNz;
        if (Math.abs(s.climbSide) > EPS) {
            double w = s.climbSide;
            double ax = ix - iz * w;
            double az = iz + ix * w;
            double l = Math.hypot(ax, az);
            ix = ax / l;
            iz = az / l;
        }
        s.mvx = ix * speed;
        s.mvy = 0;
        s.mvz = iz * speed;
    }

    /**
     * Swimming: across as on the ground, at the fluid's speed; up and down the fluid's swim speeds
     * at once; with neither, a body near the surface floats - a spring toward {@code FLOAT_DEPTH}
     * under it, cut to nothing under 0.1 - and one going down sinks at the fluid's sink speed. With
     * the eyes out of the water, jump lifts at swimJumpForce until the body is out.
     */
    private void swim(BodyState s, Controls k, MovementSettings p, Place place, Wish w) {
        Surroundings.FluidMotion f = place.fluid();
        if (w.keys()) {
            s.ramp = rampUp(s, p, w.cap());
            keyed(s, w, s.ramp);
        } else {
            s.ramp = 0;
            s.vx *= DEFAULT_DRAG;
            s.vz *= DEFAULT_DRAG;
        }
        double depth = place.fluidDepth();
        double eyes = around.shape().eyeHeight();
        if (k.jump() && depth <= eyes + 1e-3) {
            s.vy = p.swimJumpForce;
            s.swimJumping = true;
        } else if (k.jump()) {
            s.vy = f.swimUp();
        } else if (k.crouch()) {
            s.vy = f.swimDown();
        } else if (depth < Tuning.FLOAT_DEPTH) {
            // The feet above the float line (surface − FLOAT_DEPTH): the spring takes them down to it.
            double v = Tuning.FLOAT_SPRING * (depth - Tuning.FLOAT_DEPTH);
            s.vy = Math.abs(v) < 0.1 ? 0 : v;
        } else if (s.vy < 0) {
            s.vy = f.sink();
        } else {
            s.vy = 0;
        }
        s.jumping = false;
    }

    // ------------------------------------------------------------------
    // Steps, landings, ledges
    // ------------------------------------------------------------------

    /**
     * A move into a rise up to {@code most} takes it in the same tick at full speed, the feet put
     * {@code STEP_LIFT} above its top - when the block stepped onto allows it.
     */
    private Collider.Moved stepUp(double[] start, double dx, double dy, double dz, Surroundings.BodyShape shape,
                                  double height, BodyState s, double most) {
        double[] b = start.clone();
        List<double[]> boxes = collider.boxesAround(b, dx, most + 0.01, dz);
        Collider.Moved up = Collider.move(b, 0, most, 0, boxes);
        if (up.dy() < EPS) {
            return null;
        }
        Collider.Moved across = Collider.move(b, dx, 0, dz, boxes);
        Collider.Moved down = Collider.move(b, 0, -up.dy() + Math.min(0, dy), 0, boxes);
        double rise = up.dy() + down.dy();
        double flat = Math.hypot(across.dx(), across.dz());
        double wanted = Math.hypot(dx, dz);
        if (rise <= EPS || flat < wanted * 0.5 || !down.hitY()) {
            return null;
        }
        if (!terrain.autoStepAllowed(s.x + across.dx(), start[1] + rise - 0.01, s.z + across.dz(), shape.halfX(),
                shape.halfZ())) {
            return null;
        }
        return new Collider.Moved(across.dx(), rise + Tuning.STEP_LIFT, across.dz(), across.hitX(), false,
                across.hitZ());
    }

    private void land(BodyState s, Controls k, MovementSettings p, Place place, double vyBefore, Wish w) {
        s.landingSpeed = Math.max(0, -vyBefore);
        boolean fromAir = s.jumped || s.falling;
        s.vy = 0;
        s.jumping = false;
        s.swimJumping = false;
        s.groundTicks = 0;
        if (!s.inFluid && around.feature(ClientFeature.SafetyRoll) && s.landingSpeed > p.minFallSpeedToEngageRoll
                && s.landingSpeed <= p.maxFallSpeedToEngageRoll && (!Tuning.ROLL_NEEDS_CROUCH || k.crouch())) {
            s.rolling = true;
            s.rollTicks = 0;
            double v = Math.hypot(s.vx, s.vz);
            if (v > 0.05) {
                s.rollDirX = s.vx / v;
                s.rollDirZ = s.vz / v;
            } else {
                s.rollDirX = -Math.sin(s.bodyYaw);
                s.rollDirZ = -Math.cos(s.bodyYaw);
            }
        }
        if (fromAir && w.keys()) {
            // The keys' speed starts again from a share of the cap after a landing.
            s.ramp = Math.min(s.ramp, Tuning.LANDING_RAMP * w.cap());
        }
        s.jumped = false;
    }

    /**
     * A ledge: moving into a wall in the air whose top is above the head by no more than
     * {@code MANTLE_OVER_HEAD}. The body is put on the top at once, {@code MANTLE_REACH} past where
     * it met the wall, the coordinate along the wall kept, and is held still for
     * {@code MANTLE_FREEZE_TICKS}. Returns whether it happened.
     */
    private boolean mantle(BodyState s, Surroundings.BodyShape shape, double height, Collider.Moved m, double feet0,
                           double dx, double dz, double cx, double cz) {
        double nx = 0;
        double nz = 0;
        if (m.hitX() && Math.abs(dx) > EPS) {
            nx = Math.signum(dx);
        } else if (m.hitZ() && Math.abs(dz) > EPS) {
            nz = Math.signum(dz);
        } else {
            return false;
        }
        double head = feet0 + height;
        Double top = terrain.ledgeTop(cx, cz, nx, nz, shape.halfX(), shape.halfZ(), head + EPS,
                head + Tuning.MANTLE_OVER_HEAD + EPS, 0.05, height);
        if (top == null) {
            return false;
        }
        double px = nx != 0 ? cx + nx * Tuning.MANTLE_REACH : s.x;
        double pz = nz != 0 ? cz + nz * Tuning.MANTLE_REACH : s.z;
        if (!terrain.fits(px, top, pz, shape.halfX(), shape.halfZ(), height)) {
            return false;
        }
        s.x = px;
        s.y = top;
        s.z = pz;
        s.vx = s.vy = s.vz = 0;
        s.pushes.clear();
        s.mantling = true;
        s.mantleTicks = Tuning.MANTLE_FREEZE_TICKS;
        s.jumping = false;
        s.falling = false;
        s.onGround = false;
        return true;
    }

    /**
     * The flags that come from the start of the tick (the client takes jumping and falling from the
     * vertical speed it reports): jumping while a jump rises; falling once it is past its top, or,
     * off a ledge, once the ground under the feet is more than a block down - a step down is not a
     * fall - and from the start in the air after a push; fallingFar past the roll's top speed.
     */
    private void flags(BodyState s, double vy0, boolean ground0, Surroundings.BodyShape shape) {
        MovementSettings p = around.settings();
        double far = p == null || p.maxFallSpeedToEngageRoll <= 0 ? 31 : p.maxFallSpeedToEngageRoll;
        if (s.onGround) {
            // The landing tick still shows the fall; standing does not.
            if (ground0) {
                s.falling = false;
                s.fallingFar = false;
            }
            return;
        }
        if (s.flying || s.climbing || s.mantling) {
            s.falling = false;
            s.fallingFar = false;
            return;
        }
        if (s.swimming) {
            s.falling = vy0 < 0 && !s.crouching;
            s.fallingFar = false;
            return;
        }
        if (s.jumped) {
            s.jumping = s.jumping && vy0 > 0;
            s.falling = vy0 <= 0;
        } else {
            boolean pushed = !s.pushes.carried.isEmpty() || vy0 > 0;
            boolean was = s.falling;
            boolean deep = groundBelow(s, shape, 1.2) > 1.0;
            s.falling = pushed || (vy0 < 0 && (was || deep));
            if (s.falling && !was && !pushed) {
                s.airMul = 0.975;
            }
        }
        s.fallingFar = s.falling && -s.mvy > far;
    }

    /** The movement states the keys give (the client's: idle means no keys; running holds while crouching and sliding). */
    private static void states(BodyState s, Controls k, boolean keys) {
        boolean moving = keys || s.hadKeys;
        s.noKeys = !moving;
        s.walking = k.walk() && !s.sprinting;
        s.running = moving && !s.walking && !s.flying && !s.climbing;
        s.rolling = s.rollTicks >= 0;
        if (s.rolling) {
            s.crouching = true;
        }
        if (s.sliding) {
            s.crouching = true;
        }
        s.hadKeys = keys;
    }

    /**
     * Takes in a push from the server ({@code ChangeVelocity}). One with a config is a channel of
     * its own; one without goes into the body's own velocity - a set replaces it, an add is scaled
     * across by the ground's friction and the body's {@code velocityResistance} (recorded: ×0.04356
     * of 149.67), up and down as it is.
     */
    void push(BodyState s, float x, float y, float z, meridian.protocol.ChangeVelocityType type,
              meridian.protocol.VelocityConfig config) {
        MovementSettings p = around.settings();
        double resistance = p == null ? 0.242 : p.velocityResistance;
        boolean set = type == meridian.protocol.ChangeVelocityType.Set;
        if (config != null) {
            s.pushes.take(x, y, z, set, config);
        } else if (set) {
            s.vx = x;
            s.vz = z;
            s.vy = y;
        } else {
            double scale = (1 - DEFAULT_DRAG) * resistance;
            s.vx += x * scale;
            s.vz += z * scale;
            s.vy += y;
        }
        if (s.totalY() > 0) {
            s.onGround = false;
            if (s.vy < 0 && s.pushes.anyStartedUp()) {
                s.vy = 0;
            }
        }
    }

    private static double approach(double v, double target, double step) {
        double d = target - v;
        return Math.abs(d) <= step ? target : v + Math.signum(d) * step;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
