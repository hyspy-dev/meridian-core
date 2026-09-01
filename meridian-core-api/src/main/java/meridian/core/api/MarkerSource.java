package meridian.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Markers as somebody keeps them, with the pictures that go on them.
 *
 * <p>{@link MapMarkers} is the live map: what the server is showing right now. This is the other
 * half - what a module has kept, across worlds and across sessions, and the icon each marker is
 * drawn with. The markers module provides it; anything that draws a map reads it when it is there.
 *
 * <p>Optional by nature: ask the registry with {@code get} rather than {@code require}.
 */
public interface MarkerSource {

    /** Every marker kept for a world, including ones from sessions long past. */
    List<Marker> markers(UUID world);

    /** The worlds anything is kept for. */
    List<UUID> worlds();

    /**
     * The picture a marker's icon names, as image bytes, or empty when there is none.
     *
     * @param icon the marker's {@link Marker#icon}
     */
    Optional<byte[]> icon(String icon);
}
