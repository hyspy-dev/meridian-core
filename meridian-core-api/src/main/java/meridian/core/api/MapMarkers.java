package meridian.core.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Every marker on the world map: the ones the server sends, and the ones we make ourselves.
 *
 * <p>This service owns the marker traffic. It remembers every marker the server has ever shown
 * for a world and keeps that across reconnects, it decides which of them actually reach the
 * client, and it is the only thing that forges marker updates — two modules doing that
 * separately would each undo the other's work, since neither can see what the other showed.
 *
 * <p>Markers are remembered longer than the server shows them. A player who logs out, ground
 * that scrolled out of view: those markers stay in {@link #all()} with {@link Marker#online()}
 * false. What can be shown again differs by kind — a local marker we can always redraw, a
 * server-owned one only while the server is still sending it.
 *
 * <p>Making a marker is not straightforward, and the awkwardness is hidden here: the server
 * never answers a create or a remove, it just silently declines the ones it does not like — too
 * far away, too many already, a name too long. So a marker is drawn the moment it is asked for
 * and the answer is waited for behind it: if the server takes it, ours is swapped for the real
 * one; if it never answers, ours simply stays. Either way the player sees it immediately, which
 * is why {@link #create} hands back a future rather than a marker — the marker is already
 * there, and it is the outcome that takes a few seconds to settle.
 *
 * <pre>{@code
 * MapMarkers markers = ctx.services().require(MapMarkers.class);
 *
 * markers.createLocal("Base", player.position(), "UserA.png", 0x55FF55);
 *
 * for (Marker m : markers.byCategory(MarkerCategory.PLAYER)) {
 *     if (!m.online()) markers.hide(m.id());       // stop showing players who left
 * }
 * }</pre>
 */
public interface MapMarkers {

    /** Every marker known for the world the client is in, shown or not. */
    List<Marker> all();

    /** Markers of one kind, in the current world. */
    List<Marker> byCategory(MarkerCategory category);

    /** One marker by id, if it is known. */
    Optional<Marker> get(String id);

    // ------------------------------------------------------------------
    // What the client sees
    // ------------------------------------------------------------------

    /**
     * Takes a marker off the client's map. It stays known, and stays in {@link #all()}.
     *
     * <p>Hiding is remembered per world and survives a reconnect, so a marker the player asked
     * not to see does not come back on its own.
     */
    void hide(String id);

    /**
     * Puts a hidden marker back. A server-owned marker reappears only if the server is still
     * showing it — we cannot invent what we were never given.
     */
    void show(String id);

    /**
     * Hides several markers at once, as one update rather than one each. What a whole category
     * or icon being switched off amounts to, and the difference between one packet and hundreds.
     */
    void hide(Collection<String> ids);

    /** Shows several hidden markers at once. */
    void show(Collection<String> ids);

    /** Whether this marker is currently being kept off the client's map. */
    boolean isHidden(String id);

    /** The markers being kept off the map in this world. */
    Set<String> hidden();

    /** Shows everything again, forgetting every hide in this world. */
    void showAll();

    // ------------------------------------------------------------------
    // Making and unmaking
    // ------------------------------------------------------------------

    /**
     * Makes a marker that only this client will ever see. It is drawn immediately, the server is
     * not told, and it is remembered across sessions. Nothing can refuse it — which also means
     * the server cannot teleport to it.
     *
     * @param colourRgb {@code 0xRRGGBB} tint, or {@code -1} for the icon's own colours
     */
    Marker createLocal(String name, Vec3 position, String icon, int colourRgb);

    /**
     * Asks the server for a real marker, and settles for a local one if it will not give it.
     *
     * <p>The marker appears at once. The future says how it ended up: completed with a marker
     * the server owns if it accepted, or with a local one if it never answered — check
     * {@link Marker#category()} to tell which. Settling takes a few seconds when the server
     * declines, because a decline is silence.
     *
     * @param shared whether everyone sees it, or only this player
     */
    CompletableFuture<Marker> create(String name, Vec3 position, String icon, int colourRgb,
                                     boolean shared);

    /**
     * Removes a marker: ours outright, the server's by asking. If the server will not, the
     * marker is hidden instead, so it is gone from the player's point of view either way.
     *
     * @return whether such a marker was known at all
     */
    boolean remove(String id);

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    /**
     * Stops asking the server about markers at all: the player's own map is theirs alone, and
     * every marker they make or delete is handled here. Nothing is shared with other players and
     * nothing can be refused.
     */
    void setLocalOnly(boolean localOnly);

    boolean localOnly();

    /**
     * Whether a player who goes offline leaves a marker where they were last seen. Off by
     * default is not the interesting case: on, the map remembers where everyone went.
     */
    void setPlayerGhosts(boolean ghosts);

    boolean playerGhosts();

    /**
     * Called whenever the set of known markers or their state changes — a marker arrived, left,
     * was hidden, was made. Runs on the network thread, so hand off anything slow.
     */
    void onChanged(Runnable listener);
}
