package meridian.core.impl;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import meridian.core.api.Box;
import meridian.core.api.EntityTracker;
import meridian.core.api.Look;
import meridian.core.api.Vec3;
import meridian.protocol.ComponentUpdate;
import meridian.core.impl.interaction.EntityTarget;
import meridian.protocol.Direction;
import meridian.protocol.EffectOp;
import meridian.protocol.EntityStatUpdate;
import meridian.protocol.EntityStatsUpdate;
import meridian.protocol.EquipmentUpdate;
import meridian.protocol.EntityEffectUpdate;
import meridian.protocol.EntityEffectsUpdate;
import meridian.protocol.InteractionType;
import meridian.protocol.InteractionsUpdate;
import meridian.protocol.ItemUpdate;
import meridian.protocol.ModelTransform;
import meridian.protocol.ModelUpdate;
import meridian.protocol.NameplateUpdate;
import meridian.protocol.PlayerSkinUpdate;
import meridian.protocol.Position;
import meridian.protocol.TransformUpdate;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.SetClientId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live {@link EntityTracker} — server truth assembled from observed traffic.
 *
 * <p>Fed by {@link ServerObserver} (S2C {@code SetClientId} / {@code EntityUpdates})
 * and {@link ClientObserver} (C2S {@code ClientMovement}). Holds only the data
 * camera target-selection needs: per-entity position plus the local player's
 * position and look orientation.
 */
final class EntityTrackerImpl implements EntityTracker {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    private final Map<Integer, Vec3> positions = new ConcurrentHashMap<>();
    /**
     * Per-entity asset path from observed {@code ModelUpdate.model.path}.
     * Sticky once known — Hytale only resends the Model component when the
     * entity transforms (mount, polymorph, etc.); the last value remains
     * authoritative for the species filter / type-aware overlays.
     */
    private final Map<Integer, String> types = new ConcurrentHashMap<>();
    /**
     * Per-entity {@code Model.assetId} — the registered model asset that names
     * the species, unlike the shared blocky-mesh {@link #types} path. Sticky.
     */
    private final Map<Integer, String> modelAssetIds = new ConcurrentHashMap<>();
    /**
     * Per-entity nameplate text from observed {@code NameplateUpdate} — the
     * player's username for players, a custom name for named entities. Sticky
     * like {@link #types}; cleared on removal.
     */
    private final Map<Integer, String> nameplates = new ConcurrentHashMap<>();
    /**
     * Network ids that have carried a {@code PlayerSkin} component — i.e. real
     * players. Sticky once added (cleared only on removal), mirroring how the
     * {@link #types} map treats the model path.
     */
    private final Set<Integer> players = ConcurrentHashMap.newKeySet();
    /** Which way each entity's body faces - what a hit on it reports. */
    private final Map<Integer, Direction> bodies = new ConcurrentHashMap<>();
    /** Each entity's own interaction roots by type ({@code Interactions}) - what a use on it runs. */
    private final Map<Integer, Map<InteractionType, Integer>> interactions = new ConcurrentHashMap<>();
    /** Each entity's active effects by index - added and removed as the server says. */
    private final Map<Integer, Set<Integer>> effects = new ConcurrentHashMap<>();
    /**
     * Each entity's hitbox about its feet, sized as the server sizes it ({@link #hitbox}), from its
     * last model; none for a model without one.
     */
    private final Map<Integer, double[]> hitboxes = new ConcurrentHashMap<>();
    private final Map<Integer, ModelUpdate> projectileModels = new ConcurrentHashMap<>();
    /** The stack's item id of each dropped item on the ground ({@code Item} component). */
    private final Map<Integer, String> items = new ConcurrentHashMap<>();
    /** ... and how many it is. */
    private final Map<Integer, Integer> quantities = new ConcurrentHashMap<>();
    /** Where each entity was first seen since it came into view: a drop's, where it fell from. */
    private final Map<Integer, Vec3> firstSeen = new ConcurrentHashMap<>();
    /**
     * Each entity's stats as the server shares them with this viewer ({@code EntityStatsUpdate}):
     * changes applied in turn, as for the player's own ({@link meridian.core.impl.interaction.StatSheet}).
     */
    private final Map<Integer, meridian.core.impl.interaction.StatSheet> stats = new ConcurrentHashMap<>();
    /** What each entity holds in its main hand ({@code Equipment}). */
    private final Map<Integer, String> held = new ConcurrentHashMap<>();
    /** The server's stat catalog by index - shared with the player's own sheet ({@link #statCatalog}). */
    private volatile Map<Integer, meridian.protocol.EntityStatType> statTypes = Map.of();
    private volatile int localId = Integer.MIN_VALUE;
    private volatile Vec3 localPos;
    /** Look orientation of the local player, radians. */
    private volatile float lookYaw;
    private volatile float lookPitch;
    private volatile float lookRoll;
    private volatile boolean haveLook;

