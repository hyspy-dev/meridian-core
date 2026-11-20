package meridian.core.impl.projectile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds a low arc with the same discrete model used to check the resulting shot.
 * Target motion is constant velocity plus optional downward acceleration, including the release delay.
 * The model predicts no landing, target steering, or shooter movement before release.
 */
public final class AimSolver {
    /** Position is the anchor of the local box; gravity is zero for a grounded target. */
    public record Target(Vec3 position, Vec3 velocity, Aabb box, double gravity) {
        public Target {
            Objects.requireNonNull(position);
            Objects.requireNonNull(velocity);
            Objects.requireNonNull(box);
            if (!Double.isFinite(gravity) || gravity < 0) throw new IllegalArgumentException("Invalid target gravity");
        }
        public Vec3 positionAt(double time) {
            return position.add(velocity.multiply(time)).add(new Vec3(0, -0.5 * gravity * time * time, 0));
        }
        public Aabb boxAt(double time) { return box.translate(positionAt(time)); }
    }

    public enum Failure { NONE, NO_LOW_ARC_WITHIN_LIMITS, DID_NOT_CONVERGE, VERIFICATION_MISSED, BLOCKED }

    /**
     * Flight time ends at first swept box contact; total time also includes release delay.
     * Angles are the tested packet look angles, before projectile rotation offsets.
     */
    public record Solution(double yaw, double pitch, double flightTime, double totalTime,
                           Vec3 contact, int iterations) { }

    public record Result(Optional<Solution> solution, Failure failure) {
        public Result {
            Objects.requireNonNull(solution);
            Objects.requireNonNull(failure);
            if (solution.isPresent() != (failure == Failure.NONE)) throw new IllegalArgumentException("Inconsistent result");
        }
        static Result failed(Failure failure) { return new Result(Optional.empty(), failure); }
    }

    private record Arc(double yaw, double pitch, double time, Vec3 point) { }
    private final ArrowFlight flight;
    private final double maxFlightTime;
    private final int maxIterations;

    public AimSolver(ProjectileParams params) { this(new ArrowFlight(params), 8, 24); }

    /** Limits are part of the search contract: failure does not prove global physical impossibility. */
    public AimSolver(ArrowFlight flight, double maxFlightTime, int maxIterations) {
        this.flight = Objects.requireNonNull(flight);
        if (!Double.isFinite(maxFlightTime) || maxFlightTime <= 0 || maxIterations < 1) {
            throw new IllegalArgumentException("Invalid solver limits");
        }
        this.maxFlightTime = maxFlightTime;
        this.maxIterations = maxIterations;
    }

    public Result solve(Vec3 eyes, Target target, double releaseDelay) {
        return solve(eyes, target, releaseDelay, null);
    }

    /**
     * Iterates flight time and future target center, then verifies against the moving box and blocks.
     * Convergence allows 1 ms and 0.01 blocks because the server's angle lookup has finite resolution.
     */
    public Result solve(Vec3 eyes, Target target, double releaseDelay, ArrowFlight.SolidBlocks blocks) {
        Objects.requireNonNull(eyes);
        Objects.requireNonNull(target);
        if (!Double.isFinite(releaseDelay) || releaseDelay < 0) throw new IllegalArgumentException("Invalid release delay");
        double time = target.boxAt(releaseDelay).center().subtract(eyes).length() / flight.params().launchSpeed();
        for (int i = 1; i <= maxIterations; i++) {
            Vec3 predicted = target.boxAt(releaseDelay + time).center();
            Arc arc = lowArc(eyes, predicted);
            if (arc == null) return Result.failed(Failure.NO_LOW_ARC_WITHIN_LIMITS);
            Vec3 corrected = target.boxAt(releaseDelay + arc.time()).center();
            if (Math.abs(arc.time() - time) < 0.001 && corrected.subtract(predicted).length() < 0.01) {
                var entity = new ArrowFlight.Entity(target, t -> target.boxAt(releaseDelay + t));
                var trace = flight.trace(flight.launch(eyes, arc.yaw(), arc.pitch()),
                        Math.min(maxFlightTime, arc.time() + flight.dt()), List.of(entity), blocks);
                if (trace.collision().isEmpty()) return Result.failed(Failure.VERIFICATION_MISSED);
                var contact = trace.collision().get();
                if (contact.kind() != ArrowFlight.HitKind.ENTITY) return Result.failed(Failure.BLOCKED);
                return new Result(Optional.of(new Solution(arc.yaw(), arc.pitch(), contact.time(),
                        releaseDelay + contact.time(), contact.point(), i)), Failure.NONE);
            }
            time = arc.time();
        }
        return Result.failed(Failure.DID_NOT_CONVERGE);
    }

