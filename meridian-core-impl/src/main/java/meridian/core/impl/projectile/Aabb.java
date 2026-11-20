package meridian.core.impl.projectile;

import java.util.Objects;
import java.util.Optional;

/** An axis-aligned box. Projectile boxes are local to their position. */
public record Aabb(Vec3 min, Vec3 max) {
    public Aabb {
        Objects.requireNonNull(min);
        Objects.requireNonNull(max);
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Inverted box");
        }
    }

    public Vec3 size() { return max.subtract(min); }
    public Vec3 center() { return min.lerp(max, 0.5); }
    public Aabb translate(Vec3 p) { return new Aabb(min.add(p), max.add(p)); }
    public Aabb scale(double k) {
        if (!(k > 0) || !Double.isFinite(k)) throw new IllegalArgumentException("Invalid scale");
        return new Aabb(min.multiply(k), max.multiply(k));
    }

    /** Expands this obstacle by the reflected local projectile box (Minkowski sum). */
    public Aabb forProjectile(Aabb projectile) {
        return new Aabb(min.subtract(projectile.max), max.subtract(projectile.min));
    }

    /** A fraction of a segment and its outward entry normal; zero normal means initial overlap. */
    public record Entry(double fraction, Vec3 normal) { }

    /** Uses the slab method, including parallel segments, touching faces and initial overlap. */
    public Optional<Entry> intersect(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double enter = 0, exit = 1;
        Vec3 normal = Vec3.ZERO;
        for (int i = 0; i < 3; i++) {
            double d = delta.axis(i), p = from.axis(i);
            if (d == 0) {
                if (p < min.axis(i) || p > max.axis(i)) return Optional.empty();
                continue;
            }
            double a = (min.axis(i) - p) / d, b = (max.axis(i) - p) / d;
            double lo = Math.min(a, b), hi = Math.max(a, b);
            if (lo >= enter && lo >= 0) {
                enter = lo;
                double sign = d > 0 ? -1 : 1;
                normal = new Vec3(i == 0 ? sign : 0, i == 1 ? sign : 0, i == 2 ? sign : 0);
            }
            exit = Math.min(exit, hi);
            if (enter > exit) return Optional.empty();
        }
        return Optional.of(new Entry(enter, normal));
    }
}
