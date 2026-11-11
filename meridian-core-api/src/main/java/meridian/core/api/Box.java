package meridian.core.api;

/**
 * A box in the world, its sides along the axes: from {@code min} to {@code max}, both corners in
 * world coordinates.
 */
public record Box(Vec3 min, Vec3 max) {

    /** The middle of the box. */
    public Vec3 center() {
        return new Vec3((min.x() + max.x()) / 2, (min.y() + max.y()) / 2, (min.z() + max.z()) / 2);
    }

    /** How wide it is, along x. */
    public double width() {
        return max.x() - min.x();
    }

    /** How tall it is, along y. */
    public double height() {
        return max.y() - min.y();
    }

    /** How deep it is, along z. */
    public double depth() {
        return max.z() - min.z();
    }
}
