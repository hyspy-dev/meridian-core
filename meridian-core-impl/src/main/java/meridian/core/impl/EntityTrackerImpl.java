package meridian.core.impl;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import meridian.core.api.EntityTracker;
import meridian.core.api.Vec3;
import meridian.protocol.ComponentUpdate;
import meridian.protocol.Direction;
import meridian.protocol.ModelTransform;
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
    private volatile int localId = Integer.MIN_VALUE;
    private volatile Vec3 localPos;
    /** Look orientation of the local player, radians. */
    private volatile float lookYaw;
    private volatile float lookPitch;
    private volatile boolean haveLook;

    // ------------------------------------------------------------------
    // Ingest — called by the observers
    // ------------------------------------------------------------------

    void onSetClientId(SetClientId packet) {
        localId = packet.clientId;
        log.info("meridian-core: local entity id = {}", localId);
    }

    void onEntityUpdates(EntityUpdates packet) {
        if (packet.removed != null) {
            for (int id : packet.removed) {
                positions.remove(id);
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
                        }
                    }
                }
            }
        }
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
            haveLook = true;
        }
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
    public Optional<Vec3> localPosition() {
        return Optional.ofNullable(localPos);
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

        // Look unit vector. Hytale Direction is (yaw, pitch) in radians:
        // pitch < 0 looks down (a topdown camera uses pitch = -PI/2).
        double cosPitch = Math.cos(lookPitch);
        double lx = -Math.sin(lookYaw) * cosPitch;
        double ly = Math.sin(lookPitch);
        double lz = Math.cos(lookYaw) * cosPitch;

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
