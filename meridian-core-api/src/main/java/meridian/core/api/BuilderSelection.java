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
}
