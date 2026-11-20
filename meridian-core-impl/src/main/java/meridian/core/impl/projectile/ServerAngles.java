package meridian.core.impl.projectile;

/**
 * Float angle arithmetic used by release 0.7. Sources under HytaleServer/Math/src/main/java/
 * com/hypixel/hytale/math/: util/TrigMathUtil.java:43, :59, :120 and vector/Transform.java:295.
 * The atan lookup value is computed on demand at the same quantized index.
 */
final class ServerAngles {
    static final float PI = 3.1415927f;
    private static final float TWO_PI = PI * 2;
    private static final float TO_INDEX = 4096 / TWO_PI;
    private static final float[] SIN = new float[4096];
    private static final float[] COS = new float[4096];

    static {
        for (int i = 0; i < 4096; i++) {
            float angle = (i + 0.5f) / 4096 * TWO_PI;
            SIN[i] = (float) Math.sin(angle);
            COS[i] = (float) Math.cos(angle);
        }
        for (int degrees = 0; degrees < 360; degrees += 90) {
            int index = (int) (degrees * (4096 / 360f)) & 4095;
            SIN[index] = (float) Math.sin(degrees * Math.PI / 180);
            COS[index] = (float) Math.cos(degrees * Math.PI / 180);
        }
    }

    private ServerAngles() { }
    static float sin(float angle) { return SIN[(int) (angle * TO_INDEX) & 4095]; }
    static float cos(float angle) { return COS[(int) (angle * TO_INDEX) & 4095]; }

    static float normalize(float angle) {
        angle %= TWO_PI;
        if (angle < 0) angle += TWO_PI;
        if (angle >= PI) angle -= TWO_PI;
        return angle;
    }

    private static float atanValue(float ratio) {
        int index = (int) (ratio * 100000);
        return (float) Math.atan2((double) index / 100000, 1);
    }

    static float atan2(double dy, double dx) {
        float y = (float) dy, x = (float) dx;
        if (y < 0) {
            if (x < 0) {
                return y < x ? -atanValue(x / y) - PI / 2 : atanValue(y / x) - PI;
            }
            y = -y;
            return y > x ? atanValue(x / y) - PI / 2 : -atanValue(y / x);
        }
        if (x < 0) {
            x = -x;
            return y > x ? atanValue(x / y) + PI / 2 : -atanValue(y / x) + PI;
        }
        return y > x ? -atanValue(x / y) + PI / 2 : atanValue(y / x);
    }

    /** Transform.getDirection uses double products, unlike PhysicsMath.vectorFromAngles. */
    static Vec3 inputDirection(float yaw, float pitch) {
        double horizontal = cos(pitch);
        return new Vec3(horizontal * -sin(yaw), sin(pitch), horizontal * -cos(yaw));
    }

    /** PhysicsMath.vectorFromAngles keeps these products as floats. */
    static Vec3 launchDirection(float yaw, float pitch) {
        float horizontal = cos(pitch);
        return new Vec3(-sin(yaw) * horizontal, sin(pitch), -cos(yaw) * horizontal);
    }
}
