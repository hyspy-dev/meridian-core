package meridian.core.api;

/**
 * What the client is allowed to forget.
 *
 * <p>The server decides which chunks a player needs and tells the client to drop the rest. That is
 * right for playing and wrong for looking: someone mapping or inspecting a world wants what they
 * have flown over to stay on screen. This is that override, and it touches the client only - the
 * server is never told anything and goes on unloading on its own schedule.
 *
 * <p>Both halves are needed together. Keeping the chunks is not enough on its own, because the
 * client draws nothing beyond the view radius the server gave it; widening the radius is not
 * enough either, because the chunks are gone by then.
 *
 * <p>Off by default, and worth turning off again after a long flight: a client that never forgets
 * a chunk eventually runs out of room to remember them.
 */
public interface ChunkView {

    /** Whether the client is being kept from unloading chunks. */
    boolean keepLoaded();

    /**
     * Keeps every chunk the client has ever loaded, by not passing on the server's unloads.
     *
     * <p>The unload is still seen by everything that reads the world - core's own chunk feed sits
     * ahead of this - so a module writing chunks down is not robbed of the moment a column is
     * finished.
     */
    void setKeepLoaded(boolean keep);

    /** The radius being forced on the client, or {@code 0} when the server's own is left alone. */
    int viewRadius();

    /**
     * Forces the client's view radius, in chunks; {@code 0} leaves the server's alone.
     *
     * <p>Sent once, after the join, so the server's own radius cannot land afterwards and win.
     * The client's settings cap it, which is why a large number is not the same as an unbounded
     * one.
     */
    void setViewRadius(int chunks);
}
