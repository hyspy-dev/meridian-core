package meridian.core.api;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The game's own area-selection tool, borrowed for a client that the server did not hand it to.
 *
 * <p>On the 0.6 line the server tells the client which builder tools it may use; forcing the
 * selection tool into that list lets the player drag out a box with the real in-game tool instead
 * of picking corners by hand. Core reads the box the client then reports and hands it over here.
 *
 * <p>Read-only: the box is captured, never passed on to the server, which gates the tool on a
 * permission and would answer its use with a rejection. The player sees and drags a real
 * selection; the server never hears of it.
 *
 * <p>Offered only on a line whose protocol can force the tool on - 0.6 and later. On an older line
 * no one provides it, so a module asking with {@code get} falls back to its own way of selecting.
 */
public interface BuilderSelection {

    /** A selected box, in world blocks, corners sorted so min is min. */
    record Box(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax) {}

    /** Shows the client the selection tool (or takes it away). Nothing is selected until dragged. */
    void setToolForced(boolean on);

    boolean isToolForced();

    /** The box the player has dragged, if any stands. */
    Optional<Box> current();

    /** Calls back whenever the dragged box changes; the latest is also always in {@link #current}. */
    void onSelection(Consumer<Box> listener);
}
