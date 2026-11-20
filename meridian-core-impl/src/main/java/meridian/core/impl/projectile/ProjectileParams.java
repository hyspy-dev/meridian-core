package meridian.core.impl.projectile;

import java.util.Objects;
import meridian.protocol.PhysicsType;
import meridian.protocol.ProjectileConfig;
import meridian.protocol.RotationMode;

/**
 * Free-flight parameters for standard projectiles, up to the first solid contact.
 * Angles are radians. The box stays axis-aligned as in release 0.7.
 * Bounces, rolling and the swimming rest state are outside this model.
 */
public record ProjectileParams(double launchSpeed, double gravity, double density,
        double terminalVelocityAir, double terminalVelocityWater, double densityAir, double densityWater,
        Aabb hitbox, Vec3 spawnOffset, double yawOffset, double pitchOffset,
        boolean rotateOffsetByPitch, boolean rotateOffsetByYaw,
        boolean dampPitch, double rotationForce, double speedRotationFactor, double hitWaterImpulseLoss) {

    public ProjectileParams {
        Objects.requireNonNull(hitbox);
        Objects.requireNonNull(spawnOffset);
        double[] values = {launchSpeed, gravity, density, terminalVelocityAir, terminalVelocityWater,
                densityAir, densityWater, yawOffset, pitchOffset, rotationForce,
                speedRotationFactor, hitWaterImpulseLoss};
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite parameter");
        }
        Vec3 size = hitbox.size();
        if (launchSpeed <= 0 || gravity < 0 || density <= 0 || terminalVelocityAir <= 0
                || terminalVelocityWater <= 0 || densityAir < 0 || densityWater <= densityAir
                || size.x() <= 0 || size.y() <= 0 || size.z() <= 0 || rotationForce < 0
                || speedRotationFactor < 0 || hitWaterImpulseLoss < 0 || hitWaterImpulseLoss > 1) {
            throw new IllegalArgumentException("Invalid projectile parameters");
        }
    }

    /**
     * Copies the 0.7 packet fields. Model.hitbox already includes Model.scale
     * (server asset/type/model/config/Model.java:170, :512); spawnScale is the
     * chosen projectile scale, normally 1 for arrows. No 0.8 hitbox fields are used.
     */
    public static ProjectileParams fromConfig(ProjectileConfig config, double spawnScale) {
        Objects.requireNonNull(config);
        var p = Objects.requireNonNull(config.physicsConfig, "physicsConfig");
        if (p.type != PhysicsType.Standard) throw new IllegalArgumentException("Standard physics required");
        var model = Objects.requireNonNull(config.model, "model");
        var b = Objects.requireNonNull(model.hitbox, "model.hitbox");
        var offset = config.spawnOffset;
        var rot = config.rotationOffset;
        return new ProjectileParams(config.launchForce, p.gravity, p.density,
                p.terminalVelocityAir, p.terminalVelocityWater, p.densityAir, p.densityWater,
                new Aabb(new Vec3(b.minX, b.minY, b.minZ), new Vec3(b.maxX, b.maxY, b.maxZ)).scale(spawnScale),
                offset == null ? Vec3.ZERO : new Vec3(offset.x(), offset.y(), offset.z()),
                rot == null ? 0 : rot.yaw, rot == null ? 0 : rot.pitch,
                config.rotateSpawnOffsetByPitch, config.rotateSpawnOffsetByYaw,
                p.computePitch && p.rotationMode == RotationMode.VelocityDamped,
                p.rotationForce, p.speedRotationFactor, p.hitWaterImpulseLoss);
    }

    /** Copies a fixed-scale packet; variable-scale projectiles require an explicit spawn scale. */
    public static ProjectileParams fromConfig(ProjectileConfig config) {
        if (config.minScale != config.maxScale) throw new IllegalArgumentException("Choose a spawn scale");
        return fromConfig(config, config.minScale);
    }

    double mass() {
        Vec3 s = hitbox.size();
        return s.x() * s.y() * s.z() * density;
    }
}
