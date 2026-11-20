package meridian.core.impl.projectile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Standard arrow flight, independent of a world or network connection.
 * Server references below are relative to HytaleServer/CoreServer/src/main/java/
 * com/hypixel/hytale/server/core/, release b9eb29b (0.7).
 * This implements free flight and stops at first contact; it does not bounce.
 */
public final class ArrowFlight {
    /** The server default without time dilation; callers may supply the actual world step. */
    public static final double DEFAULT_DT = (double) (1.0f / 30);

    /** A homogeneous medium. Water uses 1000 for buoyancy, distinct from the 998 drag reference. */
    public record Medium(double dragDensity, double displacedDensity) {
        public static final Medium AIR = new Medium(1.2, 0);
        public static final Medium WATER = new Medium(1000, 1000);
        public Medium {
            if (!Double.isFinite(dragDensity) || !Double.isFinite(displacedDensity)
                    || dragDensity < 0 || displacedDensity < 0) throw new IllegalArgumentException("Invalid medium");
        }
    }

    /** A complete integrator state, including forces saved by post-processing for the next tick. */
    public record State(Vec3 position, Vec3 velocity, double time, double bodyPitch,
                        Vec3 nextExternalForce, boolean forcesReady) {
        public State {
            Objects.requireNonNull(position);
            Objects.requireNonNull(velocity);
            Objects.requireNonNull(nextExternalForce);
            if (!Double.isFinite(time) || time < 0 || !Double.isFinite(bodyPitch)) {
                throw new IllegalArgumentException("Invalid state");
            }
        }
    }

    @FunctionalInterface
    public interface SolidBlocks {
        /** Every true cell is a full unit cube, including cells at negative coordinates. */
        boolean isSolid(int x, int y, int z);

        /** Shapes retain the owning cell, including filler cells and overhanging fences. */
        default List<BlockShape> shapes(int x, int y, int z) {
            return boxes(x, y, z).stream().map(b -> new BlockShape(new Block(x, y, z), b)).toList();
        }

        /** Collision shapes in world coordinates; the default keeps the offline cube fixtures. */
        default List<Aabb> boxes(int x, int y, int z) {
            return isSolid(x, y, z) ? List.of(new Aabb(new Vec3(x, y, z), new Vec3(x + 1, y + 1, z + 1))) : List.of();
        }
    }

    /** Boxes must translate without changing size. Motion within each tick is swept linearly. */
    @FunctionalInterface
    public interface EntityBox {
        Aabb at(double secondsAfterRelease);
    }

    public record Entity(Object id, EntityBox box) {
        public Entity { Objects.requireNonNull(id); Objects.requireNonNull(box); }
    }
    public record Block(int x, int y, int z) { }
    public record BlockShape(Block block, Aabb box) { }
    public enum HitKind { ENTITY, BLOCK }

    /** Point is the projectile origin at contact, not a point on its box's leading face. */
    public record Collision(HitKind kind, Object object, Vec3 point, Vec3 normal, double time) { }
    public record Trace(List<State> samples, Optional<Collision> collision) {
        public Trace { samples = List.copyOf(samples); Objects.requireNonNull(collision); }
    }
    public record Closest(Vec3 point, double time, double distance) { }

    private final ProjectileParams params;
    private final double dt;
    private final Medium medium;

    public ArrowFlight(ProjectileParams params) { this(params, DEFAULT_DT); }
    public ArrowFlight(ProjectileParams params, double dt) { this(params, dt, Medium.AIR); }

    /**
     * Uses one medium for the whole flight. Fluid boundaries, partial immersion and swimming
     * need fluid geometry and are deliberately not approximated by a solid-block predicate.
     */
    public ArrowFlight(ProjectileParams params, double dt, Medium medium) {
        this.params = Objects.requireNonNull(params);
        this.medium = Objects.requireNonNull(medium);
        if (!Double.isFinite(dt) || dt <= 0) throw new IllegalArgumentException("Invalid step");
        this.dt = dt;
    }

    public double dt() { return dt; }
    public ProjectileParams params() { return params; }