    // ------------------------------------------------------------------
    // Ingest — called by the observers
    // ------------------------------------------------------------------

    private java.util.UUID worldId;

    /** A world transfer replaces the entity scope without removing each old network id. */
    void onJoinWorld(java.util.UUID id, boolean clearing) {
        if (!clearing && java.util.Objects.equals(worldId, id)) return;
        worldId = id;
        positions.clear();
        types.clear();
        modelAssetIds.clear();
        nameplates.clear();
        players.clear();
        bodies.clear();
        interactions.clear();
        effects.clear();
        hitboxes.clear();
        projectileModels.clear();
        items.clear();
        quantities.clear();
        firstSeen.clear();
        stats.clear();
        held.clear();
        localId = Integer.MIN_VALUE;
        localPos = null;
        haveLook = false;
    }

    void onSetClientId(SetClientId packet) {
        localId = packet.clientId;
        log.info("meridian-core: local entity id = {}", localId);
    }

    void onEntityUpdates(EntityUpdates packet) {
        if (packet.removed != null) {
            for (int id : packet.removed) {
                positions.remove(id);
                types.remove(id);
                modelAssetIds.remove(id);
                nameplates.remove(id);
                players.remove(id);
                bodies.remove(id);
                interactions.remove(id);
                effects.remove(id);
                hitboxes.remove(id);
                projectileModels.remove(id);
                items.remove(id);
                quantities.remove(id);
                firstSeen.remove(id);
                stats.remove(id);
                held.remove(id);
            }
        }
        if (packet.updates != null) {
            for (var update : packet.updates) {
                if (update == null || update.updates == null) continue;
                for (ComponentUpdate component : update.updates) {
                    if (component instanceof TransformUpdate transform) {
                        Vec3 pos = positionOf(transform.transform);
                        if (pos != null) {
                            positions.put(update.networkId, pos);
                            firstSeen.putIfAbsent(update.networkId, pos);
                        }
                        if (transform.transform != null && transform.transform.bodyOrientation != null) {
                            bodies.put(update.networkId, transform.transform.bodyOrientation);
                        }
                    } else if (component instanceof EntityEffectsUpdate effectUpdates
                            && effectUpdates.entityEffectUpdates != null) {
                        Set<Integer> active = effects.computeIfAbsent(update.networkId,
                                k -> ConcurrentHashMap.newKeySet());
                        for (EntityEffectUpdate e : effectUpdates.entityEffectUpdates) {
                            if (e == null) continue;
                            if (e.type == EffectOp.Remove) {
                                active.remove(e.id);
                            } else {
                                active.add(e.id);
                            }
                        }
                    } else if (component instanceof InteractionsUpdate entityInteractions
                            && entityInteractions.interactions != null) {
                        interactions.put(update.networkId, Map.copyOf(entityInteractions.interactions));
                    } else if (component instanceof ModelUpdate modelUpdate && modelUpdate.model == null) {
                        hitboxes.remove(update.networkId);      // the model taken away, its box too
                        projectileModels.remove(update.networkId);
                    } else if (component instanceof ModelUpdate modelUpdate) {
                        projectileModels.put(update.networkId, modelUpdate);
                        double[] box = hitbox(modelUpdate.model, modelUpdate.entityScale);
                        if (box != null) {
                            hitboxes.put(update.networkId, box);
                        } else {
                            hitboxes.remove(update.networkId);
                        }
                        if (modelUpdate.model.path != null && !modelUpdate.model.path.isEmpty()) {
                            types.put(update.networkId, modelUpdate.model.path);
                        }
                        if (modelUpdate.model.assetId != null
                                && !modelUpdate.model.assetId.isEmpty()) {
                            modelAssetIds.put(update.networkId, modelUpdate.model.assetId);
                        }
                    } else if (component instanceof NameplateUpdate nameplate
                            && nameplate.text != null && !nameplate.text.isEmpty()) {
                        nameplates.put(update.networkId, nameplate.text);
                    } else if (component instanceof ItemUpdate item && item.item != null
                            && item.item.itemId != null && !item.item.itemId.isEmpty()) {
                        items.put(update.networkId, item.item.itemId);
                        quantities.put(update.networkId, item.item.quantity);
                    } else if (component instanceof EntityStatsUpdate statUpdate
                            && statUpdate.entityStatUpdates != null) {
                        stats(update.networkId, statUpdate);
                    } else if (component instanceof EquipmentUpdate equipment) {
                        if (equipment.rightHandItemId != null && !equipment.rightHandItemId.isEmpty()) {
                            held.put(update.networkId, equipment.rightHandItemId);
                        } else {
                            held.remove(update.networkId);
                        }
                    } else if (component instanceof PlayerSkinUpdate) {
                        // Only players carry a skin component — definitive
                        // player marker, independent of the model path.
                        players.add(update.networkId);
                    }
                }
            }
        }
    }

