package meridian.core.impl.projectile;

import java.util.Optional;
import java.util.function.Function;

/** The ordinary Weapon_Shortbow_Adamantite primary graph in release Assets.zip (0.7). */
public final class ShortbowCharge {
    private static final float[] THRESHOLDS = {0.1f, 0.3f, 0.6f, 0.9f, 1.2f};
    private static final double[] SPEEDS = {12, 22, 35, 50, 85};
    private static final double[] GRAVITIES = {45, 40, 32, 28, 25};

    private ShortbowCharge() { }

    /**
     * Selects the greatest graph key not above packet charge seconds, or -1 for no arrow.
     * Both keys and charge use floats, as in server ChargingInteraction.java:197, :374.
     */
    public static int tier(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Invalid charge");
        float packetCharge = (float) seconds;
        for (int i = THRESHOLDS.length - 1; i >= 0; i--) if (packetCharge >= THRESHOLDS[i]) return i;
        return -1;
    }

    /** The config name after resolving Adamantite's inherited InteractionVars, without numeric asset IDs. */
    public static Optional<String> configName(double seconds) {
        int tier = tier(seconds);
        return tier < 0 ? Optional.empty() : Optional.of("Projectile_Config_Arrow_Shortbow_Strength_" + tier);
    }

    /** Resolves parameters supplied by the caller's asset catalog. */
    public static Optional<ProjectileParams> select(double seconds, Function<String, ProjectileParams> configs) {
        return configName(seconds).map(configs);
    }

    /**
     * Offline Adamantite preset. Adamantite changes damage, not the inherited primary launch configs.
     * Sources: Items/Weapon/Shortbow/Weapon_Shortbow_Adamantite.json and Template_Weapon_Shortbow.json;
     * Interactions/Weapons/Shortbow/Primary/Shoot/Weapon_Shortbow_Primary_Shoot_Charge.json;
     * ProjectileConfigs/Weapons/Shortbow/Projectile_Config_Arrow_Shortbow_Strength_0..4.json;
     * ProjectileConfigs/Weapons/Arrows/Projectile_Config_Arrow_Base.json; Models/Projectiles/Weapons/Arrow/Arrow_Crude.json.
     * All paths start at Server/ in release Assets.zip. This is not a preset for every bow or signature volley.
     */
    public static Optional<ProjectileParams> adamantite(double seconds) {
        int tier = tier(seconds);
        if (tier < 0) return Optional.empty();
        return Optional.of(new ProjectileParams(SPEEDS[tier], GRAVITIES[tier], 700, 50, 15, 1.2, 998,
                new Aabb(new Vec3(-0.075, -0.075, -0.075), new Vec3(0.075, 0.075, 0.075)),
                new Vec3(0.15f, -0.25f, 0), 0.25f * (ServerAngles.PI / 180), 2f * (ServerAngles.PI / 180),
                true, true, true, 3, 2, 0.2));
    }
}
