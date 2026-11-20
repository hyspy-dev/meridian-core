package meridian.core.impl.projectile;

/** An immutable vector in blocks, or blocks per second. */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Vector must be finite");
        }
    }

    public Vec3 add(Vec3 b) { return new Vec3(x + b.x, y + b.y, z + b.z); }
    public Vec3 subtract(Vec3 b) { return new Vec3(x - b.x, y - b.y, z - b.z); }
    public Vec3 multiply(double k) { return new Vec3(x * k, y * k, z * k); }
    public double dot(Vec3 b) { return x * b.x + y * b.y + z * b.z; }
    public double length() { return Math.sqrt(dot(this)); }
    public Vec3 lerp(Vec3 b, double t) { return add(b.subtract(this).multiply(t)); }
    double axis(int i) { return i == 0 ? x : i == 1 ? y : z; }
}
