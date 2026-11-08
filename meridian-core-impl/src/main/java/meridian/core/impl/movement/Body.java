package meridian.core.impl.movement;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import meridian.api.module.Scheduler;
import meridian.api.session.HeadlessClient;
import meridian.core.api.Movement;
import meridian.core.api.Movement.MoveResult;
import meridian.core.api.Vec3;
import meridian.protocol.Direction;
import meridian.protocol.MovementStates;
import meridian.protocol.Position;
import meridian.protocol.Vector3d;
import meridian.protocol.packets.player.ClientMovement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The headless player's body: plays moves a tick at a time and reports each tick the way the client
 * does - a {@code ClientMovement} with where the feet are, how fast they go, and the movement
 * states (on the ground, running, jumping, falling, pulling up).
 *
 * <p>A move is worked out whole before it starts, from the client's numbers ({@link BodySettings}):
 * a walk picks up speed by the server's acceleration; a jump leaves the ground at the jump force and
 * comes down under the server's gravity; a drop falls the same way, and lands at the speed it fell,
 * which is what the server takes fall damage from. Every tick of the plan is checked against the
 * blocks first ({@link Terrain#fits}); a move that would pass through one does not start.
 *
 * <p>Nothing is simulated beyond the plan: the body is where its moves put it. A teleport from the
 * server cuts a move short, and the next one starts wherever the server put the player.
 */
public final class Body {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");
    private static final long TICK_NANOS = 1_000_000_000L / 60L;

    /** One tick of a plan. */
    private record Sample(double x, double y, double z, double vx, double vy, double vz, Kind kind) {}

    /** What the body is doing in a tick - the movement states it reports. */
    private enum Kind { WALK, RUN, SPRINT, JUMP_UP, FALL, MANTLE, LAND }

    private record Queued(Movement.Move move, CompletableFuture<MoveResult> done) {}

    private final Scheduler scheduler;
    private final Terrain terrain;
    private final BodySettings settings;
    private final Supplier<HeadlessClient> client;
    private final Supplier<Vec3> trackedFeet;

    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    private Queued current;
    private List<Sample> plan;
    private int index;
    private boolean looping;
    private long baseNanos;
    private long ticks;

    private boolean positioned;
    private double x;
    private double y;
    private double z;
    private float yaw;
    /** Horizontal speed carried from one walk into the next. */
    private double carried;
    private boolean onGround = true;

    Body(Scheduler scheduler, Terrain terrain, BodySettings settings, Supplier<HeadlessClient> client,
         Supplier<Vec3> trackedFeet) {
        this.scheduler = scheduler;
        this.terrain = terrain;
        this.settings = settings;
        this.client = client;
        this.trackedFeet = trackedFeet;
    }

    synchronized Vec3 position() {
        if (!positioned) {
            Vec3 t = trackedFeet.get();
            return t;
        }
        return new Vec3(x, y, z);
    }

    synchronized boolean onGround() {
        return onGround;
    }

    synchronized CompletableFuture<MoveResult> enqueue(Movement.Move move) {
        CompletableFuture<MoveResult> done = new CompletableFuture<>();
        if (client.get() == null) {
            done.complete(new MoveResult(MoveResult.Status.NOT_AVAILABLE, position()));
            return done;
        }
        queue.addLast(new Queued(move, done));
        if (!looping) {
            looping = true;
            baseNanos = System.nanoTime();
            ticks = 0;
            schedule(0);
        }
        return done;
    }

    /** Ends after the move under way; the queued ones are dropped. */
    synchronized void stop() {
        Vec3 at = position();
        while (!queue.isEmpty()) {
            queue.pollFirst().done().complete(new MoveResult(MoveResult.Status.INTERRUPTED, at));
        }
    }

    /** The server moved the player: the move under way and the queued ones are over. */
    synchronized void teleported() {
        positioned = false;
        carried = 0;
        onGround = true;
        Vec3 at = trackedFeet.get();
        if (current != null) {
            current.done().complete(new MoveResult(MoveResult.Status.INTERRUPTED, at));
            current = null;
            plan = null;
        }
        while (!queue.isEmpty()) {
            queue.pollFirst().done().complete(new MoveResult(MoveResult.Status.INTERRUPTED, at));
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
        long next;
        synchronized (this) {
            HeadlessClient c = client.get();
            if (c == null) {
                teleported();
                looping = false;
                return;
            }
            while (plan == null && !queue.isEmpty()) {
                current = queue.pollFirst();
                plan = planFor(current.move());
                index = 0;
                if (plan == null) {
                    current.done().complete(new MoveResult(MoveResult.Status.BLOCKED, position()));
                    current = null;
                }
            }
            if (plan == null) {
                // Nothing left: a last report standing still, then quiet.
                if (positioned) {
                    c.sendAsClient(packet(x, y, z, 0, 0, 0, Kind.LAND, true));
                }
                looping = false;
                return;
            }
            Sample s = plan.get(index++);
            x = s.x();
            y = s.y();
            z = s.z();
            onGround = s.kind() == Kind.WALK || s.kind() == Kind.RUN || s.kind() == Kind.SPRINT || s.kind() == Kind.LAND;
            c.sendAsClient(packet(s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz(), s.kind(), false));
            if (index >= plan.size()) {
                carried = s.kind() == Kind.LAND ? 0 : Math.hypot(s.vx(), s.vz());
                onGround = true;
                current.done().complete(new MoveResult(MoveResult.Status.ARRIVED, new Vec3(x, y, z)));
                current = null;
                plan = null;
            }
            ticks++;
            next = baseNanos + ticks * TICK_NANOS - System.nanoTime();
        }
        schedule(next);
    }

    // ------------------------------------------------------------------
    // Plans
    // ------------------------------------------------------------------

    /** The ticks of one move from where the body is, or {@code null} when it cannot be made. */
    private List<Sample> planFor(Movement.Move move) {
        if (!positioned) {
            Vec3 t = trackedFeet.get();
            if (t == null) {
                return null;
            }
            x = t.x();
            y = t.y();
            z = t.z();
            positioned = true;
        }
        Vec3 to = move.to();
        double dx = to.x() - x;
        double dz = to.z() - z;
        double d = Math.hypot(dx, dz);
        if (d > 1e-6) {
            yaw = (float) Math.atan2(-dx, -dz);
        }
        double ux = d > 1e-6 ? dx / d : 0;
        double uz = d > 1e-6 ? dz / d : 0;
        double rise = to.y() - y;
        List<Sample> out = switch (move.kind()) {
            case WALK -> Math.abs(rise) > BodySettings.STEP_HEIGHT + 0.01 ? null : walk(to, d, ux, uz, move.gait());
            case JUMP -> jump(to, d, ux, uz, rise);
            case MANTLE -> rise <= 0 || rise > settings.capabilities().mantleHeight() + 1e-6
                    ? null : mantle(to, d, ux, uz, rise);
            case DROP -> rise >= 0 ? null : drop(to, d, ux, uz, move.gait());
        };
        if (out == null || out.isEmpty()) {
            log.info("meridian-core: {} to {} cannot be made from ({}, {}, {})", move.kind(), to, x, y, z);
            return null;
        }
        for (Sample s : out) {
            if (!terrain.fits(s.x(), s.y(), s.z(), Terrain.HEIGHT)) {
                log.info("meridian-core: {} to {} is blocked at ({}, {}, {})", move.kind(), to, s.x(), s.y(), s.z());
                return null;
            }
        }
        return out;
    }

    /** Along the ground: speed picked up by the acceleration, a step taken at once, a step down at the edge. */
    private List<Sample> walk(Vec3 to, double d, double ux, double uz, Movement.Gait gait) {
        List<Sample> out = new ArrayList<>();
        double speed = settings.speed(gait);
        double v = carried;
        double s = 0;
        double y0 = y;
        Kind kind = gait == Movement.Gait.WALK ? Kind.WALK : gait == Movement.Gait.SPRINT ? Kind.SPRINT : Kind.RUN;
        if (d < 1e-6) {
            out.add(new Sample(to.x(), to.y(), to.z(), 0, 0, 0, Kind.LAND));
            return out;
        }
        while (s < d - 1e-9) {
            v += (speed - v) * settings.acceleration;
            v = Math.max(v, 0.5); // a standing start still moves the first tick
            s = Math.min(d, s + v * BodySettings.TICK);
            double h = to.y() > y0 ? to.y() : (s >= d * 0.5 ? to.y() : y0);
            out.add(new Sample(x + ux * s, h, z + uz * s, ux * v, 0, uz * v, kind));
            if (out.size() > 60 * 60) {
                return null;
            }
        }
        return out;
    }

    /** Off the ground at the jump force, over to the point, down under gravity onto it. */
    private List<Sample> jump(Vec3 to, double d, double ux, double uz, double rise) {
        double v0 = settings.jumpForce;
        double g = BodySettings.GRAVITY;
        double disc = v0 * v0 - 2 * g * rise;
        if (disc < 0) {
            return null;
        }
        double time = (v0 + Math.sqrt(disc)) / g;
        double hv = d / time;
        List<Sample> out = new ArrayList<>();
        int n = (int) Math.ceil(time / BodySettings.TICK);
        for (int i = 1; i <= n; i++) {
            double t = Math.min(i * BodySettings.TICK, time);
            double f = t / time;
            double vy = v0 - g * t;
            if (i == n) {
                out.add(new Sample(to.x(), to.y(), to.z(), ux * hv, vy, uz * hv, Kind.LAND));
            } else {
                out.add(new Sample(x + ux * d * f, y + v0 * t - g * t * t / 2, z + uz * d * f, ux * hv, vy, uz * hv,
                        vy > 0 ? Kind.JUMP_UP : Kind.FALL));
            }
        }
        return out;
    }

    /**
     * Up a wall to a ledge: a jump close to the wall until the hands are over the edge, then the
     * pull-up - first up, beside the wall, then over the top onto the ledge.
     */
    private List<Sample> mantle(Vec3 to, double d, double ux, double uz, double rise) {
        double v0 = settings.jumpForce;
        double g = BodySettings.GRAVITY;
        double apex = settings.jumpApex();
        double grab = Math.min(rise - 1.2, apex * 0.9); // feet height (above the start) where the hands reach
        double approach = Math.max(0, Math.min(d, 0.5) - Terrain.HALF); // up to the wall, not into it
        List<Sample> out = new ArrayList<>();
        double gx = x;
        double gy = y;
        double gz = z;
        if (grab > 0) {
            double tGrab = (v0 - Math.sqrt(Math.max(0, v0 * v0 - 2 * g * grab))) / g;
            int n = Math.max(1, (int) Math.ceil(tGrab / BodySettings.TICK));
            for (int i = 1; i <= n; i++) {
                double t = Math.min(i * BodySettings.TICK, tGrab);
                double f = t / tGrab;
                double vy = v0 - g * t;
                gx = x + ux * approach * f;
                gy = y + v0 * t - g * t * t / 2;
                gz = z + uz * approach * f;
                out.add(new Sample(gx, gy, gz, ux * approach / tGrab, vy, uz * approach / tGrab, Kind.JUMP_UP));
            }
        }
        int pull = (int) Math.round(BodySettings.MANTLE_SECONDS / BodySettings.TICK);
        for (int i = 1; i <= pull; i++) {
            double f = (double) i / pull;
            double up = Math.min(1, f / 0.6);          // the first part of the pull-up raises the body
            double over = Math.max(0, (f - 0.6) / 0.4); // then the rest takes it over the edge
            double py = gy + (to.y() - gy) * up;
            double px = gx + (to.x() - gx) * over;
            double pz = gz + (to.z() - gz) * over;
            out.add(new Sample(px, py, pz, 0, 0, 0, i == pull ? Kind.LAND : Kind.MANTLE));
        }
        return out;
    }

    /** Off an edge: walking until the body is past it, then falling under gravity, landing at the speed it fell. */
    private List<Sample> drop(Vec3 to, double d, double ux, double uz, Movement.Gait gait) {
        List<Sample> out = new ArrayList<>();
        double speed = settings.speed(gait == Movement.Gait.SPRINT ? Movement.Gait.RUN : gait);
        double edge = Math.min(d, 0.5 + Terrain.HALF + 0.05);
        double v = Math.max(carried, 0.5);
        double s = 0;
        while (s < edge - 1e-9) {
            v += (speed - v) * settings.acceleration;
            s = Math.min(edge, s + v * BodySettings.TICK);
            out.add(new Sample(x + ux * s, y, z + uz * s, ux * v, 0, uz * v, Kind.RUN));
        }
        double py = y;
        double vy = 0;
        int guard = 0;
        while (true) {
            vy -= BodySettings.GRAVITY * BodySettings.TICK;
            py += vy * BodySettings.TICK;
            s = Math.min(d, s + v * BodySettings.TICK);
            double hv = s < d ? v : 0;
            if (py <= to.y() || ++guard > 60 * 30) {
                out.add(new Sample(to.x(), to.y(), to.z(), ux * hv, vy, uz * hv, Kind.LAND));
                return out;
            }
            out.add(new Sample(x + ux * s, py, z + uz * s, ux * hv, vy, uz * hv, Kind.FALL));
        }
    }

    // ------------------------------------------------------------------
    // The report
    // ------------------------------------------------------------------

    private ClientMovement packet(double px, double py, double pz, double vx, double vy, double vz, Kind kind,
                                  boolean idle) {
        ClientMovement m = new ClientMovement();
        m.absolutePosition = new Position(px, py, pz);
        m.bodyOrientation = new Direction(yaw, 0f, 0f);
        m.lookOrientation = new Direction(yaw, 0f, 0f);
        m.velocity = new Vector3d(vx, vy, vz);
        m.movementStates = states(kind, idle, Math.abs(vy));
        m.mountedTo = 0;
        return m;
    }

    private static MovementStates states(Kind kind, boolean idle, double fallSpeed) {
        MovementStates s = new MovementStates();
        s.idle = idle;
        s.horizontalIdle = idle || kind == Kind.MANTLE || kind == Kind.LAND;
        switch (kind) {
            case WALK -> {
                s.walking = true;
                s.onGround = true;
            }
            case RUN -> {
                s.running = true;
                s.onGround = true;
            }
            case SPRINT -> {
                s.running = true;
                s.sprinting = true;
                s.onGround = true;
            }
            case JUMP_UP -> s.jumping = true;
            case FALL -> {
                s.falling = true;
                s.fallingFar = fallSpeed > BodySettings.HURT_FALL_SPEED;
            }
            case MANTLE -> s.mantling = true;
            case LAND -> s.onGround = true;
        }
        return s;
    }
}
