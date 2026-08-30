package meridian.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * A marker on the world map, as it stands right now.
 *
 * <p>A snapshot, not a handle: it does not change under you, and holding one does not keep the
 * marker alive. Ask {@link MapMarkers} again for the current state.
 *
 * @param id           the server's identifier, or ours for a {@link MarkerCategory#LOCAL} one
 * @param name         what it is called; empty when it has no name
 * @param icon         the picture the client draws, e.g. {@code UserA.png}
 * @param position     where it is
 * @param colourRgb    {@code 0xRRGGBB} tint, or {@code -1} when the marker has no tint
 * @param category     what kind of marker this is
 * @param owner        the player who placed it, when a player did
 * @param ownerName    that player's name, when the server told us
 * @param player       for a player marker, whose position it is
 * @param online       whether the server is currently showing this marker. A player who logs
 *                     out, or ground that scrolled out of view, goes offline but is remembered
 * @param lastSeenMillis when the server last sent it
 */
public record Marker(
        String id,
        String name,
        String icon,
        Vec3 position,
        int colourRgb,
        MarkerCategory category,
        UUID owner,
        String ownerName,
        UUID player,
        boolean online,
        long lastSeenMillis) {

    /** The player who placed this marker, if a player did. */
    public Optional<UUID> placedBy() {
        return Optional.ofNullable(owner);
    }

    /** Whose position this marker shows, for a {@link MarkerCategory#PLAYER} marker. */
    public Optional<UUID> playerId() {
        return Optional.ofNullable(player);
    }

    /** Whether this marker is ours rather than the server's. */
    public boolean isLocal() {
        return category == MarkerCategory.LOCAL;
    }

    /** A name to show: the marker's own, falling back to its id when it has none. */
    public String displayName() {
        return name == null || name.isEmpty() ? id : name;
    }
}
