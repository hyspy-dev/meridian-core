package meridian.core.api;

/**
 * Immutable world position — the neutral, protocol-free vector type meridian-core
 * exposes to Layer-2 modules. Hytale's wire {@code Position} never crosses this
 * boundary.
 */
public record Vec3(double x, double y, double z) {

    /** Squared distance to {@code o} — cheaper than {@link #distanceTo} for comparisons. */
    public double distanceSqTo(Vec3 o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceTo(Vec3 o) {
        return Math.sqrt(distanceSqTo(o));
    }
}