    /**
     * Applies rotation offsets before rotating the local spawn offset, X then Y.
     * Yaw 0 faces -Z; positive pitch looks up. Sources:
     * modules/projectile/ProjectileModule.java:311; modules/projectile/config/ProjectileConfig.java:483;
     * modules/physics/util/PhysicsMath.java:208. The incoming direction is converted back to float
     * angles before offsets, as on the server. Roll does not affect velocity or spawn offset.
     */
    public State launch(Vec3 eyes, double yaw, double pitch) {
        Objects.requireNonNull(eyes);
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) throw new IllegalArgumentException("Invalid angles");
        Vec3 incoming = ServerAngles.inputDirection((float) yaw, (float) pitch);
        float spawnYaw = ServerAngles.normalize(ServerAngles.atan2(-incoming.x(), -incoming.z()));
        float spawnPitch = ServerAngles.atan2(incoming.y(), Math.sqrt(incoming.x() * incoming.x() + incoming.z() * incoming.z()));
        spawnYaw += (float) params.yawOffset();
        spawnPitch += (float) params.pitchOffset();
        yaw = spawnYaw;
        pitch = spawnPitch;
        Vec3 offset = params.spawnOffset();
        if (params.rotateOffsetByPitch()) {
            offset = new Vec3(offset.x(), offset.y() * Math.cos(pitch) - offset.z() * Math.sin(pitch),
                    offset.y() * Math.sin(pitch) + offset.z() * Math.cos(pitch));
        }
        if (params.rotateOffsetByYaw()) {
            offset = new Vec3(offset.x() * Math.cos(yaw) + offset.z() * Math.sin(yaw), offset.y(),
                    -offset.x() * Math.sin(yaw) + offset.z() * Math.cos(yaw));
        }
        Vec3 direction = ServerAngles.launchDirection(spawnYaw, spawnPitch);
        return new State(eyes.add(offset), direction.multiply(params.launchSpeed()), 0, pitch, Vec3.ZERO, false);
    }

    /**
     * Advances one fixed tick. Velocity changes before position. The first tick has no gravity/drag:
     * the server fills those fields in post-processing. Resistance is clipped before adding gravity.
     * VelocityDamped also saves a force parallel to velocity for the next tick; omitting it changes flight.
     * Sources: modules/projectile/config/StandardPhysicsProvider.java:154, :454, :479;
     * modules/projectile/system/StandardPhysicsTickSystem.java:95, :181, :327, :460;
     * modules/physics/util/PhysicsBodyStateUpdaterSymplecticEuler.java:14;
     * modules/physics/util/ForceAccumulator.java:22; ForceProviderStandard.java:32;
     * PhysicsMath.java:49, :75; PhysicsBodyStateUpdater.java:53 (all under modules/physics/util/).
     */
    public State step(State before) {
        Vec3 v = before.velocity();
        double dragRate = 0, gravity = 0;
        if (before.forcesReady()) {
            Vec3 size = params.hitbox().size();
            double air = params.gravity() / (params.terminalVelocityAir() * params.terminalVelocityAir());
            double water = params.gravity() / (params.terminalVelocityWater() * params.terminalVelocityWater());
            double multiplier = (water - air) / (params.densityWater() - params.densityAir());
            double coefficientPerMassArea = multiplier * medium.dragDensity() + air - multiplier * params.densityAir();
            double projectedAreaTimesSpeed = Math.abs(v.x()) * size.z() * size.y()
                    + Math.abs(v.y()) * size.z() * size.x() + Math.abs(v.z()) * size.x() * size.y();
            dragRate = Math.min(1 / dt, coefficientPerMassArea * projectedAreaTimesSpeed / (size.x() * size.z()));
            gravity = params.gravity() * (1 - medium.displacedDensity() / params.density());
        }
        Vec3 acceleration = before.nextExternalForce().multiply(1 / params.mass())
                .subtract(v.multiply(dragRate)).add(new Vec3(0, -gravity, 0));
        Vec3 next = v.add(acceleration.multiply(dt));
        next = new Vec3(clip(next.x()), clip(next.y()), clip(next.z()));
        float pitch = (float) before.bodyPitch();
        Vec3 externalForce = Vec3.ZERO;
        if (params.dampPitch() && next.x() * next.x() + next.z() * next.z() > 1e-10) {
            float desired = ServerAngles.atan2(next.y(), Math.sqrt(next.x() * next.x() + next.z() * next.z()));
            float delta = ServerAngles.normalize(desired - pitch);
            float limit = (float) (next.dot(next) * dt * params.speedRotationFactor());
            // The server assigns the target directly when damping does not limit the turn.
            // Reconstructing it as pitch + normalized delta loses float bits and feeds a
            // slightly different rotation force into subsequent ticks.
            if (delta > limit) {
                desired = pitch + limit;
                delta = limit;
            } else if (delta < -limit) {
                desired = pitch - limit;
                delta = -limit;
            }
            pitch = desired;
            externalForce = next.multiply(-delta * params.rotationForce());
        }
        return new State(before.position().add(next.multiply(dt)), next, before.time() + dt,
                pitch, externalForce, true);
    }

    private static double clip(double value) { return Math.abs(value) < 1e-6 ? 0 : value; }

    /**
     * Samples at fixed ticks and linearly interpolates the last segment; never changes the tick size.
     * A fractional-time result is a sample, not a state from which to resume the fixed-tick integrator.
     * Its velocity is the constant segment velocity, already updated at the beginning of that tick.
     */
    public State atTime(State initial, double seconds) {
        if (!Double.isFinite(seconds) || seconds < initial.time()) throw new IllegalArgumentException("Invalid time");
        State state = initial;
        while (state.time() < seconds) {
            State next = step(state);
            if (next.time() >= seconds) {
                double f = (seconds - state.time()) / dt;
                return new State(state.position().lerp(next.position(), f), next.velocity(), seconds,
                        next.bodyPitch(), next.nextExternalForce(), next.forcesReady());
            }
            state = next;
        }
        return state;
    }

    /**
     * Sweeps a step against entity boxes and the supplied block shapes. Entities move in relative coordinates.
     * The first contact wins; equal-time ties prefer entities, then list/cell order.
     * Block shapes may be partial boxes; entity boxes are supplied by the session collision adapter.
     */
    public Optional<Collision> collide(State before, State after, List<Entity> entities, SolidBlocks blocks) {
        Collision best = null;
        for (Entity entity : entities) {
            Aabb a = entity.box().at(before.time()), b = entity.box().at(after.time());
            if (a.size().subtract(b.size()).length() > 1e-9) throw new IllegalArgumentException("Entity box changed size");
            Vec3 relativeEnd = after.position().subtract(b.min().subtract(a.min()));
            var entry = a.forProjectile(params.hitbox()).intersect(before.position(), relativeEnd);
            if (entry.isPresent()) best = nearer(best, collision(before, after, entry.get(), HitKind.ENTITY, entity.id()));
        }
        if (blocks != null) {
            Vec3 from = before.position(), to = after.position();
            Aabb box = params.hitbox();
            int x0 = cell(Math.min(from.x(), to.x()) + box.min().x());
            int y0 = cell(Math.min(from.y(), to.y()) + box.min().y());
            int z0 = cell(Math.min(from.z(), to.z()) + box.min().z());
            int x1 = cell(Math.max(from.x(), to.x()) + box.max().x());
            int y1 = cell(Math.max(from.y(), to.y()) + box.max().y());
            int z1 = cell(Math.max(from.z(), to.z()) + box.max().z());
            // Include a cell whose maximum face exactly touches the swept minimum.
            if (x0 == Math.min(from.x(), to.x()) + box.min().x()) x0--;
            if (y0 == Math.min(from.y(), to.y()) + box.min().y()) y0--;
            if (z0 == Math.min(from.z(), to.z()) + box.min().z()) z0--;
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                for (BlockShape shape : blocks.shapes(x, y, z)) {
                    var entry = shape.box().forProjectile(box).intersect(from, to);
                    if (entry.isPresent()) best = nearer(best, collision(before, after, entry.get(), HitKind.BLOCK, shape.block()));
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static int cell(double value) {
        if (value <= Integer.MIN_VALUE + 1.0 || value >= Integer.MAX_VALUE - 1.0) {
            throw new IllegalArgumentException("Outside block coordinate range");
        }
        return (int) Math.floor(value);
    }

    private static Collision collision(State a, State b, Aabb.Entry entry, HitKind kind, Object object) {
        return new Collision(kind, object, a.position().lerp(b.position(), entry.fraction()), entry.normal(),
                a.time() + (b.time() - a.time()) * entry.fraction());
    }

    private static Collision nearer(Collision a, Collision b) { return a == null || b.time() < a.time() ? b : a; }

    /**
     * Returns fixed-step samples through the requested horizon, stopping on the first contact.
     * A final fractional-time or contact sample is not a resumable integrator state.
     */
    public Trace trace(State initial, double duration, List<Entity> entities, SolidBlocks blocks) {
        if (!Double.isFinite(duration) || duration < 0) throw new IllegalArgumentException("Invalid duration");
        List<State> states = new ArrayList<>();
        states.add(initial);
        State state = initial;
        double end = initial.time() + duration;
        while (state.time() < end) {
            State next = step(state);
            if (next.time() > end) next = new State(state.position().lerp(next.position(), (end - state.time()) / dt),
                    next.velocity(), end, next.bodyPitch(), next.nextExternalForce(), true);
            Optional<Collision> hit = collide(state, next, entities, blocks);
            if (hit.isPresent()) {
                Collision c = hit.get();
                states.add(new State(c.point(), next.velocity(), c.time(), next.bodyPitch(), next.nextExternalForce(), true));
                return new Trace(states, hit);
            }
            states.add(next);
            state = next;
        }
        return new Trace(states, Optional.empty());
    }

    /** Finds the closest point on the piecewise linear path, including between ticks. */
    public Closest closest(State initial, Vec3 point, double duration) {
        if (!Double.isFinite(duration) || duration < 0) throw new IllegalArgumentException("Invalid duration");
        Closest best = new Closest(initial.position(), initial.time(), initial.position().subtract(point).length());
        State a = initial;
        double end = initial.time() + duration;
        while (a.time() < end) {
            State b = step(a);
            Vec3 delta = b.position().subtract(a.position());
            double upper = Math.min(1, (end - a.time()) / dt);
            double f = delta.dot(delta) == 0 ? 0 : Math.max(0, Math.min(upper, point.subtract(a.position()).dot(delta) / delta.dot(delta)));
            Vec3 candidate = a.position().add(delta.multiply(f));
            double distance = candidate.subtract(point).length();
            if (distance < best.distance()) best = new Closest(candidate, a.time() + f * dt, distance);
            a = b;
        }
        return best;
    }
}
