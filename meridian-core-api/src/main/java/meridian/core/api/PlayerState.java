package meridian.core.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The player, as the server keeps them: where they are, how they are, and what they carry.
 *
 * <p>All of it is read off the traffic that keeps the client's own screen honest - the inventory
 * panels, the health bar, the position. Nothing here is guessed; what the server has not said is
 * simply absent.
 *
 * <p>This is the state a save file holds about a player, which is why it is gathered in one place:
 * anything that writes a world can write the player who explored it.
 */
public interface PlayerState {

    /** One inventory panel: how big it is, what is in it, and which slot is in hand. */
    record Section(int capacity, Map<Integer, Containers.Item> items, int activeSlot) {
    }

    /**
     * The player's own id, once it is known.
     *
     * <p>The server never announces it outright; it turns up the first time the player's own
     * marker comes back with their name on it, so early in a session this can be empty.
     */
    Optional<UUID> uuid();

    /** The name over the player's head, or empty until the server has drawn one. */
    String name();

    /**
     * Where the player is now.
     *
     * <p>Where they are walking is something only the client says: the server puts them somewhere
     * on the way in, and on a teleport, and is otherwise silent about it - so this follows the
     * movement the client sends, and keeps following it to the last packet before they leave.
     */
    Vec3 position();

    /**
     * Which way they are looking: pitch, yaw and roll, in that order.
     *
     * <p>From the same place as {@link #position()}, and just as current.
     */
    Vec3 rotation();

    /**
     * The player's stats by name - {@code Health}, {@code Stamina}, {@code Oxygen} and the rest.
     *
     * <p>Names come from the server's own catalog, so they are the same names the save uses.
     */
    Map<String, Double> stats();

    /**
     * The inventory panels by name: {@code Storage}, {@code Armor}, {@code Hotbar},
     * {@code Utility}, {@code Tools}, {@code Backpack} - whichever the server has sent.
     */
    Map<String, Section> inventories();
}
