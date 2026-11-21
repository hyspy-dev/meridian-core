package meridian.core.impl.movement;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import meridian.api.module.Scheduler;
import meridian.core.api.Movement;
import meridian.core.api.Movement.Controls;
import meridian.core.api.Movement.MoveResult;
import meridian.core.api.Vec3;
import meridian.protocol.ChangeVelocityType;
import meridian.protocol.Direction;
import meridian.protocol.MovementStates;
import meridian.protocol.Position;
import meridian.protocol.VelocityConfig;
import meridian.protocol.Vector3d;
import meridian.protocol.packets.player.ClientMovement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The player's body, alive while core moves it: simulated a client tick at a time, 60 a second -
 * a headless player's from the moment the server places it until the session ends, a game's while
 * core holds it ({@link Driver}) - with a move's pilot, held keys, or none -
 * and reported each tick it changes, the way the client reports it: a {@code ClientMovement} with
 * where the feet are, the body's and the look's yaw, the velocity and the movement states.
 *
 * <p>Where it is follows every position the client side sends that is not the body's own: the
 * answer to a server teleport, a teleport core makes (which is a position sent as the client).
 */
public final class Body {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");
    private static final long TICK_NANOS = 1_000_000_000L / 60L;
    private static final double MOVED = 1e-6;

    private record Queued(Pilot pilot, CompletableFuture<MoveResult> done) {}

    private final Scheduler scheduler;
    private final Physics physics;
    private final Supplier<Driver> driver;
    private final Supplier<Surroundings.EffectMotion> effects;

    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    private Queued current;
    private Controls held;

    private BodyState state;
    private boolean looping;
    private long baseNanos;
    private long ticks;
    private boolean quiet;
    /** No old-world tick may be queued after a server teleport's acknowledgement. */
    private final ArrayDeque<Byte> pendingTeleports = new ArrayDeque<>();
    /**
     * Where the body's own last reports put it, so the client-side watch does not take them for a
     * teleport (the packet reaching the watch need not be the object sent).
     */
    private final ArrayDeque<double[]> own = new ArrayDeque<>();
    private MovementStates lastStates;
    /** The keys of the last tick. */
    private volatile Controls lastControls;
    /** Turns of the head waiting for the tick that reports them ({@link #look}). */
    private final java.util.List<CompletableFuture<Boolean>> turns = new java.util.ArrayList<>();
    /** Where the head is turning to while nothing steers ({@link #look}): yaw, pitch; or {@code null}. */
    private float[] lookTo;
    /** How near {@link #lookTo} is near enough, radians. */
    private double lookWithin;
    /** Ticks the head has been turning to {@link #lookTo}. */
    private int lookTicks;
    /** The longest a turn of the head is waited for. */
    private static final int LOOK_MAX_TICKS = 90;

    /**
     * Who moves the body by core: where its reports go, and who is shown it. A headless client's
     * reports go out as the client's; a game's go to the server while core holds its body, and the
     * game is shown where the body is.
     */
    interface Driver {

        /** One tick's report, to the server. */
        void report(ClientMovement m);

        /** The body after a tick (a game is shown it). Called under the body's lock: nothing blocking. */
        default void ticked(BodyState s) {
        }
    }