    /**
     * The server's stat catalog, shared with the player's own sheet: bounds and starting values by
     * index, and the names stats are asked for by.
     */
    void statCatalog(Map<Integer, meridian.protocol.EntityStatType> types) {
        statTypes = types == null ? Map.of() : types;
    }

    /** One entity's stat changes, applied in turn on its own sheet. */
    private void stats(int entityId, EntityStatsUpdate update) {
        meridian.core.impl.interaction.StatSheet sheet = stats.computeIfAbsent(entityId,
                k -> new meridian.core.impl.interaction.StatSheet(statTypes));
        update.entityStatUpdates.forEach((index, changes) -> {
            if (changes == null || index == null) {
                return;
            }
            for (EntityStatUpdate change : changes) {
                sheet.apply(index, change);
            }
        });
    }

    /** The catalog index of the stat named so, or {@code -1}. */
    private int statIndex(String name) {
        for (Map.Entry<Integer, meridian.protocol.EntityStatType> e : statTypes.entrySet()) {
            if (e.getValue() != null && name.equals(e.getValue().id)) {
                return e.getKey();
            }
        }
        return -1;
    }

    void onClientMovement(ClientMovement packet) {
        if (packet.absolutePosition != null) {
            localPos = new Vec3(packet.absolutePosition.x,
                    packet.absolutePosition.y, packet.absolutePosition.z);
        }
        Direction look = packet.lookOrientation;
        if (look != null) {
            lookYaw = look.yaw;
            lookPitch = look.pitch;
            lookRoll = look.roll;
            haveLook = true;
        }
    }

    /**
     * Which way the local player is facing, as the angles themselves: pitch, yaw, roll.
     *
     * <p>{@link #localLookDirection()} answers the same question as a vector, for aiming. This is
     * for anything that has to write the angles down the way the game does.
     */
    Vec3 localLookAngles() {
        return haveLook ? new Vec3(lookPitch, lookYaw, lookRoll) : null;
    }

    /**
     * An entity as something to aim at, with the local player's pose - what a forged hit or use
     * on it reports. {@code null} while the entity, or the local player, is not in sight.
     *
     * <p>The hit point is on the entity's vertical middle line, at the height of the player's
     * eyes kept within the body - where a straight look at it lands.
     */
    EntityTarget entityTarget(int entityId, double eyeHeight) {
        Vec3 at = positions.get(entityId);
        Vec3 me = localPos;
        if (at == null || me == null) {
            return null;
        }
        Direction body = bodies.getOrDefault(entityId, new Direction(0f, 0f, 0f));
        double hitY = Math.max(at.y() + 0.2, Math.min(at.y() + 1.2, me.y() + eyeHeight));
        return new EntityTarget(entityId,
                new Position(at.x(), at.y(), at.z()),
                new Direction(body.yaw, body.pitch, body.roll),
                new org.joml.Vector3f((float) at.x(), (float) hitY, (float) at.z()),
                new Position(me.x(), me.y(), me.z()),
                new Direction(lookYaw, lookPitch, lookRoll),
                interactions.getOrDefault(entityId, Map.of()),
                Set.copyOf(effectsOf(entityId)), true);
    }

