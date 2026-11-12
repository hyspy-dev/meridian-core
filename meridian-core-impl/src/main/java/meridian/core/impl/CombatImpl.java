package meridian.core.impl;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import meridian.core.api.Combat;
import meridian.core.api.Vec3;
import meridian.protocol.InteractionType;
import meridian.protocol.packets.interaction.PlayInteractionFor;
import meridian.protocol.packets.player.DamageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live {@link Combat}: the hits the player took ({@code DamageInfo}) and the attacks other entities
 * began ({@code PlayInteractionFor}), kept for a minute.
 */
final class CombatImpl implements Combat {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** How long a hit or an attack is kept. */
    static final long KEEP_MS = 60_000;
    /** The most kept of each. */
    static final int KEEP_MOST = 256;
    /**
     * An attacker is the tracked entity standing this near where the server said the blow came
     * from: the server sends the attacker's own position at that moment, and core has the same
     * entity's last position from the same tick's updates, or one a tick off.
     */
    static final double ATTACKER_WITHIN = 1.5;

    private final EntityTrackerImpl entities;
    private final Deque<Hit> hits = new ArrayDeque<>();
    private final Deque<Swing> swings = new ArrayDeque<>();
    /** The chain each entity last played, so an attack is counted once, at its start. */
    private final Map<Integer, Integer> chains = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    CombatImpl(EntityTrackerImpl entities) {
        this.entities = entities;
    }

    @Override
    public List<Hit> hits(Duration within) {
        long since = System.currentTimeMillis() - (within == null ? KEEP_MS : within.toMillis());
        synchronized (hits) {
            return hits.stream().filter(h -> h.at() >= since).toList();
        }
    }

    @Override
    public List<Swing> swings(Duration within) {
        long since = System.currentTimeMillis() - (within == null ? KEEP_MS : within.toMillis());
        synchronized (swings) {
            return swings.stream().filter(s -> s.at() >= since).toList();
        }
    }

    @Override
    public void subscribe(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------
    // From the handler
    // ------------------------------------------------------------------

    void onDamage(DamageInfo info) {
        Vec3 from = info.damageSourcePosition == null ? null
                : new Vec3(info.damageSourcePosition.x, info.damageSourcePosition.y, info.damageSourcePosition.z);
        int attacker = from == null ? -1 : nearestTo(from);
        Hit hit = new Hit(System.currentTimeMillis(), attacker, from, info.damageAmount,
                info.damageCause == null ? null : info.damageCause.id);
        keep(hits, hit);
        log.info("meridian-core: hit for {} ({}) by entity {} from {}", info.damageAmount, hit.cause(), attacker, from);
        for (Listener l : listeners) {
            try {
                l.hit(hit);
            } catch (RuntimeException e) {
                log.warn("meridian-core: a combat listener failed on a hit", e);
            }
        }
    }

    void onPlay(PlayInteractionFor play) {
        if (play.cancel || play.forkedId != null) {
            return;
        }
        if (play.interactionType != InteractionType.Primary && play.interactionType != InteractionType.Secondary) {
            return;
        }
        OptionalInt self = entities.localEntityId();
        if (self.isPresent() && self.getAsInt() == play.entityId) {
            return;
        }
        Integer before = chains.put(play.entityId, play.chainId);
        if (before != null && before == play.chainId) {
            return;     // the same attack, an operation further on
        }
        Swing swing = new Swing(System.currentTimeMillis(), play.entityId, play.interactionType.name(), play.chainId);
        keep(swings, swing);
        for (Listener l : listeners) {
            try {
                l.swing(swing);
            } catch (RuntimeException e) {
                log.warn("meridian-core: a combat listener failed on a swing", e);
            }
        }
    }

    /** An entity gone from view: its chain is forgotten, so an attack after it comes back counts. */
    void onRemoved(int entityId) {
        chains.remove(entityId);
    }

    void reset() {
        synchronized (hits) {
            hits.clear();
        }
        synchronized (swings) {
            swings.clear();
        }
        chains.clear();
    }

    private static <T> void keep(Deque<T> into, T item) {
        synchronized (into) {
            into.addLast(item);
            while (into.size() > KEEP_MOST) {
                into.removeFirst();
            }
        }
        long old = System.currentTimeMillis() - KEEP_MS;
        synchronized (into) {
            while (!into.isEmpty() && at(into.peekFirst()) < old) {
                into.removeFirst();
            }
        }
    }

    private static long at(Object o) {
        return o instanceof Hit h ? h.at() : o instanceof Swing s ? s.at() : Long.MAX_VALUE;
    }

    /** The tracked entity, not the player, standing nearest {@code at} - within {@link #ATTACKER_WITHIN}; {@code -1} for none. */
    private int nearestTo(Vec3 at) {
        OptionalInt self = entities.localEntityId();
        int best = -1;
        double bestSq = ATTACKER_WITHIN * ATTACKER_WITHIN;
        for (int id : entities.trackedEntities()) {
            if (self.isPresent() && self.getAsInt() == id) {
                continue;
            }
            Vec3 p = entities.positionOf(id).orElse(null);
            if (p == null) {
                continue;
            }
            double d = p.distanceSqTo(at);
            if (d <= bestSq) {
                bestSq = d;
                best = id;
            }
        }
        return best;
    }
}
