package meridian.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Everything core remembers about markers, in a form somebody else can keep.
 *
 * <p>{@link MapMarkers} is the world the player is in right now. This is the whole memory behind
 * it - every world, every marker ever seen there, which of them the player has taken off their map
 * - and the two things needed to look after it: read it out, and put it back.
 *
 * <p>Core holds that memory because the marker traffic is core's, but it no longer keeps the file.
 * The markers module does: it hands core what it kept at startup and writes back what core has as
 * the session goes. Without that module markers still work for as long as the proxy runs; they are
 * simply not remembered afterwards.
 *
 * <p>The archive travels as JSON on purpose. It is core's own shape and only core knows how to
 * read it, so a module that keeps it never has to understand it - which is what lets it stay a
 * Layer-2 module while the format underneath changes with the game.
 */
public interface MarkerArchive {

    /** Everything remembered, as JSON: the file a keeper should write. */
    String export();

    /**
     * Puts a kept archive back, folding it into whatever is already known.
     *
     * <p>Meant for startup, before a connection: what the server sends afterwards updates these
     * in place, so a marker that is still there is refreshed rather than duplicated.
     */
    void restore(String json);

    /** Whether anything at all is remembered - false on a first run, or after a failed read. */
    boolean isEmpty();

    /**
     * Whether anything has changed since the last {@link #export()}.
     *
     * <p>A keeper writing on a timer asks this first: a quiet minute costs nothing, and a busy
     * world updates player markers several times a second, none of which is worth a write.
     */
    boolean hasChanges();

    /** The worlds anything is remembered for. */
    List<UUID> worlds();

    /** Every marker remembered for a world, shown or not, from this session or any before it. */
    List<Marker> markers(UUID world);
}
