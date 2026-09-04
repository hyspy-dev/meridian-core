package meridian.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import meridian.core.api.BuilderSelection;

/**
 * The native area-selection tool, forced on and read back.
 *
 * <p>Two halves, both in {@link BuilderSelectionHandler}: the server's list of allowed builder
 * tools is widened to include the selection tool on its way to the client, and the box the client
 * then reports is captured here and the report dropped before it reaches the server. This object
 * holds the state between the two - whether we are forcing, and the last box seen.
 *
 * <p>0.6 line and later. The list packet that forces the tool on does not exist earlier, so this
 * and its handler go with it, and the service is not provided on an older line.
 */
final class BuilderSelectionImpl implements BuilderSelection {

    /**
     * The item id forced into the enabled-tools list to show the client the selection tool.
     *
     * <p>The server keys that list by item id. If a build names the selection tool otherwise, this
     * is the one string to change.
     */
    static final String SELECTION_TOOL_ID = "EditorTool_Selection";

    private volatile boolean forced;
    private volatile Box current;
    private final List<Consumer<Box>> listeners = new ArrayList<>();

    @Override
    public void setToolForced(boolean on) {
        forced = on;
    }

    @Override
    public boolean isToolForced() {
        return forced;
    }

    @Override
    public Optional<Box> current() {
        return Optional.ofNullable(current);
    }

    @Override
    public synchronized void onSelection(Consumer<Box> listener) {
        listeners.add(listener);
    }

    /** Called by the handler when the client reports a dragged box. */
    void report(Box box) {
        current = box;
        List<Consumer<Box>> copy;
        synchronized (this) {
            copy = new ArrayList<>(listeners);
        }
        for (Consumer<Box> l : copy) {
            try {
                l.accept(box);
            } catch (RuntimeException e) {
                // a listener that throws is its own problem, not the selection's
            }
        }
    }
}
