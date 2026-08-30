package meridian.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Player-owned map markers that live in the proxy rather than on the server.
 *
 * <p>The game's own markers are the server's: it decides whether a create is allowed, refuses
 * one placed too far away, silently drops the rest past a small cap, and forgets them when you
 * leave. Waypoints here are ours — kept on disk, unlimited in number, and drawn on the map by
 * forging them straight to the client.
 *
 * <p>Because a map crowded with hundreds of pins is useless (and the client has its own ideas
 * about how many it will draw), only the nearest {@link #visibleLimit()} are projected into the
 * game at any moment. The rest still exist, still persist, and appear as the player moves.
 *
 * <pre>{@code
 * Waypoints waypoints = ctx.services().require(Waypoints.class);
 * waypoints.add("Diamond cave", world.player().orElseThrow().position(), "UserA.png", 0x33CCFF);
 * }</pre>
 */
public interface Waypoints {

    /** Adds a waypoint and returns it, including the id it was given. */
    Waypoint add(String name, Vec3 position, String icon, int colourRgb);

    /** Removes a waypoint. False when no waypoint has that id. */
    boolean remove(UUID id);

    /** Every waypoint of the current world, in creation order. */
    List<Waypoint> all();

    /** The waypoint with this id, if it exists in the current world. */
    Optional<Waypoint> get(UUID id);

    /** How many waypoints are drawn on the game map at once (the nearest ones). */
    void setVisibleLimit(int limit);

    int visibleLimit();

    /**
     * One saved place. {@code colourRgb} is {@code 0xRRGGBB}, or {@code -1} for the map's
     * default tint; {@code icon} is a client image name such as {@code UserA.png}.
     */
    record Waypoint(UUID id, String name, Vec3 position, String icon, int colourRgb) {}
}
