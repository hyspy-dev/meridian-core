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

    /**
     * Asset/prefab path that identifies what kind of entity this is — populated
     * from observed {@code ModelUpdate.model.path}. Stable across instances of
     * the same species, so callers can group/filter entities by type (e.g. a
     * minimap mob-hide list, or ESP per-species colouring).
     *
     * <p>Empty until the server has sent at least one {@code ModelUpdate} for
     * the entity; some entities (raw projectiles, server-side-only) may never
     * receive one.
     */
    Optional<String> entityTypeOf(int entityId);

    /**
     * Model asset id from observed {@code Model.assetId} — the registered model
     * asset that identifies the species/variant (e.g. {@code Creatures/Cow}).
     * More specific than {@link #entityTypeOf}, whose path is the shared blocky
     * mesh ({@code Models/Model.blockymodel}) and so is the same across species.
     *
     * <p>Empty until a {@code ModelUpdate} with a non-empty asset id is seen.
     */
    Optional<String> entityModelAssetId(int entityId);

    /**
     * Nameplate text shown above the entity — the player's username for players,
     * or a custom display name for named entities. Populated from observed
     * {@code NameplateUpdate} components.
     *
     * <p>Empty until the server has sent a nameplate for the entity; most mobs
     * and props never get one, so callers should fall back to
     * {@link #entityTypeOf} for an identifying label.
     */
    Optional<String> nameplateOf(int entityId);

    /**
     * Whether entity {@code entityId} is a player — true once the server has
     * sent a {@code PlayerSkin} component for it. Players are the only entities
     * that carry a skin, so this is a definitive signal independent of the
     * model path (handy for a players-only ESP filter).
     *
     * <p>False for non-players and for players whose skin component hasn't been
     * observed yet; sticky once true (the local player included).
     */
    boolean isPlayer(int entityId);

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
