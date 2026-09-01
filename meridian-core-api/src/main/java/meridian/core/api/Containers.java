package meridian.core.api;

import java.util.Map;

/**
 * What is inside the containers the player opens.
 *
 * <p>A chest's contents are the server's business: it never tells anyone what is in a chest until
 * somebody opens it, and then it tells only them. So this is the one way a proxy can know - by
 * watching over the player's shoulder as they look inside.
 *
 * <p>Which chest a window belongs to is not in the packet either; the server keeps the position to
 * itself and sends only the contents. It is worked out here from the block the player was
 * interacting with when the window opened, which is the block they opened - the server opens a
 * container in answer to that interaction and nothing else.
 *
 * <p>Listeners are called on the proxy's network threads.
 */
public interface Containers {

    /** One stack in a container, with everything the game stores about it. */
    record Item(String id, int quantity, double durability, double maxDurability, int quality,
                String metadata) {
    }

    /** A container's contents: how many slots it has, and what is in them. */
    record Contents(int capacity, Map<Integer, Item> items) {
    }

    /** Told when a container is opened, and again whenever its contents change while open. */
    @FunctionalInterface
    interface Listener {
        /**
         * @param block    where the container is
         * @param contents what is in it at this moment
         */
        void contents(BlockPos block, Contents contents);
    }

    void subscribe(Listener listener);

    void unsubscribe(Listener listener);

    /** What was last seen inside the container at this block, if it has ever been opened. */
    Contents at(BlockPos block);

    /** Every container the player has opened, by where it is. */
    Map<BlockPos, Contents> all();
}
