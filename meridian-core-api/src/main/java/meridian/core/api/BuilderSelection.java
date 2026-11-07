package meridian.core.api;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The game's builder toolbar, borrowed for a client the server did not hand it to.
 *
 * <p>On the 0.6 line the server tells the client which builder tools it may use. Forcing a set of
 * ids into that list puts those tools in the editor toolbar; the player can then select one and
 * press F on it. F is an {@code InteractionType.Use}, which the client reports as a
 * {@code SyncInteractionChains} naming the tool - core catches it, tells whoever registered that
 * tool, and drops it so the server never acts on a tool it never really granted.
 *
 * <p>The selection tool is special: dragging it reports a box, captured here as well.
 *
 * <p>Offered only on a line whose protocol carries the builder-tool packets - 0.6 and later. On an
 * older line no one provides it, so a module asking with {@code get} falls back to its own way.
 */
public interface BuilderSelection {

    /** The item id of the native area-selection tool. */
    String SELECTION_TOOL = "EditorTool_Selection";
    /** The item id of the native prefab-paste tool. */
    String PASTE_TOOL = "EditorTool_Paste";
    /** The item id of the native layers tool. */
    String LAYERS_TOOL = "EditorTool_Layers";
    /** The item id of the native colour tool. */
    String COLOR_TOOL = "EditorTool_Color";
    /** The item id of the native extrude tool. */
    String EXTRUDE_TOOL = "EditorTool_Extrude";

    /** A selected box, in world blocks, corners sorted so min is min. */
    record Box(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax) {}

    /** What the player did with a tool: left-click, right-click, or the F/use key. */
    enum Interaction { PRIMARY, SECONDARY, USE }

    /**
     * A tool of the module's own: its item id, the native tool it is modelled on, and the name and
     * description the player reads on it.
     *
     * <p>The client only knows the items its server sent it, so an id of ours would show up as
     * "Invalid Item". {@code basedOn} names a native tool to take the <em>look</em> from - icon,
     * model, animation - and nothing else: none of its builder-tool behaviour, arguments, legend or
     * click bindings follow, so the tool does not act like the one it borrows from. Pick a
     * {@code basedOn} whose icon suits the job.
     *
     * <p>A tool declared this way answers to F ({@link Interaction#USE}), reported through
     * {@link #onTool}. Left- and right-click are not bound to it.
     *
     * @param id          the item id the tool is known by, the one {@code onTool} reports
     * @param basedOn     item id of the native tool to copy the look and behaviour from
     * @param name        the tool's name, as shown to the player
     * @param description the line under the name
     */
    record ToolSpec(String id, String basedOn, String name, String description) {}

    /**
     * Declares tools of the module's own, so the client knows them as real items.
     *
     * <p>Call before {@link #forceTools}; the definitions are handed to the client each time the
     * tools go up. Declaring the same id again replaces the previous definition.
     */
    void defineTools(List<ToolSpec> tools);

    /**
     * Puts these tools in the client's toolbar, one per slot, and takes back whatever was there.
     *
     * <p>The list is read by slot: index 0 is the first slot, and a {@code null} entry leaves that
     * slot empty. An empty list clears our tools and restores the toolbar the server last sent.
     * Any id is allowed, native ({@link #SELECTION_TOOL}) or a module's own - the client shows what
     * it can and reports F on any of them the same way.
     */
    void forceTools(List<String> slots);

    /** The tool ids currently forced, empties dropped. */
    List<String> forcedTools();

    /**
     * Called when the player left-clicks, right-clicks or F's one of the forced tools - the tool's
     * id and which of the three it was. The interaction is answered here and never reaches the
     * server, so the module owns what each does. Runs on the network thread; do the slow part
     * elsewhere.
     */
    void onTool(BiConsumer<String, Interaction> listener);

    /** The box the player has dragged with the selection tool, if any stands. */
    Optional<Box> current();

    /** Calls back whenever the dragged box changes; the latest is also always in {@link #current}. */
    void onSelection(Consumer<Box> listener);

