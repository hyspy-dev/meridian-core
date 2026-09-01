package meridian.core.impl;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import meridian.core.api.PlayerState;
import meridian.core.api.Vec3;

/**
 * The player as the traffic describes them.
 *
 * <p>Assembled from what the client is told about itself: the inventory panels, the stat bars, the
 * transform of the entity the server said is ours. Everything here is the server's own word - a
 * value that has never been sent is not here, rather than guessed at.
 */
final class PlayerStateImpl implements PlayerState {

    /**
     * Where the client says it is.
     *
     * <p>The server does not tell us where we are walking - it does not need to, since the client
     * is the one moving - so the only running account of our own position is the movement the
     * client sends out. Ask for it rather than keep a copy: the tracker is fed before any of our
     * own rewriting, so it is where the player really is, right up to the last packet they sent.
     */
    private final EntityTrackerImpl tracker;

    private final Map<String, Double> stats = new ConcurrentHashMap<>();
    private final Map<String, Section> inventories = new ConcurrentHashMap<>();
    /** Stat id → name, from the server's catalog; a stat's number means nothing without it. */
    private final Map<Integer, String> statNames = new ConcurrentHashMap<>();

    /**
     * Who the player proved they are, from the token they logged in with.
     *
     * <p>The one source worth having: it is what the server itself goes by, it arrives at the
     * handshake before anything else, and it is the same on every server. The guesses that used
     * to stand behind it - a marker we had placed, a marker with our name on it - were each right
     * only sometimes, and a player file under a wrong name is worse than none.
     */
    private volatile UUID told;
    private volatile String name = "";
    /** Where the server last put us: a spawn, a teleport, a world change. */
    private volatile Vec3 position = new Vec3(0, 0, 0);
    private volatile Vec3 rotation = new Vec3(0, 0, 0);

    PlayerStateImpl(EntityTrackerImpl tracker) {
        this.tracker = tracker;
    }

    @Override
    public Optional<UUID> uuid() {
        return Optional.ofNullable(told);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Vec3 position() {
        Vec3 live = tracker == null ? null : tracker.localPosition().orElse(null);
        // The server's own word, for the moment before the player has moved at all.
        return live != null ? live : position;
    }

    @Override
    public Vec3 rotation() {
        Vec3 live = tracker == null ? null : tracker.localLookAngles();
        return live != null ? live : rotation;
    }

    @Override
    public Map<String, Double> stats() {
        return Map.copyOf(stats);
    }

    @Override
    public Map<String, Section> inventories() {
        return Map.copyOf(inventories);
    }

    // ------------------------------------------------------------------
    // Ingest - from PlayerStateHandler, on the network threads
    // ------------------------------------------------------------------

    void onStatTypes(Map<Integer, String> types) {
        statNames.putAll(types);
    }

    /** One stat of ours changed; the name comes from the catalog, the number from the server. */
    void onStat(int statId, double value) {
        String named = statNames.get(statId);
        if (named != null) {
            stats.put(named, value);
        }
    }

    /** The player announcing themselves as they connect: the one place the id is said outright. */
    void onIdentity(UUID id, String username) {
        if (id != null && id.getMostSignificantBits() != 0) {
            told = id;
        }
        onName(username);
    }

    void onName(String text) {
        if (text != null) {
            name = text;
        }
    }

    void onTransform(Vec3 where, Vec3 facing) {
        if (where != null) {
            position = where;
        }
        if (facing != null) {
            rotation = facing;
        }
    }

    void onSection(String panel, Section section) {
        if (section != null) {
            inventories.put(panel, section);
        }
    }

    /** The slot in hand changed, and the panel it belongs to keeps everything else. */
    void onActiveSlot(String panel, int slot) {
        inventories.computeIfPresent(panel,
                (key, was) -> new Section(was.capacity(), was.items(), slot));
    }
}
