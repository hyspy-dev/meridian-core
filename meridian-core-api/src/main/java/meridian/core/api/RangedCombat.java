package meridian.core.api;

import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Ordinary bow shots, from drawing to the first projectile contact. The caller holds the body
 * still with {@link Movement}; core reports the aim before releasing and keeps the bow equipped
 * until contact. Angles, charge tiers and flight parameters come from the session's assets.
 */
public interface RangedCombat {
    enum Status { HIT, MISS, NO_BOW, NO_AMMO, UNKNOWN_TARGET, UNREACHABLE, UNAVAILABLE, BUSY, CANCELLED, TIMED_OUT }

    /**
     * HIT describes simulated contact with an entity, not a promise of damage. damage is the
     * observed health decrease, or NaN when the server did not share health. primary and impact
     * are the server's interaction outcomes; either may be null when nothing was sent.
     */
    record Shot(Status status, String reason, UUID projectile, int entity, Vec3 contact,
                double chargeSeconds, double flightSeconds, double damage,
                InteractionOutcome primary, InteractionOutcome impact) { }

    /** An intact ordinary bow with ammunition in the hotbar, if its assets are supported. */
    OptionalInt bowSlot();

    /** Cancels an unreleased draw. An arrow already released continues to its real contact. */
    void cancel();

    /** Selects the shortest usable draw and leads the target using its observed motion. */
    CompletableFuture<Shot> shoot(int entityId);

    /** Aims at a world point. A blocked or unknown path is refused before drawing. */
    CompletableFuture<Shot> shoot(Vec3 point);
}