    // ------------------------------------------------------------------
    // The paste tool, holding a clipboard of ours
    // ------------------------------------------------------------------

    /** A world axis, for a turn of the paste tool's clipboard. */
    enum Axis { X, Y, Z }

    /** What the player does with the paste tool while it holds a clipboard of ours. */
    interface PasteListener {

        /**
         * The clipboard was turned around {@code axis}, about its anchor, by {@code degrees} - a
         * multiple of 90, signed the way the client sends it. The client turns what it shows under
         * the cursor by itself, then waits for the turned clipboard as the server would send it:
         * answer with {@link #confirmTurn}.
         */
        void turned(Axis axis, int degrees);

        /**
         * The clipboard was mirrored across {@code axis} through its anchor - the paste tool's flip
         * key, which the client sends as a {@code /flip} command. Answer with {@link #confirmTurn}.
         * The axis is the server's: the one the player looks along, or the one a typed direction
         * names.
         */
        default void flipped(Axis axis) {}

        /**
         * The player asked for the prefab list (the paste tool's own key, sent as
         * {@code /prefab list}). The server's list would offer its prefabs, and only to an editor.
         */
        default void listAsked() {}

        /**
         * The player copied the selection - the selection tool's copy key, or {@code /copy} typed
         * in chat - while our tools are out. The server would copy into its own clipboard, and only
         * for an editor; fill the paste tool with {@link #setClipboard} instead.
         */
        default void copyAsked() {}

        /** Every turn and flip so far was undone; hand the clipboard back with {@link #setClipboard}. */
        void reset();

        /** The player pasted: the clipboard's anchor goes at this block. */
        void pasted(int x, int y, int z);
    }

    /**
     * Fills the game's own paste tool with blocks of ours, so the player handles a blueprint with
     * the real tool - it follows the cursor, turns on the usual keys, and shows every block.
     *
     * <p>The client keeps its clipboard from the server's {@code EditorBlocksChange}, sent after a
     * copy or a cut; ours is handed over the same way, unturned. While it is set, the server's own
     * clipboard updates are held back so they cannot replace it, and the tool's turns, reset and
     * paste are reported to {@link #onPaste} and never reach the server - nothing is pasted into
     * the world. {@code null} or an empty list hands the tool back to the server's clipboard.
     *
     * @param blocks positions relative to the clipboard's anchor, with each block's rotation
     */
    default void setClipboard(List<PastePreview.Change> blocks) {
        setClipboard(blocks, List.of());
    }

    /**
     * {@link #setClipboard(List)} with fluids as well - water and lava the paste tool shows
     * beside the blocks. An empty clipboard is one with no blocks; fluids alone do not make one.
     *
     * @param fluids fluid cells relative to the same anchor; {@code null} for none
     */
    void setClipboard(List<PastePreview.Change> blocks, List<PastePreview.FluidChange> fluids);

    /**
     * Answers a turn or a flip the way the server does: the clipboard as it stands after it, sent
     * with the angles turned so far; the client rebuilds its preview from it. Call from
     * {@link PasteListener#turned} or {@link PasteListener#flipped}.
     *
     * @param turned the clipboard after every turn and flip so far, relative to its anchor
     */
    default void confirmTurn(List<PastePreview.Change> turned) {
        confirmTurn(turned, List.of());
    }

    /** {@link #confirmTurn(List)} with the clipboard's fluids, turned the same way. */
    void confirmTurn(List<PastePreview.Change> turned, List<PastePreview.FluidChange> fluids);

    /**
     * Shows blocks in the spinning preview of the game's prefab list - the {@code
     * PrefabPreviewComponent} of {@code Pages/PrefabListPage.ui} - as the server does when a
     * prefab there is clicked. {@code null} or an empty list empties it.
     *
     * @param blocks positions relative to the prefab's anchor, with each block's rotation
     */
    void showPrefabPreview(List<PastePreview.Change> blocks);

    /** Called for what the player does with the paste tool while our clipboard is in it. */
    void onPaste(PasteListener listener);
}
