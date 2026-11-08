package meridian.core.impl.movement;

import java.util.List;
import meridian.core.api.Movement.Controls;
import meridian.protocol.BlockMovementSettings;
import meridian.protocol.packets.setup.ClientFeature;
import meridian.protocol.FlyMode;
import meridian.protocol.MovementEffects;
import meridian.protocol.MovementSettings;

/**
 * One client tick of the player's body: the keys and the look in, the body moved, as the client
 * moves it. Gravity, drag in the air, pushes and how they die away, and fall speed are the server's
 * copies of the client's ({@code PhysicsMath}, {@code MotionControllerBase}); how the keys turn into
 * speed, steps, climbing, swimming and ledges is the client's alone and goes by the settings it is
 * sent and {@link Tuning}.
 *
 * <p>Pure: a state and controls in, the state changed. The live body and a prediction run the same
 * code.
 */
final class Physics {

    static final double DT = 1.0 / 60.0;
    /** The server's gravity ({@code PhysicsConstants.GRAVITY_ACCELERATION}). */
    static final double GRAVITY = 32;
    /** Air density as the server takes it ({@code PhysicsMath.AIR_DENSITY}). */
    private static final double AIR_DENSITY = 0.001225;
    private static final double EPS = 1e-6;

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
                 Surroundings.FluidMotion fluid, double fluidDepth, boolean bouncy, double bounce) {}

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

        // --- what the body is doing -------------------------------------------
        FlyMode fly = around.flyMode();
        if (fly == FlyMode.Forced) {
            s.flying = true;
        } else if (fly == FlyMode.Disabled) {
            s.flying = false;
        } else if (k.fly() != s.flying) {
            s.flying = k.fly();
        }
        boolean wantCrouch = k.crouch() && !s.flying && !s.swimming;
        double fullHeight = shape.height();
        double crouchHeight = shape.height() + shape.crouchOffset();
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
        s.swimming = !s.flying && place.fluid() != null && place.fluidDepth() >= Tuning.SWIM_DEPTH * fullHeight;

        boolean pressedJump = k.jump() && !s.jumpHeld;
        s.jumpHeld = k.jump();
        if (pressedJump) {
            s.jumpBuffer = p.jumpBufferDuration;
        } else {
            s.jumpBuffer = Math.max(0, s.jumpBuffer - DT);
        }

        if (s.ledge != null) {
            hang(s, k, p, shape, fullHeight);
            finish(s, p, place, shape, fullHeight);
            return true;
        }

        boolean wantsClimb = place.climbable() && !s.flying && !s.swimming
                && (!s.onGround || k.forward() > 0 || k.jump());
        s.climbing = wantsClimb;

        // --- the wished velocity ----------------------------------------------
        double[] wish = wish(s, k, p, place, effects);
        double wishSpeed = Math.hypot(wish[0], wish[1]);

        if (s.flying) {
            fly(s, k, p, wish);
        } else if (s.climbing) {
            climb(s, k, p, place);
        } else if (s.swimming) {
            swim(s, k, p, place, wish);
        } else if (s.onGround) {
            ground(s, k, p, place, wish, wishSpeed, shape);
        } else {
            air(s, k, p, place, wish);
        }

        // --- moving it through the blocks --------------------------------------
        expel(s, shape, height);
        double vyBefore = s.totalY();
        double[] box = box(s, shape, height);
        double dx = s.totalX() * DT;
        double dy = s.totalY() * DT;
        double dz = s.totalZ() * DT;
        List<double[]> boxes = collider.boxesAround(box, dx, dy + (s.onGround ? -0.05 : 0), dz);
        double[] start = box.clone();
        Collider.Moved m = Collider.move(box, dx, dy, dz, boxes);
        if (m.horizontal() && s.onGround && !s.flying) {
            Collider.Moved stepped = stepUp(start, dx, dy, dz, place, shape, height, s);
            if (stepped != null) {
                m = stepped;
                System.arraycopy(start, 0, box, 0, 6);
                box[1] += m.dy();
                box[4] += m.dy();
                box[0] += m.dx();
                box[3] += m.dx();
                box[2] += m.dz();
                box[5] += m.dz();
            } else {
                autoJump(s, k, p, start, dx, dz, boxes, place);
            }
        }
        s.x = (box[0] + box[3]) / 2;
        s.y = box[1];
        s.z = (box[2] + box[5]) / 2;

        if (m.hitX()) {
            s.vx = 0;
            for (Pushes.Push q : s.pushes.carried) {
                q.x = 0;
            }
        }
        if (m.hitZ()) {
            s.vz = 0;
            for (Pushes.Push q : s.pushes.carried) {
                q.z = 0;
            }
        }
        boolean wasOnGround = s.onGround;
        // The body's own vertical speed as it came down: a push's lift is spent once that has
        // eaten it (MotionControllerWalk: canClear = push.y + own.y <= 0).
        double ownVyDown = s.vy;
        if (m.landed(dy)) {
            land(s, k, p, place, vyBefore);
        } else if (m.ceiling(dy)) {
            s.vy = Math.min(0, s.vy);
            for (Pushes.Push q : s.pushes.carried) {
                q.y = Math.min(0, q.y);
            }
        }
        // Still on the ground: a floor right under the feet.
        s.onGround = m.landed(dy) || (dy <= EPS && floorUnder(s, shape, height));
        if (s.onGround && !wasOnGround && !m.landed(dy)) {
            land(s, k, p, place, vyBefore);
        }
        if (s.onGround) {
            s.airTime = 0;
            s.pushes.landed(Math.min(ownVyDown, s.vy));
            if (s.vy < 0) {
                s.vy = 0;
            }
        } else {
            s.airTime += DT;
        }

        // Mantling: in the air, moving into a wall whose top the hands reach.
        if (!s.onGround && !s.flying && !s.climbing && !s.swimming && k.forward() > 0
                && (k.jump() || !Tuning.MANTLE_NEEDS_JUMP) && s.totalY() <= Tuning.MANTLE_GRAB_MAX_VY
                && around.feature(ClientFeature.Mantling)) {
            grab(s, shape, fullHeight);
        }

        finish(s, p, place, shape, fullHeight);
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

    static double[] box(BodyState s, Surroundings.BodyShape shape, double height) {
        return new double[] {s.x - shape.halfX(), s.y, s.z - shape.halfZ(), s.x + shape.halfX(), s.y + height,
                s.z + shape.halfZ()};
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /** The horizontal velocity the keys ask for, in the world. */
    private double[] wish(BodyState s, Controls k, MovementSettings p, Place place,
                          Surroundings.EffectMotion effects) {
        double f = clamp(k.forward(), -1, 1);
        double st = clamp(k.strafe(), -1, 1);
        double len = Math.hypot(f, st);
        if (len > 1) {
            f /= len;
            st /= len;
        }
        boolean sprint = k.sprint() && f > 0 && !s.crouching && !s.swimming;
        s.sprinting = sprint && len > EPS;
        s.walking = !sprint && k.walk() && len > EPS;
        s.running = len > EPS && !s.walking && !s.crouching;
        double fm;
        double sm;
        if (s.crouching) {
            fm = f >= 0 ? p.forwardCrouchSpeedMultiplier : p.backwardCrouchSpeedMultiplier;
            sm = p.strafeCrouchSpeedMultiplier;
        } else if (k.walk() && !sprint) {
            fm = f >= 0 ? p.forwardWalkSpeedMultiplier : p.backwardWalkSpeedMultiplier;
            sm = p.strafeWalkSpeedMultiplier;
        } else {
            fm = f >= 0 ? p.forwardRunSpeedMultiplier : p.backwardRunSpeedMultiplier;
            sm = p.strafeRunSpeedMultiplier;
            if (sprint) {
                fm *= p.forwardSprintSpeedMultiplier;
            }
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
        double base = s.flying ? p.horizontalFlySpeed : p.baseSpeed;
        double lf = f * fm * base * mul;
        double ls = st * sm * base * mul;
        double sin = Math.sin(s.yaw);
        double cos = Math.cos(s.yaw);
        // yaw 0 faces -z; right is +x.
        return new double[] {-sin * lf + cos * ls, -cos * lf - sin * ls};
    }

    // ------------------------------------------------------------------
    // The ways of moving
    // ------------------------------------------------------------------

    private void ground(BodyState s, Controls k, MovementSettings p, Place place, double[] wish, double wishSpeed,
                        Surroundings.BodyShape shape) {
        double friction = place.ground() == null ? Tuning.DEFAULT_FRICTION : place.ground().friction;
        double blend = Tuning.groundBlend(p.acceleration, friction);
        double speed = Math.hypot(s.vx, s.vz);

        // Sliding: a crouch at a run's speed carries on, slowing, until it is spent.
        if (s.sliding) {
            if (!k.crouch() || speed < p.slideExitSpeed) {
                s.sliding = false;
            } else {
                s.vx -= s.vx * blend * Tuning.SLIDE_FRICTION_SHARE;
                s.vz -= s.vz * blend * Tuning.SLIDE_FRICTION_SHARE;
            }
        } else if (k.crouch() && speed >= p.minSlideEntrySpeed && around.feature(ClientFeature.CrouchSlide)) {
            s.sliding = true;
        }
        if (s.rolling) {
            s.rollTime -= DT;
            if (s.rollTime <= 0) {
                s.rolling = false;
                double v = Math.hypot(s.vx, s.vz);
                if (v > EPS) {
                    double to = p.rollExitSpeedModifier * (Tuning.ROLL_SPEEDS_ABSOLUTE ? 1 : p.baseSpeed);
                    s.vx *= Math.min(1, to / v);
                    s.vz *= Math.min(1, to / v);
                }
            }
        }
        if (!s.sliding && !s.rolling) {
            s.vx += (wish[0] - s.vx) * blend;
            s.vz += (wish[1] - s.vz) * blend;
        }
        if (s.slowTime > 0) {
            s.slowTime -= DT;
            if (s.slowTime <= 0) {
                s.slowMultiplier = 1;
            }
        }
        s.vy = 0;
        if (place.bouncy() && place.bounce() > 0) {
            s.vy = place.bounce();
            s.onGround = false;
            s.jumping = false;
            return;
        }
        if (s.jumpBuffer > 0 && !s.rolling) {
            s.vy = p.jumpForce * place.jumpMul();
            s.jumpBuffer = 0;
            s.jumping = true;
            s.onGround = false;
            s.sliding = false;
        }
    }

    private void air(BodyState s, Controls k, MovementSettings p, Place place, double[] wish) {
        double[] v = {s.vx, s.vz};
        airHorizontal(v, wish, p);
        s.vx = v[0];
        s.vz = v[1];

        // A jump pressed just before landing waits for the ground; one pressed falling slowly is a
        // jump still (JumpBufferMaxYVelocity).
        s.vy = fall(s.vy, p, place.terminalMul());
        if (s.vy > 0 && s.jumping && !k.jump()) {
            // Letting go early ends the jump sooner (VariableJumpFallForce).
            s.vy = Math.max(0, s.vy - p.variableJumpFallForce * DT);
        }
        if (s.vy <= 0) {
            s.jumping = false;
        }
    }

    /**
     * The horizontal velocity {@code v} (x, z) one tick in the air toward {@code wish}: steered by
     * the air's friction and control, then slowed by the air's drag over the speed range.
     */
    static void airHorizontal(double[] v, double[] wish, MovementSettings p) {
        double speed = Math.hypot(v[0], v[1]);
        double friction = Tuning.range(speed, p.airFrictionMinSpeed, p.airFrictionMaxSpeed, p.airFrictionMin,
                p.airFrictionMax);
        double control = Tuning.range(Math.hypot(wish[0], wish[1]), p.airControlMinSpeed, p.airControlMaxSpeed,
                p.airControlMinMultiplier, p.airControlMaxMultiplier);
        double ax = wish[0] * p.airSpeedMultiplier;
        double az = wish[1] * p.airSpeedMultiplier;
        if (Tuning.AIR_FRICTION_IS_BLEND) {
            double blend = Tuning.clamp01(friction * Math.max(1, control));
            // Air control steers; it does not speed the body past what it had or wished.
            double nx = v[0] + (ax - v[0]) * blend;
            double nz = v[1] + (az - v[1]) * blend;
            double cap = Math.max(speed, Math.hypot(ax, az));
            double nl = Math.hypot(nx, nz);
            if (nl > cap && nl > EPS) {
                nx *= cap / nl;
                nz *= cap / nl;
            }
            v[0] = nx;
            v[1] = nz;
        }
        double drag = Tuning.range(Math.hypot(v[0], v[1]), p.airDragMinSpeed, p.airDragMaxSpeed, p.airDragMin,
                p.airDragMax);
        v[0] *= drag;
        v[1] *= drag;
    }

    /** How much of the gap to the wished speed the ground under the body closes a tick. */
    double groundBlend(BodyState s) {
        MovementSettings p = around.settings();
        Surroundings.BodyShape shape = around.shape();
        BlockMovementSettings g = terrain.groundSettings(s.x, s.y, s.z, shape.halfX(), shape.halfZ());
        double friction = g == null ? Tuning.DEFAULT_FRICTION : g.friction;
        return Math.max(1e-3, Tuning.groundBlend(p == null ? 0.1 : p.acceleration, friction));
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

    private void fly(BodyState s, Controls k, MovementSettings p, double[] wish) {
        double up = k.jump() ? p.verticalFlySpeed : k.crouch() ? -p.verticalFlySpeed : 0;
        s.vx += (wish[0] - s.vx) * Tuning.FLY_BLEND;
        s.vz += (wish[1] - s.vz) * Tuning.FLY_BLEND;
        s.vy += (up - s.vy) * Tuning.FLY_BLEND;
        s.jumping = false;
    }

    private void climb(BodyState s, Controls k, MovementSettings p, Place place) {
        double perTick = Tuning.CLIMB_SPEED_PER_TICK;
        double up = k.sprint() ? p.climbUpSprintSpeed : p.climbSpeed;
        double down = k.sprint() ? p.climbDownSprintSpeed : p.climbSpeed;
        double vertical = 0;
        if (k.forward() > 0 || k.jump()) {
            vertical = up * perTick * place.climbUpMul();
        } else if (k.forward() < 0 || k.crouch()) {
            vertical = -down * perTick * place.climbDownMul();
        }
        double lateral = k.strafe() * p.climbSpeedLateral * perTick * place.climbLateralMul();
        double cos = Math.cos(s.yaw);
        double sin = Math.sin(s.yaw);
        s.vx = cos * lateral;
        s.vz = -sin * lateral;
        s.vy = vertical;
        s.jumping = false;
    }

    private void swim(BodyState s, Controls k, MovementSettings p, Place place, double[] wish) {
        Surroundings.FluidMotion f = place.fluid();
        double target = k.jump() ? f.swimUp() : k.crouch() ? f.swimDown() : f.sink();
        double friction = place.ground() == null ? Tuning.DEFAULT_FRICTION : Tuning.DEFAULT_FRICTION;
        double blend = Tuning.groundBlend(p.acceleration, friction);
        s.vx += (wish[0] - s.vx) * blend;
        s.vz += (wish[1] - s.vz) * blend;
        s.vy += (target - s.vy) * Tuning.SWIM_BLEND;
        s.swimJumping = false;
        // At the surface, a jump against a bank lifts the body out (SwimJumpForce).
        if (k.jump() && place.fluidDepth() < 1.0 && s.jumpBuffer > 0) {
            s.vy = p.swimJumpForce;
            s.swimJumping = true;
            s.jumpBuffer = 0;
        }
        s.jumping = false;
    }

    // ------------------------------------------------------------------
    // Steps, landings, ledges
    // ------------------------------------------------------------------

    /** A walk into a low rise takes it without a jump, when the block it would stand on allows. */
    private Collider.Moved stepUp(double[] start, double dx, double dy, double dz, Place place,
                                  Surroundings.BodyShape shape, double height, BodyState s) {
        double[] b = start.clone();
        List<double[]> boxes = collider.boxesAround(b, dx, Tuning.STEP_HEIGHT, dz);
        Collider.Moved up = Collider.move(b, 0, Tuning.STEP_HEIGHT, 0, boxes);
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
        return new Collider.Moved(across.dx(), rise, across.dz(), across.hitX(), false, across.hitZ());
    }

    /** Walking into an obstacle a jump clears, the client jumps by itself, losing some speed. */
    private void autoJump(BodyState s, Controls k, MovementSettings p, double[] start, double dx, double dz,
                          List<double[]> boxes, Place place) {
        if (s.jumping || s.crouching || Math.hypot(dx, dz) < EPS || p.autoJumpObstacleMaxAngle <= 0) {
            return;
        }
        // How high the obstacle is: the body lifted until the move goes through.
        double[] b = start.clone();
        Collider.Moved up = Collider.move(b, 0, Tuning.AUTO_JUMP_HEIGHT, 0, boxes);
        if (up.dy() < Tuning.AUTO_JUMP_HEIGHT - 1e-3) {
            return;
        }
        Collider.Moved across = Collider.move(b, dx, 0, dz, boxes);
        if (Math.hypot(across.dx(), across.dz()) < Math.hypot(dx, dz) * 0.5) {
            return;
        }
        // Only head-on enough (AutoJumpObstacleMaxAngle between the move and the wall's normal).
        double moveAngle = Math.toDegrees(Math.atan2(Math.abs(dz), Math.abs(dx)));
        double offAxis = Math.min(moveAngle, 90 - moveAngle);
        if (offAxis > p.autoJumpObstacleMaxAngle) {
            return;
        }
        s.vy = p.jumpForce * place.jumpMul();
        s.jumping = true;
        s.onGround = false;
        boolean sprint = s.sprinting;
        s.slowMultiplier = sprint ? p.autoJumpObstacleSprintSpeedLoss : p.autoJumpObstacleSpeedLoss;
        s.slowTime = sprint ? p.autoJumpObstacleSprintEffectDuration : p.autoJumpObstacleEffectDuration;
        if (p.autoJumpDisableJumping) {
            s.jumpBuffer = 0;
        }
    }

    private void land(BodyState s, Controls k, MovementSettings p, Place place, double vyBefore) {
        s.landingSpeed = Math.max(0, -vyBefore);
        s.vy = 0;
        s.jumping = false;
        s.falling = false;
        s.swimJumping = false;
        if (!s.inFluid && around.feature(ClientFeature.SafetyRoll) && s.landingSpeed > p.minFallSpeedToEngageRoll
                && s.landingSpeed <= p.maxFallSpeedToEngageRoll && (!Tuning.ROLL_NEEDS_CROUCH || k.crouch())) {
            s.rolling = true;
            s.rollTime = p.rollTimeToComplete;
            double v = Math.hypot(s.vx, s.vz);
            double to = p.rollStartSpeedModifier * (Tuning.ROLL_SPEEDS_ABSOLUTE ? 1 : p.baseSpeed);
            if (v > EPS) {
                s.vx *= to / v;
                s.vz *= to / v;
            } else {
                double sin = Math.sin(s.yaw);
                double cos = Math.cos(s.yaw);
                s.vx = -sin * to;
                s.vz = -cos * to;
            }
        }
        if (p.fallEffectDuration > 0 && s.landingSpeed > p.minFallSpeedToEngageRoll && !s.rolling) {
            s.slowMultiplier = 1 - p.fallMomentumLoss;
            s.slowTime = p.fallEffectDuration;
        }
    }

    /** A wall in front whose top is in reach: the hands take the ledge. */
    private void grab(BodyState s, Surroundings.BodyShape shape, double height) {
        double fx = -Math.sin(s.yaw);
        double fz = -Math.cos(s.yaw);
        // The wall straight ahead along the nearer axis.
        double nx;
        double nz;
        if (Math.abs(fx) > Math.abs(fz)) {
            nx = -Math.signum(fx);
            nz = 0;
        } else {
            nx = 0;
            nz = -Math.signum(fz);
        }
        double eyes = s.y + shape.eyeHeight();
        Double top = terrain.ledgeTop(s.x, s.z, -nx, -nz, shape.halfX(), shape.halfZ(), s.y + Tuning.MANTLE_MIN_ABOVE_FEET,
                eyes + Tuning.MANTLE_REACH_ABOVE_EYES, Tuning.MANTLE_WALL_DISTANCE, height);
        if (top == null) {
            return;
        }
        s.ledge = new BodyState.Ledge(top, nx, nz, s.x, top - shape.eyeHeight() + 0.1, s.z);
        s.vx = 0;
        s.vy = 0;
        s.vz = 0;
        s.pushes.clear();
        s.mantling = true;
        s.pullTime = -1;
        s.jumping = false;
    }

    /** Hanging from a ledge: forward pulls up, sideways moves along it, back or crouch lets go. */
    private void hang(BodyState s, Controls k, MovementSettings p, Surroundings.BodyShape shape, double height) {
        BodyState.Ledge l = s.ledge;
        s.mantling = true;
        s.climbing = false;
        s.onGround = false;
        if (s.pullTime >= 0) {
            s.pullTime += DT;
            double f = Math.min(1, s.pullTime / Tuning.MANTLE_UP_SECONDS);
            double up = Math.min(1, f / 0.6);
            double over = Math.max(0, (f - 0.6) / 0.4);
            double reach = shape.halfX() + 0.05;
            s.y = l.hangY() + (l.top() - l.hangY()) * up;
            s.x = l.hangX() - l.nx() * reach * over;
            s.z = l.hangZ() - l.nz() * reach * over;
            if (f >= 1) {
                s.ledge = null;
                s.mantling = false;
                s.pullTime = -1;
                s.onGround = true;
                s.y = l.top();
            }
            return;
        }
        if (k.forward() < 0 || k.crouch()) {
            s.ledge = null;
            s.mantling = false;
            return;
        }
        if (k.forward() > 0 || k.jump()) {
            if (terrain.standsOn(l.hangX() - l.nx() * (shape.halfX() + 0.05), l.top(),
                    l.hangZ() - l.nz() * (shape.halfZ() + 0.05), shape.halfX(), shape.halfZ(), height)) {
                s.pullTime = 0;
                return;
            }
        }
        // Along the ledge: the wall's tangent.
        if (Math.abs(k.strafe()) > EPS) {
            double tx = -l.nz();
            double tz = l.nx();
            double cos = Math.cos(s.yaw);
            double sin = Math.sin(s.yaw);
            double rx = cos;
            double rz = -sin;
            double sign = Math.signum((rx * tx + rz * tz) * k.strafe());
            double d = Tuning.MANTLE_SHUFFLE_SPEED * DT * sign;
            double nxp = l.hangX() + tx * d;
            double nzp = l.hangZ() + tz * d;
            Double top = terrain.ledgeTop(nxp, nzp, -l.nx(), -l.nz(), shape.halfX(), shape.halfZ(), l.top() - 0.3,
                    l.top() + 0.3, Tuning.MANTLE_WALL_DISTANCE, height);
            if (top != null && Math.abs(top - l.top()) < 1e-3) {
                s.ledge = new BodyState.Ledge(l.top(), l.nx(), l.nz(), nxp, l.hangY(), nzp);
                s.x = nxp;
                s.z = nzp;
                s.vx = tx * d / DT;
                s.vz = tz * d / DT;
            }
        } else {
            s.vx = 0;
            s.vz = 0;
        }
        s.x = s.ledge.hangX();
        s.y = s.ledge.hangY();
        s.z = s.ledge.hangZ();
    }

    /** The states that follow from the rest, and pushes dying away. */
    private void finish(BodyState s, MovementSettings p, Place place, Surroundings.BodyShape shape, double height) {
        s.falling = !s.onGround && !s.flying && !s.climbing && !s.swimming && !s.mantling && s.totalY() < 0;
        boolean affectedByGravity = !(s.flying || s.inFluid || s.swimming || s.climbing || s.mantling);
        s.pushes.decay(s.onGround, affectedByGravity);
        // A push without a config dies away by the ground's drag, or the air's, as the body's own
        // velocity does on the server's copy (MotionControllerBase: block drag / air drag).
        if (s.kx != 0 || s.kz != 0) {
            double k;
            if (s.flying) {
                k = 0;
            } else if (s.onGround || s.swimming) {
                k = place.ground() == null ? 0.82 : place.ground().drag;
            } else {
                k = Tuning.range(Math.hypot(s.kx, s.kz), p.airDragMinSpeed, p.airDragMaxSpeed, p.airDragMin,
                        p.airDragMax);
            }
            s.kx *= k;
            s.kz *= k;
            if (Math.abs(s.kx) <= 0.1 && Math.abs(s.kz) <= 0.1 && s.onGround) {
                s.kx = 0;
                s.kz = 0;
            }
            if (Math.abs(s.kx) < 1e-4) {
                s.kx = 0;
            }
            if (Math.abs(s.kz) < 1e-4) {
                s.kz = 0;
            }
        }
    }

    /**
     * Takes in a push from the server ({@code ChangeVelocity}). One with a config is a channel of
     * its own; one without goes into the body's velocity - a set replaces it, an add is scaled by
     * the ground's friction and the body's {@code velocityResistance} ("client null-path parity").
     */
    void push(BodyState s, float x, float y, float z, meridian.protocol.ChangeVelocityType type,
              meridian.protocol.VelocityConfig config) {
        MovementSettings p = around.settings();
        double resistance = p == null ? 0.242 : p.velocityResistance;
        boolean set = type == meridian.protocol.ChangeVelocityType.Set;
        if (config != null) {
            s.pushes.take(x, y, z, set, config);
        } else if (set) {
            s.vx = 0;
            s.vz = 0;
            s.kx = x;
            s.kz = z;
            s.vy = y;
        } else {
            Place place = sense(s, around.shape());
            double groundDrag = place.ground() == null ? 0.82 : place.ground().drag;
            double scale = (1 - groundDrag) * resistance;
            s.kx += x * scale;
            s.kz += z * scale;
            s.vy += y;
        }
        if (s.totalY() > 0) {
            s.onGround = false;
            if (s.vy < 0 && s.pushes.anyStartedUp()) {
                s.vy = 0;
            }
        }
        if (s.ledge != null) {
            s.ledge = null;
            s.mantling = false;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
