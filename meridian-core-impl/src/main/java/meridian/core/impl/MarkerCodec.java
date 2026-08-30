package meridian.core.impl;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import meridian.core.api.MarkerCategory;
import meridian.protocol.Color;
import meridian.protocol.Direction;
import meridian.protocol.FormattedMessage;
import meridian.protocol.Position;
import meridian.protocol.Transform;
import meridian.protocol.packets.worldmap.MapMarker;
import meridian.protocol.packets.worldmap.MapMarkerComponent;
import meridian.protocol.packets.worldmap.PlacedByMarkerComponent;
import meridian.protocol.packets.worldmap.PlayerMarkerComponent;
import meridian.protocol.packets.worldmap.TintComponent;

/**
 * The wire form of markers: reading what the server sends, and writing the ones we invent.
 *
 * <p>Which kind a marker is has to be worked out the same way the server does it, because the
 * server does not say. A marker carrying a player component is somebody's position; one carrying
 * a placed-by component is a marker a player made, and whether everyone sees it is written into
 * the id ({@code user_shared_} against {@code user_personal_}); anything else belongs to the
 * world — a spawn, a home, a point of interest, the place someone died.
 *
 * <p>Ours are named with a {@code proxy_} prefix, which no server path produces, so a marker we
 * made is never mistaken for one that came back from the server.
 */
final class MarkerCodec {

    static final String LOCAL_PREFIX = "proxy_marker_";
    static final String GHOST_PREFIX = "proxy_lastseen_";
    /** Ships with the game. The server's own fallback, {@code User1.png}, does not. */
    static final String DEFAULT_ICON = "UserA.png";

    private static final DateTimeFormatter SEEN = DateTimeFormatter.ofPattern("HH:mm");

    private MarkerCodec() {
    }

    static boolean isOurs(String id) {
        return id != null && id.startsWith("proxy_");
    }

    static boolean isGhost(String id) {
        return id != null && id.startsWith(GHOST_PREFIX);
    }

    static String newLocalId() {
        return LOCAL_PREFIX + UUID.randomUUID();
    }

    static String ghostId(UUID player) {
        return player == null ? null : GHOST_PREFIX + player;
    }

    /** Reads an incoming marker, working out what kind it is. */
    static MarkerState read(String worldId, MapMarker m) {
        MarkerState s = new MarkerState();
        s.id = m.id;
        s.worldId = worldId;
        s.name = text(m.name);
        s.icon = m.markerImage == null ? "" : m.markerImage;
        if (m.transform != null && m.transform.position != null) {
            s.x = m.transform.position.x;
            s.y = m.transform.position.y;
            s.z = m.transform.position.z;
        }
        s.lastSeenMillis = System.currentTimeMillis();
        s.online = true;   // arriving on the wire is what "the server is showing it" means
        if (m.components != null) {
            for (MapMarkerComponent c : m.components) {
                if (c instanceof PlayerMarkerComponent p) {
                    s.player = p.playerId;
                } else if (c instanceof PlacedByMarkerComponent p) {
                    s.owner = p.playerId;
                    s.ownerName = text(p.name);
                } else if (c instanceof TintComponent t) {
                    s.colourRgb = rgb(t.color);
                }
            }
        }
        if (s.player != null) {
            s.category = MarkerCategory.PLAYER;
            if (s.name.isEmpty()) {
                s.name = "player " + shortId(s.player);
            }
        } else if (isOurs(m.id)) {
            s.category = MarkerCategory.LOCAL;
        } else if (s.owner != null || s.ownerName != null) {
            s.category = m.id != null && m.id.startsWith("user_shared_")
                    ? MarkerCategory.USER_SHARED
                    : MarkerCategory.USER_PRIVATE;
        } else {
            s.category = MarkerCategory.SERVER;
        }
        return s;
    }

    /** Builds a marker from what we remember — for our own, and for players who left. */
    static MapMarker write(String id, String name, String icon, double x, double y, double z,
                           int colourRgb, UUID owner, String ownerName) {
        MapMarker m = new MapMarker();
        m.id = id;
        m.name = name == null || name.isEmpty() ? null : message(name);
        m.markerImage = icon == null || icon.isEmpty() ? DEFAULT_ICON : icon;
        m.transform = new Transform(new Position(x, y, z), new Direction());
        List<MapMarkerComponent> components = new ArrayList<>(2);
        if (colourRgb >= 0) {
            components.add(new TintComponent(colour(colourRgb)));
        }
        if (owner != null) {
            components.add(new PlacedByMarkerComponent(
                    message(ownerName == null ? "" : ownerName), owner));
        }
        m.components = components.isEmpty() ? null : components.toArray(MapMarkerComponent[]::new);
        return m;
    }

    static MapMarker writeLocal(MarkerState s) {
        return write(s.id, s.name, s.icon, s.x, s.y, s.z, s.colourRgb, s.owner, s.ownerName);
    }

    /** Where a player was when they went offline, with the time they were last seen. */
    static MapMarker writeGhost(MarkerState s) {
        String seen = SEEN.format(Instant.ofEpochMilli(s.lastSeenMillis)
                .atZone(ZoneId.systemDefault()));
        return write(s.ghostId(), s.name + " (seen " + seen + ")",
                s.icon == null || s.icon.isEmpty() ? "Player.png" : s.icon,
                s.x, s.y, s.z, s.colourRgb, null, null);
    }

    static FormattedMessage message(String text) {
        FormattedMessage m = new FormattedMessage();
        m.rawText = text;
        return m;
    }

    /**
     * The readable text of a message. A name may be given either literally or as something for
     * the client to translate; for the latter the last part of the key is the closest we can get
     * without the client's own language files.
     */
    static String text(FormattedMessage m) {
        if (m == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        collect(m, out);
        return out.toString().trim();
    }

    private static void collect(FormattedMessage m, StringBuilder out) {
        if (m.rawText != null && !m.rawText.isEmpty()) {
            out.append(m.rawText);
        } else if (m.messageId != null && !m.messageId.isEmpty()) {
            int dot = m.messageId.lastIndexOf('.');
            out.append(dot >= 0 ? m.messageId.substring(dot + 1) : m.messageId);
        }
        if (m.children != null) {
            for (FormattedMessage child : m.children) {
                if (child != null) {
                    if (!out.isEmpty()) {
                        out.append(' ');
                    }
                    collect(child, out);
                }
            }
        }
    }

    static int rgb(Color c) {
        return c == null ? -1
                : ((c.red & 0xFF) << 16) | ((c.green & 0xFF) << 8) | (c.blue & 0xFF);
    }

    static Color colour(int rgb) {
        return new Color((byte) ((rgb >> 16) & 0xFF), (byte) ((rgb >> 8) & 0xFF),
                (byte) (rgb & 0xFF));
    }

    static String shortId(UUID id) {
        String s = id.toString();
        return s.substring(0, Math.min(8, s.length()));
    }
}
