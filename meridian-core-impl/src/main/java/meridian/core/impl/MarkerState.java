package meridian.core.impl;

import java.util.UUID;
import meridian.core.api.Marker;
import meridian.core.api.MarkerCategory;
import meridian.core.api.Vec3;
import meridian.protocol.packets.worldmap.MapMarker;

/**
 * What we know about one marker, as it changes.
 *
 * <p>The public {@link Marker} is a snapshot handed to modules; this is the thing behind it that
 * the server keeps updating. Written to disk by gson, so every field kept has to survive a round
 * trip — which is why the wire marker is not one of them: it belongs to a protocol that changes
 * between game versions, and a saved copy would be a decoding problem waiting to happen. It is
 * held for this session only, to redraw a marker we were given rather than rebuild it.
 */
final class MarkerState {

    String id = "";
    String worldId = "";
    String name = "";
    String icon = "";
    double x;
    double y;
    double z;
    /** {@code 0xRRGGBB}, or {@code -1} when the marker has no tint. */
    int colourRgb = -1;
    MarkerCategory category = MarkerCategory.SERVER;
    /** The player who placed it, for a marker a player placed. */
    UUID owner;
    String ownerName;
    /** Whose position this shows, for a player marker. */
    UUID player;
    long lastSeenMillis;
    /** Whether the server is currently showing this marker. */
    boolean online;
    /**
     * Set when the server would not delete a marker and we took it off the map instead. The
     * server keeps sending it; every arrival is dropped again, so it stays gone.
     */
    boolean removedLocally;

    /** The last wire marker the server sent, so a hidden one can be put back exactly. */
    transient MapMarker live;

    Marker snapshot() {
        return new Marker(id, name, icon, new Vec3(x, y, z), colourRgb, category,
                owner, ownerName, player, online, lastSeenMillis);
    }

    /** Where a marker for a player who has gone offline is drawn. */
    String ghostId() {
        return MarkerCodec.ghostId(player);
    }
}