    /** Scans from downward to upward pitch and refines the first crossing, choosing the low branch. */
    private Arc lowArc(Vec3 eyes, Vec3 target) {
        double distance = Math.hypot(target.x() - eyes.x(), target.z() - eyes.z());
        // The horizontal-plane shooting method is ill-conditioned for a vertical shot.
        if (distance < 0.01) return null;
        Arc previous = null;
        double minPitch = -Math.PI / 2 + 0.015;
        double maxPitch = Math.PI / 2 - 0.015;
        for (int i = 0; i <= 160; i++) {
            double pitch = minPitch + (maxPitch - minPitch) * i / 160;
            Arc current = crossing(eyes, target, pitch);
            if (current != null && Math.abs(current.point().y() - target.y()) < 1e-6) return current;
            if (previous != null && current != null && previous.point().y() <= target.y() && current.point().y() >= target.y()) {
                Arc lo = previous, hi = current;
                for (int n = 0; n < 36; n++) {
                    Arc mid = crossing(eyes, target, (lo.pitch() + hi.pitch()) * 0.5);
                    if (mid == null) return null;
                    if (Math.abs(mid.point().y() - target.y()) < 1e-6) return mid;
                    if (mid.point().y() < target.y()) lo = mid; else hi = mid;
                }
                return hi;
            }
            previous = current;
        }
        return null;
    }

    /** Corrects yaw for the rotated spawn offset, then samples where the horizontal target plane is crossed. */
    private Arc crossing(Vec3 eyes, Vec3 target, double pitch) {
        Vec3 horizontal = new Vec3(target.x() - eyes.x(), 0, target.z() - eyes.z());
        double distance = horizontal.length();
        Vec3 direction = horizontal.multiply(1 / distance);
        Vec3 side = new Vec3(-direction.z(), 0, direction.x());
        double yaw = Math.atan2(-horizontal.x(), -horizontal.z()) - flight.params().yawOffset();
        Arc best = null;
        double bestError = Double.POSITIVE_INFINITY;
        for (int correction = 0; correction < 6; correction++) {
            ArrowFlight.State a = flight.launch(eyes, yaw, pitch);
            double progress = a.position().subtract(eyes).dot(direction);
            while (a.time() < maxFlightTime) {
                ArrowFlight.State b = flight.step(a);
                double nextProgress = b.position().subtract(eyes).dot(direction);
                if (nextProgress >= distance && progress <= distance) {
                    double f = (distance - progress) / (nextProgress - progress);
                    double time = a.time() + flight.dt() * f;
                    if (time > maxFlightTime) return null;
                    Vec3 point = a.position().lerp(b.position(), f);
                    double lateral = point.subtract(target).dot(side);
                    if (Math.abs(lateral) < bestError) {
                        bestError = Math.abs(lateral);
                        best = new Arc(yaw, pitch, time, point);
                    }
                    if (Math.abs(lateral) < 1e-6) return new Arc(yaw, pitch, time, point);
                    yaw += Math.atan2(lateral, distance);
                    break;
                }
                if (nextProgress <= progress) return null;
                a = b;
                progress = nextProgress;
            }
        }
        // Lookup angles have finite resolution. The final swept-box verification decides whether this is enough.
        return best;
    }

}
