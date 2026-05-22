package meridian.core.api;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Live registry of entities the server has spawned for this client — a
 * meridian-core Layer-1 service.
 *
 * <p>Built by observing S2C {@code EntityUpdates}: each entity's network id and
 * last-known {@code Transform} position are kept current, and ids in a packet's
 * {@code removed} list are dropped. The local player's own position and look
 * direction come from observed C2S {@code ClientMovement}.
 *
 * <p>Its main job is target selection for {@link CameraControl#followEntity} and
 * {@link CameraControl#entityView}.
 */
public interface EntityTracker {

    /** Network id of the client's own entity, if {@code SetClientId} was seen. */
    OptionalInt localEntityId();

    /** Last-known position of entity {@code entityId}. */
    Optional<Vec3> positionOf(int entityId);

    /** Last-known position of the local player. */
    Optional<Vec3> localPosition();

    /** Number of entities currently tracked. */
    int trackedCount();

    /** Network ids of every currently tracked entity (includes the local player). */
    Set<Integer> trackedEntities();

    /**
     * Network id of the entity nearest the local player, excluding the player
     * itself. Empty if nothing is tracked or the player position is unknown.
     */
    OptionalInt nearestEntity();

    /**
     * Network id of the entity the local player is looking at — the tracked
     * entity whose direction from the player is within {@code maxAngleDeg} of
     * the look vector and no farther than {@code maxRange} blocks. The closest
     * angular match wins. Best-effort: depends on an observed look orientation.
     */
    OptionalInt entityInCrosshair(double maxAngleDeg, double maxRange);
}
