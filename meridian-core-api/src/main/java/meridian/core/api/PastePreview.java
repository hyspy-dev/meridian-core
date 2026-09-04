package meridian.core.api;

import java.util.List;

/**
 * The game's own paste preview — a ghost of blocks laid over the world, textured, that only the
 * one player sees.
 *
 * <p>It is the server-side builder feature turned to a client's own use: the client already knows
 * how to draw this overlay, so a module that wants to show someone a structure before it exists
 * hands the blocks here and core forges the packets. The blocks are offsets from an anchor, each
 * with a type and a rotation, exactly as the wire carries them.
 *
 * <p>Owned by core because it is vanilla traffic on the Default channel; offered as a service so a
 * module need not know one packet of it. Present on every line - the packets behind it are old.
 */
public interface PastePreview {

    /** One block of the ghost: where it sits relative to the anchor, what it is, how it is turned. */
    record Change(int dx, int dy, int dz, int blockId, int rotation) {}

    /**
     * Shows the ghost, replacing any already up.
     *
     * <p>The client does not merge one of these into the last, so showing again is how the
     * overlay is changed: hide is implied.
     *
     * @param anchorX the world point the offsets are measured from
     */
    void show(float anchorX, float anchorY, float anchorZ, List<Change> blocks);

    /** Takes the ghost down. Harmless when none is up. */
    void hide();

    /** Whether there is a client to draw it - false before anyone has joined. */
    boolean isAvailable();
}