    Body(Scheduler scheduler, Physics physics, Supplier<Driver> driver,
         Supplier<Surroundings.EffectMotion> effects) {
        this.scheduler = scheduler;
        this.physics = physics;
        this.driver = driver;
        this.effects = effects;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    synchronized boolean placed() {
        return state != null;
    }

    /** The keys of the last tick, or {@code null} before the first. */
    Controls lastControls() {
        return lastControls;
    }

    synchronized BodyState snapshot() {
        return state == null ? null : state.copy();
    }

    // ------------------------------------------------------------------
    // Driven
    // ------------------------------------------------------------------

    synchronized CompletableFuture<MoveResult> enqueue(Movement.Move move) {
        CompletableFuture<MoveResult> done = new CompletableFuture<>();
        if (driver.get() == null || state == null || physics.surroundings().settings() == null) {
            done.complete(new MoveResult(MoveResult.Status.NOT_AVAILABLE, position()));
            return done;
        }
        queue.addLast(new Queued(new Pilot(move, physics, false), done));
        wake();
        return done;
    }

    synchronized void hold(Controls controls) {
        held = controls;
        wake();
    }

    /**
     * Turns the head to a look - when nothing steers: no move under way or queued, no keys held - a
     * tick at a time as {@link Neck} turns it, the body following. The future says whether it got
     * within {@code within} radians of it, and that tick went out; a move or held keys that start
     * steering meanwhile end it with {@code false}.
     */
    synchronized CompletableFuture<Boolean> look(float yaw, float pitch, double within) {
        if (driver.get() == null || state == null || physics.surroundings().settings() == null
                || current != null || !queue.isEmpty() || held != null) {
            return CompletableFuture.completedFuture(false);
        }
        answerTurns(false);
        lookTo = new float[] {yaw, pitch};
        lookWithin = Math.max(1e-6, within);
        lookTicks = 0;
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        turns.add(done);
        wake();
        return done;
    }

    /** Answers the turns waiting: reported this tick with nothing steering, or not. */
    private void answerTurns(boolean turned) {
        if (turns.isEmpty()) {
            return;
        }
        java.util.List<CompletableFuture<Boolean>> waiting = new java.util.ArrayList<>(turns);
        turns.clear();
        // Outside the lock's reach of whoever waits: they may queue a forge, or another turn.
        Runnable answer = () -> waiting.forEach(f -> f.complete(turned));
        try {
            scheduler.schedule(answer, Duration.ZERO);
        } catch (RejectedExecutionException shutDown) {
            answer.run();
        }
    }

    synchronized void stop() {
        held = null;
        if (current != null) {
            current.pilot().interrupt(state);
            current.done().complete(current.pilot().result());
            current = null;
        }
        Vec3 at = position();
        while (!queue.isEmpty()) {
            queue.pollFirst().done().complete(new MoveResult(MoveResult.Status.INTERRUPTED, at));
        }
    }

    /** A push from the server ({@code ChangeVelocity}). */
    synchronized void push(float x, float y, float z, ChangeVelocityType type, VelocityConfig config) {
        if (state != null) {
            physics.push(state, x, y, z, type, config);
            wake();
        }
    }

    /** The server teleported the player: what is under way is over. */
    synchronized void teleported(boolean resetVelocity) {
        if (state != null && resetVelocity) {
            state.vx = state.vy = state.vz = 0;
            state.mvx = state.mvy = state.mvz = 0;
            reportV = new double[] {0, 0, 0};
            state.ramp = 0;
            state.pushes.clear();
        }
        if (state != null) {
            state.mantleTicks = 0;
            state.mantling = false;
            state.climbing = false;
        }
        if (current != null) {
            current.pilot().interrupt(state != null ? state : new BodyState());
            current.done().complete(current.pilot().result());
            current = null;
        }
        Vec3 at = position();
        while (!queue.isEmpty()) {
            queue.pollFirst().done().complete(new MoveResult(MoveResult.Status.INTERRUPTED, at));
        }
    }

    /** Freeze before the headless handler queues the acknowledgement onto the client stream. */
    synchronized void serverTeleport(byte id, boolean resetVelocity) {
        pendingTeleports.addLast(id);
        teleported(resetVelocity);
        own.clear();
    }

    /** A client position the body did not send: initial placement or a teleport's answer. */
    synchronized void sentByOther(ClientMovement m) {
        // The answer to a server teleport is never the body's own report, even where it lands on a
        // place the body reported once - a teleport back to where it stood: taken for its own, the
        // body would stay where it was while the server has it at the teleport.
        if (m.absolutePosition == null) {
            return;
        }
        if (!pendingTeleports.isEmpty()) {
            if (m.teleportAck == null || m.teleportAck.teleportId != pendingTeleports.peekFirst()) {
                return;                     // an old report, or an ACK left over from the previous world
            }
            pendingTeleports.removeFirst();
            if (!pendingTeleports.isEmpty()) {
                // Several S2C teleports can precede their queued C2S answers. Resuming on the
                // first ACK would let an intermediate body's report get queued after the last
                // ACK and put the server and tracker back at that intermediate position.
                return;
            }
        }
        // Body reports always carry velocity. Bare absolute reports are explicit teleports
        // or initial placement, even if they revisit a point the body reported before.
        if (m.velocity != null && ownReport(m.absolutePosition)) {
            if (m.teleportAck == null) {
                return;
            }
            log.info("meridian-core: the answer to teleport {} lands where the body reported once ({}, {}, {}) - taken",
                    m.teleportAck, m.absolutePosition.x, m.absolutePosition.y, m.absolutePosition.z);
        }
        if (state == null) {
            state = new BodyState();
            state.onGround = false;
        }
        state.x = m.absolutePosition.x;
        state.y = m.absolutePosition.y;
        state.z = m.absolutePosition.z;
        sentPos = new double[] {state.x, state.y, state.z};
        if (m.lookOrientation != null) {
            state.yaw = m.lookOrientation.yaw;
            state.pitch = m.lookOrientation.pitch;
            sentLook = new float[] {state.yaw, state.pitch};
        }
        if (m.bodyOrientation != null) {
            state.bodyYaw = m.bodyOrientation.yaw;
            sentBody = state.bodyYaw;
        }
        if (m.teleportAck == null) {
            // A teleport core made: the move under way would steer from the old place.
            if (current != null) {
                current.pilot().interrupt(state);
                current.done().complete(current.pilot().result());
                current = null;
            }
        }
        quiet = false;
        wake();
    }

    /**
     * Core takes a game's body (a game's player, not a headless one): the body starts where the game
     * last said it was, looking where it looked, standing still - and is core's from the next tick.
     */
    synchronized void takeOver(double x, double y, double z, float yaw, float pitch, float bodyYaw,
                               boolean onGround, boolean flying) {
        stop();
        pendingTeleports.clear();
        lookTo = null;
        answerTurns(false);
        state = new BodyState();
        state.x = x;
        state.y = y;
        state.z = z;
        state.yaw = yaw;
        state.pitch = pitch;
        state.bodyYaw = bodyYaw;
        state.onGround = onGround;
        state.flying = flying;
        lastStates = null;
        sentPos = null;
        sentLook = null;
        sentBody = null;
        reportV = new double[] {0, 0, 0};
        own.clear();
        wake();
    }

    /**
     * Core puts the body somewhere (its own teleport, for a game's body core holds): what is under
     * way is over, and the next tick reports the body there.
     */
    synchronized void placeAt(double x, double y, double z) {
        if (state == null) {
            return;
        }
        state.x = x;
        state.y = y;
        state.z = z;
        teleported(true);
        sentPos = null;                      // an explicit teleport always refreshes the upstream pose
        quiet = false;
        wake();
    }

    /** The game has its body back: what is under way is over, and the body is no longer core's. */
    synchronized void letGo() {
        reset();
    }

    /** The server says whether the body flies ({@code SetMovementStates}). */
    synchronized void setFlying(boolean flying) {
        if (state != null) {
            state.flying = flying;
            wake();
        }
    }

    /** Something changed around the body (a block): a tick to see whether it falls or slides. */
    synchronized void nudge() {
        if (state != null) {
            wake();
        }
    }

    private boolean ownReport(Position p) {
        for (java.util.Iterator<double[]> it = own.iterator(); it.hasNext(); ) {
            double[] o = it.next();
            if (o[0] == p.x && o[1] == p.y && o[2] == p.z) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /** The session went away. */
    synchronized void reset() {
        stop();
        pendingTeleports.clear();
        own.clear();
        lookTo = null;
        answerTurns(false);
        state = null;
        lastStates = null;
        sentPos = null;
        sentLook = null;
        sentBody = null;
        reportV = new double[] {0, 0, 0};
    }

    private Vec3 position() {
        return state == null ? null : new Vec3(state.x, state.y, state.z);
    }

    // ------------------------------------------------------------------
    // The tick
    // ------------------------------------------------------------------

    private void wake() {
        quiet = false;
        if (!looping && state != null) {
            looping = true;
            baseNanos = System.nanoTime();
            ticks = 0;
            schedule(0);
        }
    }

    private void schedule(long delayNanos) {
        try {
            scheduler.schedule(this::tick, Duration.ofNanos(Math.max(0, delayNanos)));
        } catch (RejectedExecutionException shutDown) {
            synchronized (this) {
                looping = false;
            }
        }
    }

    private void tick() {
        try {
            tickOnce();
        } catch (RuntimeException e) {
            // A tick that throws must not stop the body for good: say so and go on next tick.
            log.warn("meridian-core: body tick failed", e);
            synchronized (this) {
                looping = false;
                quiet = false;
            }
            try {
                scheduler.schedule(this::wakeLater, Duration.ofMillis(50));
            } catch (RejectedExecutionException ignored) {
                // shutting down
            }
        }
    }

    private synchronized void wakeLater() {
        wake();
    }

    private void tickOnce() {
        long next;
        synchronized (this) {
            Driver c = driver.get();
            if (c == null || state == null) {
                reset();
                looping = false;
                return;
            }
            if (!pendingTeleports.isEmpty()) {
                looping = false;             // the acknowledgement's sentByOther wakes it again
                return;
            }
            Controls controls = controls();
            boolean steered = current != null || held != null;
            boolean turning = !steered && lookTo != null;
            lastControls = controls;
            BodyState before = state.copy();
            boolean moved = physics.step(state, controls, effects.get());
            if (turning) {
                // Standing, the body comes round after the head, as it does with keys in.
                double d = Math.IEEEremainder(state.yaw - state.bodyYaw, 2 * Math.PI);
                state.bodyYaw = (float) (state.bodyYaw + Math.max(-Tuning.BODY_TURN, Math.min(Tuning.BODY_TURN, d)));
            }
            boolean changed = moved && changed(before, state);
            MovementStates states = states(state, controls);
            if (moved) {
                // The client reports every tick, standing still too.
                send(c, states);
            }
            if (!turns.isEmpty() || lookTo != null) {
                if (!moved || steered) {
                    lookTo = null;
                    answerTurns(false);
                } else if (lookTo == null || ++lookTicks >= LOOK_MAX_TICKS
                        || Neck.apart(state.yaw, state.pitch, lookTo[0], lookTo[1]) <= lookWithin + 1e-6) {
                    boolean reached = lookTo == null || Neck.apart(state.yaw, state.pitch,
                            lookTo[0], lookTo[1]) <= lookWithin + 1e-6;
                    lookTo = null;
                    answerTurns(reached);
                }
            }
            c.ticked(state);
            ticks++;
            next = baseNanos + ticks * TICK_NANOS - System.nanoTime();
            if (next < -TICK_NANOS * 30) {
                // Far behind (a stall): catch the clock up rather than replaying the backlog.
                baseNanos = System.nanoTime();
                ticks = 0;
                next = 0;
            }
        }
        schedule(next);
    }

    /** The keys this tick: the move's pilot, else what is held, else none. */
    private Controls controls() {
        while (true) {
            if (current == null) {
                current = queue.pollFirst();
                if (current == null) {
                    break;
                }
            }
            // Another move waiting: go through this one's point instead of stopping on it.
            Queued after = queue.peekFirst();
            current.pilot().through(after != null);
            current.pilot().ahead(after == null ? null : after.pilot().move());
            Controls k = current.pilot().next(state);
            if (k != null) {
                return Neck.follow(state, k, current.pilot().lookYaw(), current.pilot().lookPitch(),
                        current.pilot().carriesKeys());
            }
            current.done().complete(current.pilot().result());
            current = null;
        }
        if (held != null) {
            return held;
        }
        // No keys: a body in flight stays in flight, as the client's does until flying is toggled off.
        float[] look = lookTo == null ? new float[] {state.yaw, state.pitch}
                : Neck.turn(state.yaw, state.pitch, lookTo[0], lookTo[1]);
        return new Controls(0, 0, look[0], look[1], false, false, false, false, state.flying);
    }

    private static boolean changed(BodyState a, BodyState b) {
        return Math.abs(a.x - b.x) > MOVED || Math.abs(a.y - b.y) > MOVED || Math.abs(a.z - b.z) > MOVED
                || a.yaw != b.yaw || a.pitch != b.pitch;
    }

    /**
     * One tick's report, shaped as the client's (recorded 08.11.2026): a packet only when the
     * position, the look, the body's facing or the movement states changed, each of those only
     * when it did; the velocity always - the one the body moved with the tick before, before the
     * blocks stopped any of it.
     */
    private void send(Driver c, MovementStates states) {
        // Pulled up onto a ledge, the client reports no speed from the pull-up's own tick (recorded).
        double[] velocity = state.mantling ? new double[] {0, 0, 0} : reportV;
        reportV = new double[] {state.mvx, state.mvy, state.mvz};
        boolean any = sentPos == null || sentPos[0] != state.x || sentPos[1] != state.y || sentPos[2] != state.z
                || sentLook == null || sentLook[0] != state.yaw || sentLook[1] != state.pitch
                || sentBody == null || sentBody != state.bodyYaw || !states.equals(lastStates);
        if (!any) {
            return;
        }
        ClientMovement m = new ClientMovement();
        if (sentPos == null || sentPos[0] != state.x || sentPos[1] != state.y || sentPos[2] != state.z) {
            m.absolutePosition = new Position(state.x, state.y, state.z);
            sentPos = new double[] {state.x, state.y, state.z};
            own.addLast(sentPos.clone());
            while (own.size() > 32) {
                own.pollFirst();
            }
        }
        if (sentLook == null || sentLook[0] != state.yaw || sentLook[1] != state.pitch) {
            m.lookOrientation = new Direction(state.yaw, state.pitch, 0f);
            sentLook = new float[] {state.yaw, state.pitch};
        }
        if (sentBody == null || sentBody != state.bodyYaw) {
            m.bodyOrientation = new Direction(state.bodyYaw, 0f, 0f);
            sentBody = state.bodyYaw;
        }
        m.velocity = new Vector3d(velocity[0], velocity[1], velocity[2]);
        if (!states.equals(lastStates)) {
            m.movementStates = states;
            lastStates = states;
        }
        m.mountedTo = 0;
        c.report(m);
    }

    /** What was last told: so a field goes out only when it changed. */
    private double[] sentPos;
    private float[] sentLook;
    private Float sentBody;
    /** The velocity to report next tick: this tick's. */
    private double[] reportV = {0, 0, 0};

    private static MovementStates states(BodyState s, Controls k) {
        MovementStates m = new MovementStates();
        // idle and horizontalIdle mean no movement keys, not a body at rest (recorded).
        m.idle = s.noKeys;
        m.horizontalIdle = s.noKeys;
        m.jumping = s.jumping;
        m.flying = s.flying;
        m.walking = s.walking;
        m.running = s.running;
        m.sprinting = s.sprinting;
        m.crouching = s.crouching;
        m.forcedCrouching = s.forcedCrouching;
        m.falling = s.falling;
        m.fallingFar = s.fallingFar;
        m.climbing = s.climbing;
        m.inFluid = s.inFluid;
        m.swimming = s.swimming;
        m.swimJumping = s.swimJumping;
        m.onGround = s.onGround;
        m.mantling = s.mantling;
        m.sliding = s.sliding;
        m.rolling = s.rolling;
        return m;
    }

    // ------------------------------------------------------------------
    // Prediction
    // ------------------------------------------------------------------

    /** Moves played on a copy of the body, one after another. */
    synchronized Movement.Prediction predict(java.util.List<Movement.Move> moves, java.util.List<String> trace) {
        if (state == null || physics.surroundings().settings() == null) {
            return new Movement.Prediction(new MoveResult(MoveResult.Status.NOT_AVAILABLE, null), 0, false, 0);
        }
        return predictFrom(state.copy(), moves, physics, effects.get(), trace);
    }

    static Movement.Prediction predictFrom(BodyState s, java.util.List<Movement.Move> moves, Physics physics,
                                           Surroundings.EffectMotion effects, java.util.List<String> trace) {
        int n = 0;
        boolean hurt = false;
        double hurtSpeed = physics.surroundings().settings().minFallSpeedToEngageRoll;
        int arrived = 0;
        MoveResult last = new MoveResult(MoveResult.Status.ARRIVED, new Vec3(s.x, s.y, s.z));
        for (int i = 0; i < moves.size(); i++) {
            Pilot p = new Pilot(moves.get(i), physics, i + 1 < moves.size());
            p.ahead(i + 1 < moves.size() ? moves.get(i + 1) : null);
            while (true) {
                Controls k = p.next(s);
                if (k == null) {
                    break;
                }
                k = Neck.follow(s, k, p.lookYaw(), p.lookPitch(), p.carriesKeys());
                boolean wasGround = s.onGround;
                physics.step(s, k, effects);
                if (!wasGround && s.onGround && s.landingSpeed > hurtSpeed && !s.inFluid && !s.rolling) {
                    hurt = true;
                }
                if (trace != null && trace.size() < 4000) {
                    trace.add(String.format(java.util.Locale.ROOT,
                            "%d.%d p=(%.3f %.3f %.3f) v=(%.2f %.2f %.2f) f=%.2f s=%.2f yaw=%.0f%s%s%s%s%s", i, n, s.x, s.y,
                            s.z, s.totalX(), s.totalY(), s.totalZ(), k.forward(), k.strafe(), Math.toDegrees(k.yaw()),
                            k.jump() ? " J" : "", s.onGround ? " G" : "", s.climbing ? " C" : "", s.swimming ? " W" : "",
                            s.mantling ? " M" : ""));
                }
                n++;
            }
            last = p.result();
            if (!last.arrived()) {
                break;
            }
            arrived++;
        }
        return new Movement.Prediction(last, n * Physics.DT, hurt, arrived);
    }
}
