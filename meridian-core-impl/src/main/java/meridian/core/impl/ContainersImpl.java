package meridian.core.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import meridian.core.api.BlockPos;
import meridian.core.api.Containers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the player has seen inside containers, kept for as long as the connection lasts.
 *
 * <p>Windows are matched to blocks here: the packet that opens a container says what is in it but
 * not where it is, so the block the player was last interacting with is the answer - the server
 * opens a container in answer to that interaction and nothing else. A window keeps its block for
 * as long as it is open, so the updates that follow land in the right place.
 *
 * <p>The interaction that names the block is the chain the client runs ({@code Open_Container}),
 * not the mouse packet: on a live server the mouse packet before a chest opens carries no block at
 * all, and a window with no block to belong to is a chest whose contents are quietly lost. Both
 * are watched now, and the log says which one answered.
 */
final class ContainersImpl implements Containers {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** Where each open window's container is, by the window's id. */
    private final Map<Integer, BlockPos> windows = new ConcurrentHashMap<>();
    /** What was last seen in each container. */
    private final Map<BlockPos, Contents> seen = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** The block the player is interacting with; the next container window belongs to it. */
    private volatile BlockPos target;

    @Override
    public void subscribe(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    @Override
    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public Contents at(BlockPos block) {
        return seen.get(block);
    }

    @Override
    public Map<BlockPos, Contents> all() {
        return Map.copyOf(seen);
    }

    // ------------------------------------------------------------------
    // Ingest - from ContainerHandler, on the network threads
    // ------------------------------------------------------------------

    /** The player reached for a block; if a container opens next, this is the one. */
    void onTargetBlock(int x, int y, int z) {
        target = new BlockPos(x, y, z);
    }

    /**
     * A window has opened. If it belongs to a block, it belongs to the one the player just
     * reached for - the server opens it in answer to that and nothing else.
     *
     * <p>Says what it decided, once per window: this is the one place where a chest's contents
     * can quietly go missing, and a line in the log naming the window and the block is what tells
     * a full chest from an unnoticed one.
     */
    void onOpened(int windowId, boolean ofABlock, String type, Contents contents) {
        int items = contents == null ? 0 : contents.items().size();
        if (!ofABlock) {
            log.debug("meridian-core: window {} ({}) belongs to no block", windowId, type);
            return;
        }
        BlockPos block = target;
        if (block == null) {
            log.info("meridian-core: window {} ({}, {} item(s)) opened, but nothing was being "
                    + "reached for - its contents belong to no block we know", windowId, type,
                    items);
            return;
        }
        windows.put(windowId, block);
        log.info("meridian-core: window {} ({}) opened at {} with {} item(s)",
                windowId, type, block, items);
        if (contents != null) {
            remember(block, contents);
        }
    }

    /** A window that is already open, saying what is in it now. */
    void onUpdated(int windowId, Contents contents) {
        BlockPos block = windows.get(windowId);
        if (block == null) {
            log.debug("meridian-core: window {} says what is in it, but we never saw it open",
                    windowId);
            return;
        }
        log.info("meridian-core: window {} at {} now holds {} item(s)",
                windowId, block, contents.items().size());
        remember(block, contents);
    }

    void onClosed(int windowId) {
        windows.remove(windowId);
    }

    private void remember(BlockPos block, Contents contents) {
        seen.put(block, contents);
        for (Listener listener : listeners) {
            try {
                listener.contents(block, contents);
            } catch (RuntimeException e) {
                log.warn("meridian-core: container listener {} threw",
                        listener.getClass().getName(), e);
            }
        }
    }

    /** A fresh connection has nothing seen and no windows open. */
    void reset() {
        windows.clear();
        seen.clear();
        target = null;
    }

    /** The contents of a window, in the shape modules read. */
    static Contents contents(int capacity, Map<Integer, Item> items) {
        return new Contents(capacity, items == null ? Map.of() : new HashMap<>(items));
    }
}