    /** An entity's active effects by index, as last seen; empty when none were. */
    Set<Integer> effectsOf(int entityId) {
        Set<Integer> active = effects.get(entityId);
        return active == null ? Set.of() : Set.copyOf(active);
    }

    /** The local player's own active effects. */
    Set<Integer> selfEffects() {
        return localId == Integer.MIN_VALUE ? Set.of() : effectsOf(localId);
    }

    /** An entity's own interaction roots by type, as last seen; empty when none were. */
    Map<InteractionType, Integer> interactionsOf(int entityId) {
        return interactions.getOrDefault(entityId, Map.of());
    }

    /**
     * A model's hitbox about the feet as the server collides by it ({@code EntityScaleBoundingBoxSystem}):
     * the box sent already carries the model's own scale, and an entity scale replaces that scale
     * rather than multiplying it - so the box grows by the ratio of the two. {@code null} for a
     * model without a hitbox.
     */
    static double[] hitbox(meridian.protocol.Model model, float entityScale) {
        meridian.protocol.Hitbox h = model.hitbox;
        if (h == null) {
            return null;
        }
        double k = 1;
        if (Float.isFinite(entityScale) && entityScale > 0) {
            k = Float.isFinite(model.scale) && model.scale > 0 ? entityScale / model.scale : entityScale;
        }
        return new double[] {h.minX * k, h.minY * k, h.minZ * k, h.maxX * k, h.maxY * k, h.maxZ * k};
    }

    /** One collision detail of the current model; only the offset turns with body yaw in 0.7. */
    record ProjectileBox(String detail, Box box) { }

    java.util.List<ProjectileBox> projectileBoxesOf(int id) {
        ModelUpdate update = projectileModels.get(id);
        Vec3 at = positions.get(id);
        if (at == null || update == null) return java.util.List.of();
        var details = update.model.detailBoxes;
        if (details == null || details.isEmpty()) return boundsOf(id)
                .map(b -> java.util.List.of(new ProjectileBox(null, b))).orElse(java.util.List.of());
        double k = update.entityScale > 0 && Float.isFinite(update.entityScale)
                ? (Float.isFinite(update.model.scale) && update.model.scale > 0
                    ? update.entityScale / update.model.scale : update.entityScale) : 1;
        double yaw = bodies.getOrDefault(id, new Direction()).yaw;
        java.util.List<ProjectileBox> boxes = new java.util.ArrayList<>();
        for (var entry : details.entrySet()) for (var detail : entry.getValue()) {
            if (detail == null || detail.box == null) continue;
            var h = detail.box;
            double x = detail.offset.x() * k, y = detail.offset.y() * k, z = detail.offset.z() * k;
            double ox = at.x() + x * Math.cos(yaw) + z * Math.sin(yaw);
            double oy = at.y() + y;
            double oz = at.z() - x * Math.sin(yaw) + z * Math.cos(yaw);
            boxes.add(new ProjectileBox(entry.getKey(), new Box(
                    new Vec3(ox + h.minX * k, oy + h.minY * k, oz + h.minZ * k),
                    new Vec3(ox + h.maxX * k, oy + h.maxY * k, oz + h.maxZ * k))));
        }
        return java.util.List.copyOf(boxes);
    }

    private static Vec3 positionOf(ModelTransform transform) {
        if (transform == null) return null;
        Position p = transform.position;
        return p == null ? null : new Vec3(p.x, p.y, p.z);
    }

    // ------------------------------------------------------------------
    // EntityTracker
    // ------------------------------------------------------------------

    @Override
    public OptionalInt localEntityId() {
        int id = localId;
        return id == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(id);
    }

    @Override
    public Optional<Vec3> positionOf(int entityId) {
        return Optional.ofNullable(positions.get(entityId));
    }

    @Override
    public Optional<Box> boundsOf(int entityId) {
        Vec3 at = positions.get(entityId);
        double[] h = hitboxes.get(entityId);
        if (at == null || h == null) {
            return Optional.empty();
        }
        return Optional.of(new Box(new Vec3(at.x() + h[0], at.y() + h[1], at.z() + h[2]),
                new Vec3(at.x() + h[3], at.y() + h[4], at.z() + h[5])));
    }

