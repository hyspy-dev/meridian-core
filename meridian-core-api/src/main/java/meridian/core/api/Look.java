package meridian.core.api;

/**
 * Where a player looks: a yaw and a pitch, radians, the way the client sends them
 * ({@code ClientMovement.lookOrientation}) and the server aims by them.
 *
 * <p>Yaw 0 faces -z and a quarter turn ({@code π/2}) faces -x; pitch 0 is level, above 0 looks up,
 * below 0 down. The look's direction is {@code (-sin yaw · cos pitch, sin pitch, -cos yaw · cos pitch)}
 * - the server's own ({@code Transform.getDirection}) - and {@link #at} is its inverse, as the
 * server works it out ({@code Rotation3f.lookAt}).
 *
 * <pre>{@code
 * Look look = Look.at(player.eyePosition(), new Vec3(x + 0.5, y + 0.5, z + 0.5));
 * Vec3 ahead = look.direction();
 * }</pre>
 *
 * @param yaw   the turn about the vertical, radians
 * @param pitch up or down, radians
 */
public record Look(float yaw, float pitch) {

    /**
     * The steepest look: the server keeps a look it works out this far short of straight up or
     * down ({@code MathUtil.PITCH_EDGE_PADDING}, 0.01), where the yaw would no longer say anything.
     */
    public static final float MAX_PITCH = (float) (Math.PI / 2 - 0.01);

    /**
     * The look from {@code from} - the eyes - at {@code to}. Straight up or down, where the yaw
     * does not matter, the yaw is 0; the pitch stays within {@link #MAX_PITCH}.
     *
     * @throws IllegalArgumentException when the two points are the same
     */
    public static Look at(Vec3 from, Vec3 to) {
        return along(to.x() - from.x(), to.y() - from.y(), to.z() - from.z());
    }

    /**
     * The look along a direction (any length). Straight up or down the yaw is 0; the pitch stays
     * within {@link #MAX_PITCH}.
     *
     * @throws IllegalArgumentException for a direction of no length
     */
    public static Look along(double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 1e-9)) {
            throw new IllegalArgumentException("no direction to look along");
        }
        float pitch = (float) Math.asin(Math.max(-1, Math.min(1, dy / len)));
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
        return new Look(yawAlong(dx, dz), pitch);
    }

    /**
     * The yaw that faces along {@code (dx, dz)} on the ground plane - where a body walking that
     * way faces; 0 when both are 0.
     */
    public static float yawAlong(double dx, double dz) {
        return dx == 0 && dz == 0 ? 0f : (float) Math.atan2(-dx, -dz);
    }

    /** The unit vector this look points along. */
    public Vec3 direction() {
        double cosPitch = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * cosPitch, Math.sin(pitch), -Math.cos(yaw) * cosPitch);
    }
}
