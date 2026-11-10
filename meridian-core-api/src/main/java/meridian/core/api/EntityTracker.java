package meridian.core.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * The item a dropped item on the ground is - the id of the stack its {@code Item} component
     * carries - or empty for an entity that is no dropped item. The server takes it away once it is
     * picked up.
     */
    default Optional<String> itemOf(int entityId) {
        return Optional.empty();
    }

    /** How many items a dropped item on the ground is - its stack's size; {@code 0} for anything else. */
    default int itemQuantityOf(int entityId) {
        return 0;
    }

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
     * The entities within {@code range} of the local player, nearest first, the player itself left
     * out - measured from the player's feet to where each entity stands. Empty while the player's
     * position is unknown.
     *
     * @param range       how far out, in blocks
     * @param playersOnly only {@linkplain #isPlayer players}
     * @return network ids, nearest first
     */
    default List<Integer> nearby(double range, boolean playersOnly) {
        Optional<Vec3> me = localPosition();
        if (me.isEmpty() || !(range >= 0)) {
            return List.of();
        }
        OptionalInt self = localEntityId();
        double rangeSq = range * range;
        Map<Integer, Double> found = new HashMap<>();
        for (int id : trackedEntities()) {
            if (self.isPresent() && self.getAsInt() == id || playersOnly && !isPlayer(id)) {
                continue;
            }
            Optional<Vec3> at = positionOf(id);
            if (at.isEmpty()) {
                continue;
            }
            double dSq = at.get().distanceSqTo(me.get());
            if (dSq <= rangeSq) {
                found.put(id, dSq);
            }
        }
        List<Integer> out = new ArrayList<>(found.keySet());
        out.sort(Comparator.<Integer>comparingDouble(found::get).thenComparingInt(id -> id));
        return out;
    }

    /**
     * A name to show for an entity: its {@linkplain #nameplateOf nameplate} (a player's name, a
     * named NPC's), else its species from {@linkplain #entityModelAssetId its model}
     * ({@code Sheep}), else the last part of its {@linkplain #entityTypeOf model path} - the folder
     * when the file is the shared {@code Model} mesh - else {@code "?"}.
     */
    default String labelOf(int entityId) {
        Optional<String> nameplate = nameplateOf(entityId);
        if (nameplate.isPresent() && !nameplate.get().isBlank()) {
            return nameplate.get();
        }
        Optional<String> asset = entityModelAssetId(entityId);
        if (asset.isPresent() && !asset.get().isBlank()) {
            return lastName(asset.get());
        }
        Optional<String> type = entityTypeOf(entityId);
        return type.isPresent() && !type.get().isBlank() ? lastName(type.get()) : "?";
    }

    /**
     * The readable last part of a model path: {@code Creatures/Cow/Model.blockymodel} is
     * {@code Cow}, {@code Characters/Player.blockymodel} is {@code Player}, {@code Sheep} is itself.
     */
    private static String lastName(String path) {
        String[] parts = path.split("/");
        int last = parts.length - 1;
        String name = parts[last];
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        if ((name.isEmpty() || name.equalsIgnoreCase("Model")) && last >= 1) {
            name = parts[last - 1];
        }
        return name;
    }

    /**
     * The box the server collides entity {@code entityId} by, where it stands now: its model's
     * hitbox as the server sent it, sized by the entity's own scale the way the server sizes it,
     * around its {@linkplain #positionOf position} (the feet). Standing - a crouch or a seat lowers
     * the real box, which the server does not say.
     *
     * <p>Empty until the server has sent the entity's model with a hitbox, and for a model that has
     * none. A core too old to tell answers empty.
     */
    default Optional<Box> boundsOf(int entityId) {
        return Optional.empty();
    }

    /**
     * Network id of the entity the local player is looking at — the tracked
     * entity whose direction from the player is within {@code maxAngleDeg} of
     * the look vector and no farther than {@code maxRange} blocks. The closest
     * angular match wins. Best-effort: depends on an observed look orientation.
     */
    OptionalInt entityInCrosshair(double maxAngleDeg, double maxRange);
}