    @Override
    public Optional<String> entityTypeOf(int entityId) {
        return Optional.ofNullable(types.get(entityId));
    }

    @Override
    public Optional<String> entityModelAssetId(int entityId) {
        return Optional.ofNullable(modelAssetIds.get(entityId));
    }

    @Override
    public Optional<String> itemOf(int entityId) {
        return Optional.ofNullable(items.get(entityId));
    }

    @Override
    public int itemQuantityOf(int entityId) {
        return quantities.getOrDefault(entityId, 0);
    }

    @Override
    public Optional<Vec3> firstSeenAt(int entityId) {
        return Optional.ofNullable(firstSeen.get(entityId));
    }

    @Override
    public Optional<Stat> statOf(int entityId, String name) {
        meridian.core.impl.interaction.StatSheet sheet = stats.get(entityId);
        int index = name == null || sheet == null ? -1 : statIndex(name);
        float[] v = index < 0 ? null : sheet.read(index);
        return v == null ? Optional.empty() : Optional.of(new Stat(v[0], v[1], v[2]));
    }

    @Override
    public Optional<String> heldItemOf(int entityId) {
        return Optional.ofNullable(held.get(entityId));
    }

    @Override
    public Optional<String> nameplateOf(int entityId) {
        return Optional.ofNullable(nameplates.get(entityId));
    }

    @Override
    public boolean isPlayer(int entityId) {
        // A skin is the sure sign; a player whose game sent none (a headless one, on an insecure
        // server) still wears the player model.
        return players.contains(entityId) || PLAYER_MODEL.equals(modelAssetIds.get(entityId));
    }

    /** The model every player wears. */
    private static final String PLAYER_MODEL = "Player";

    @Override
    public Optional<Vec3> localPosition() {
        return Optional.ofNullable(localPos);
    }

    /**
     * Unit vector for the local player's current look direction, or
     * {@code null} if no orientation has been observed yet. Package-private
     * accessor used by {@link PlayerImpl#lookDirection}; {@link Look#direction}, as
     * {@link #entityInCrosshair} aims by.
     */
    Vec3 localLookDirection() {
        if (!haveLook) return null;
        return new Look(lookYaw, lookPitch).direction();
    }

    @Override
    public int trackedCount() {
        return positions.size();
    }

    @Override
    public Set<Integer> trackedEntities() {
        return Set.copyOf(positions.keySet());
    }

    @Override
    public OptionalInt nearestEntity() {
        Vec3 origin = localPos;
        if (origin == null) return OptionalInt.empty();
        int self = localId;
        int best = Integer.MIN_VALUE;
        double bestSq = Double.MAX_VALUE;
        for (Map.Entry<Integer, Vec3> e : positions.entrySet()) {
            if (e.getKey() == self) continue;
            double sq = origin.distanceSqTo(e.getValue());
            // Skip the entity sitting on top of the player (the player itself,
            // when its id was never learned from SetClientId).
            if (sq < 0.25) continue;
            if (sq < bestSq) {
                bestSq = sq;
                best = e.getKey();
            }
        }
        return best == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(best);
    }

    @Override
    public OptionalInt entityInCrosshair(double maxAngleDeg, double maxRange) {
        Vec3 origin = localPos;
        if (origin == null || !haveLook) return OptionalInt.empty();

        Vec3 look = new Look(lookYaw, lookPitch).direction();
        double lx = look.x(), ly = look.y(), lz = look.z();

        double maxRangeSq = maxRange * maxRange;
        double minCos = Math.cos(Math.toRadians(maxAngleDeg));
        int self = localId;
        int best = Integer.MIN_VALUE;
        double bestCos = minCos;
        for (Map.Entry<Integer, Vec3> e : positions.entrySet()) {
            if (e.getKey() == self) continue;
            Vec3 p = e.getValue();
            double dx = p.x() - origin.x(), dy = p.y() - origin.y(), dz = p.z() - origin.z();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < 0.25 || distSq > maxRangeSq) continue;
            double dist = Math.sqrt(distSq);
            double cos = (dx * lx + dy * ly + dz * lz) / dist;
            if (cos > bestCos) {
                bestCos = cos;
                best = e.getKey();
            }
        }
        return best == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(best);
    }
}
