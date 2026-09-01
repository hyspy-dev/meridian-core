package meridian.core.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Everything known about markers, kept across sessions.
 *
 * <p>Held per world and written as one small file, so that reconnecting does not lose the map:
 * which markers exist, where players were last seen, and which ones the player has chosen not to
 * look at. A marker the server stops sending is not forgotten, it is only marked as no longer
 * shown.
 *
 * <p>Writes come from the network threads and from settings callbacks, so the collections are
 * concurrent; saving is debounced through {@link #saveIfDirty}, since a busy world updates
 * player markers several times a second and none of those are worth a write.
 */
final class MarkerStoreImpl {

    private static final int FORMAT = 1;

    /** worldId to markerId to what we know about it. */
    private final Map<String, Map<String, MarkerState>> worlds = new ConcurrentHashMap<>();
    /** worldId to the markers the player has taken off their map. */
    private final Map<String, Set<String>> hidden = new ConcurrentHashMap<>();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Logger log;

    MarkerStoreImpl(Logger log) {
        this.log = log;
    }

    // ------------------------------------------------------------------

    private Map<String, MarkerState> world(String worldId) {
        return worlds.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());
    }

    Set<String> hiddenIn(String worldId) {
        return hidden.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet());
    }

    Collection<MarkerState> markers(String worldId) {
        return world(worldId).values();
    }

    MarkerState get(String worldId, String id) {
        return world(worldId).get(id);
    }

    /**
     * Folds an incoming marker into what we already knew. A marker the player deleted stays
     * deleted even when the server sends it again, so that flag survives the update while
     * everything else is replaced by what just arrived.
     */
    synchronized MarkerState upsert(String worldId, meridian.protocol.packets.worldmap.MapMarker m) {
        MarkerState fresh = MarkerCodec.read(worldId, m);
        MarkerState state = world(worldId).merge(m.id, fresh, (old, incoming) -> {
            incoming.removedLocally = old.removedLocally;
            return incoming;
        });
        state.live = m.clone();
        dirty.set(true);
        return state;
    }

    synchronized void put(MarkerState state) {
        world(state.worldId).put(state.id, state);
        dirty.set(true);
    }

    synchronized MarkerState remove(String worldId, String id) {
        MarkerState state = world(worldId).remove(id);
        if (state != null) {
            hiddenIn(worldId).remove(id);   // a marker that is gone cannot be hidden
            dirty.set(true);
        }
        return state;
    }

    void markDirty() {
        dirty.set(true);
    }

    // ------------------------------------------------------------------

    /**
     * Folds a kept archive back into what is known.
     *
     * <p>The file itself belongs to the markers module now; this only reads what it hands over,
     * which is why it takes the text rather than a path.
     */
    void restore(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Saved saved = gson.fromJson(json, Saved.class);
            if (saved == null || saved.worlds == null) {
                return;
            }
            int count = 0;
            for (Map.Entry<String, SavedWorld> entry : saved.worlds.entrySet()) {
                SavedWorld world = entry.getValue();
                if (world == null) {
                    continue;
                }
                if (world.markers != null) {
                    for (MarkerState state : world.markers) {
                        if (state == null || state.id == null || state.id.isEmpty()) {
                            continue;
                        }
                        // Nothing has arrived yet this session, so nothing is being shown yet -
                        // whatever was on screen last time belongs to a connection that is gone.
                        state.online = false;
                        state.worldId = entry.getKey();
                        world(entry.getKey()).put(state.id, state);
                        count++;
                    }
                }
                if (world.hidden != null) {
                    hiddenIn(entry.getKey()).addAll(world.hidden);
                }
            }
            log.info("meridian-core: {} markers restored across {} worlds",
                    count, saved.worlds.size());
        } catch (RuntimeException e) {
            // An archive we cannot read is not worth failing a session over: markers rebuild
            // themselves from the server within a minute of play.
            log.warn("meridian-core: could not read the kept markers - starting with none", e);
        }
    }

    /** Everything remembered, as JSON, for whoever is keeping the file. */
    String export() {
        Saved saved = new Saved();
        saved.version = FORMAT;
        saved.worlds = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, MarkerState>> entry : worlds.entrySet()) {
            SavedWorld world = new SavedWorld();
            world.markers = new ArrayList<>(entry.getValue().values());
            world.hidden = new LinkedHashSet<>(hiddenIn(entry.getKey()));
            saved.worlds.put(entry.getKey(), world);
        }
        dirty.set(false);
        return gson.toJson(saved);
    }

    /** The worlds anything is remembered for. */
    Set<String> worldIds() {
        return Set.copyOf(worlds.keySet());
    }

    /** Whether anything has changed since the archive was last read out. */
    boolean hasChanges() {
        return dirty.get();
    }

    boolean isEmpty() {
        return worlds.values().stream().allMatch(Map::isEmpty);
    }

    /** On-disk shape. Only what gson can round-trip. */
    private static final class Saved {
        int version;
        Map<String, SavedWorld> worlds;
    }

    private static final class SavedWorld {
        List<MarkerState> markers;
        Set<String> hidden;
    }
}
