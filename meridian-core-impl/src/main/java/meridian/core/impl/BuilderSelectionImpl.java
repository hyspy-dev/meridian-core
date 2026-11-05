package meridian.core.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import meridian.core.api.Vec3;
import meridian.api.session.ProxySession;
import meridian.core.api.BuilderSelection;
import meridian.core.api.PastePreview;
import meridian.protocol.InventorySection;
import meridian.protocol.ItemBase;
import meridian.protocol.InteractionType;
import meridian.protocol.ItemTranslationProperties;
import meridian.protocol.UpdateType;
import meridian.protocol.packets.assets.UpdateItems;
import meridian.protocol.packets.buildertools.BuilderToolPrefabPreview;
import meridian.protocol.packets.buildertools.BuilderToolSelectionToolReplyWithClipboard;
import meridian.protocol.packets.buildertools.BuilderToolsEnabledTools;
import meridian.protocol.packets.buildertools.ClipboardEntityChange;
import meridian.protocol.packets.interface_.BlockChange;
import meridian.protocol.packets.interface_.EditorBlocksChange;
import meridian.protocol.packets.interface_.FluidChange;
import meridian.protocol.ItemWithAllMetadata;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.packets.interaction.SyncInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChains;
import meridian.protocol.packets.inventory.SetActiveSlot;
import meridian.protocol.packets.inventory.UpdatePlayerInventory;

/**
 * The builder toolbar, forced on and listened to.
 *
 * <p>Holds which tools are forced, who is waiting on an F, and the last selection box. The wire
 * work - widening the allowed-tools list, catching the F chain, reading the drag - is in
 * {@link BuilderSelectionHandler}.
 *
 * <p>0.6 line and later; the packets it rides do not exist earlier, so this and its handler are
 * stripped there and the service is simply not provided.
 */
final class BuilderSelectionImpl implements BuilderSelection {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("meridian-core");

    /** How many slots the hotbar holds. */
    private static final short HOTBAR_SLOTS = 9;

    /** The hotbar's inventory-section id, as the server names it in {@code SetActiveSlot}. */
    private static final int HOTBAR_SECTION_ID = -1;

    private final SessionHolder session;
    /** The server's own item definitions - the donors our tools are copied from. */
    private final ItemRegistry itemRegistry;
    /** Tools of a module's own, by item id, handed to the client whenever the tools go up. */
    private final Map<String, BuilderSelection.ToolSpec> defined = new LinkedHashMap<>();
    private volatile List<String> forced = List.of();
    /** Slot -&gt; the tool the client believes sits there, null where we left the player's own item.
     *  {@link #forced} is the same set with the gaps squeezed out, so only this one maps slots. */
    private volatile List<String> layout = List.of();
    /** The hotbar the server last sent, so our tools sit on top of the player's real items and go
     *  back when we let go. */
    private volatile InventorySection savedHotbar;
    /** The allow-list the server last sent while we were not forcing, so ours is added on top of it. */
    private volatile String[] savedEnabled;
    private volatile Box current;
    private final List<Consumer<Box>> boxListeners = new ArrayList<>();
    private final List<BiConsumer<String, BuilderSelection.Interaction>> useListeners = new ArrayList<>();
    private final List<PasteListener> pasteListeners = new ArrayList<>();
    /** The blocks the paste tool holds instead of the server's clipboard; null when it is the server's. */
    private volatile List<PastePreview.Change> clipboard;
    /** Degrees the clipboard is turned around each axis, 0..359, kept as the server keeps them. */
    private volatile int turnX, turnY, turnZ;

    /**
     * The server's side of the hotbar: which slot it has active. Fed only by what reaches the server,
     * so the slot changes onto our tools - which never do - leave it where the server really is.
     */
    private final InventoryTracker inventory;

    /** Where the player looks - pitch, yaw, roll in radians - for the axis of a flip. */
    private final Supplier<Vec3> look;

    BuilderSelectionImpl(SessionHolder session, ItemRegistry itemRegistry, InventoryTracker inventory,
            Supplier<Vec3> look) {
        this.session = session;
        this.itemRegistry = itemRegistry;
        this.inventory = inventory;
        this.look = look;
    }

    /**
     * The axis {@code /flip} mirrors across, the way the server picks it: a typed {@code x}/{@code
     * y}/{@code z} as is, up/down as Y, forward/back along the look, left/right across it, and no
     * argument the axis the look is closest to.
     *
     * @return null for a direction the server would refuse, or a look not yet known
     */
    BuilderSelection.Axis flipAxis(String target) {
        String t = target == null ? "" : target.trim().toLowerCase(java.util.Locale.ROOT);
        switch (t) {
            case "x": return BuilderSelection.Axis.X;
            case "y": return BuilderSelection.Axis.Y;
            case "z": return BuilderSelection.Axis.Z;
            case "u": case "up": case "d": case "down": return BuilderSelection.Axis.Y;
            default: break;
        }
        Vec3 l = look == null ? null : look.get();
        if (l == null) {
            return null;
        }
        double pitch = l.x();
        double yaw = l.y();
        switch (t) {
            case "": {
                // HeadRotation.getAxisDirection, then getAxis: each part rounded on its own.
                double len = Math.cos(pitch);
                long x = Math.round(len * -Math.sin(yaw));
                long y = Math.round(Math.sin(pitch));
                return x != 0 ? BuilderSelection.Axis.X : y != 0 ? BuilderSelection.Axis.Y : BuilderSelection.Axis.Z;
            }
            case "f": case "forward": case "b": case "back": case "backward":
                return horizontal(yaw, false);
            case "l": case "left": case "r": case "right":
                return horizontal(yaw, true);
            default:
                return null;
        }
    }

    /** RelativeDirection's horizontal axis of a look, or the one across it. */
    private static BuilderSelection.Axis horizontal(double yaw, boolean across) {
        boolean alongX = Math.round(-Math.sin(yaw)) != 0;   // getHorizontalAxisDirection().x
        return alongX != across ? BuilderSelection.Axis.X : BuilderSelection.Axis.Z;
    }

    @Override
    public synchronized void defineTools(List<BuilderSelection.ToolSpec> tools) {
        for (BuilderSelection.ToolSpec spec : tools) {
            if (spec != null && spec.id() != null && !spec.id().isBlank()) {
                defined.put(spec.id(), spec);
            }
        }
    }

    /**
     * Teaches the client the module's own tools, by handing it a copy of a native tool's definition
     * under our id with our name and description.
     *
     * <p>Without this the client has never heard of the id and draws the slot as "Invalid Item".
     * Copying a real tool also brings its icon, model and interactions, so ours is a working builder
     * tool rather than a stub.
     */
    private void defineOnClient(ProxySession live) {
        List<BuilderSelection.ToolSpec> specs;
        synchronized (this) {
            if (defined.isEmpty()) {
                return;
            }
            specs = new ArrayList<>(defined.values());
        }
        Map<String, ItemBase> items = new HashMap<>();
        for (BuilderSelection.ToolSpec spec : specs) {
            ItemBase donor = itemRegistry.item(spec.basedOn()).orElse(null);
            if (donor == null) {
                continue;               // the server never sent that tool; nothing to copy
            }
            items.put(spec.id(), lookOf(donor, spec));
        }
        if (items.isEmpty()) {
            return;
        }
        UpdateItems add = new UpdateItems();
        add.type = UpdateType.AddOrUpdate;
        add.items = items;
        add.updateIcons = true;         // so the client binds our ids to the borrowed icons
        live.sendToClient(add);
    }

    /**
     * A tool of ours that only looks like the donor.
     *
     * <p>Built field by field rather than copied and trimmed: a native builder tool carries its
     * whole behaviour with it - {@code builderToolData} with its arguments, the block-selector, the
     * legend overlay, and {@code Primary}/{@code Secondary} bound to {@code Builder_Tool} - and any
     * of that coming along makes our tool act like the real one (its own selection box and all).
     * Only the look is taken; the tool answers to F, which the proxy catches itself and which needs
     * no binding on the item.
     *
     * <p>The item's interaction bindings do come along, all of them. The client acts on a key only
     * through the item in hand's binding for it: switching away needs {@code SwapFrom}, F needs
     * {@code Use}, and so on - and the server fills every item's map from the unarmed bindings, so
     * a real tool has them all. An item missing one is deaf to that key (one without {@code
     * SwapFrom} could be switched onto but never off). {@code Primary}/{@code Secondary} are the
     * builder tool's own root; with no builder data on our item it only raises the chain, which
     * we take.
     */
    private static ItemBase lookOf(ItemBase donor, BuilderSelection.ToolSpec spec) {
        ItemBase ours = new ItemBase();
        ours.id = spec.id();
        ours.icon = donor.icon;
        ours.iconProperties = donor.iconProperties;
        ours.model = donor.model;
        ours.scale = donor.scale;
        ours.texture = donor.texture;
        ours.animation = donor.animation;
        ours.playerAnimationsId = donor.playerAnimationsId;
        ours.usePlayerAnimations = donor.usePlayerAnimations;
        ours.droppedItemAnimation = donor.droppedItemAnimation;
        ours.reticleIndex = donor.reticleIndex;
        ours.soundEventIndex = donor.soundEventIndex;
        ours.itemSoundSetIndex = donor.itemSoundSetIndex;
        ours.maxStack = donor.maxStack > 0 ? donor.maxStack : 1;
        if (donor.interactions != null) {
            ours.interactions = new HashMap<>(donor.interactions);
        }
        ItemTranslationProperties text = new ItemTranslationProperties();
        text.name = spec.name();
        text.description = spec.description();
        ours.translationProperties = text;
        return ours;
    }

    @Override
    public void forceTools(List<String> slots) {
        List<String> present = new ArrayList<>();
        for (String id : slots) {
            if (id != null && !id.isBlank()) {
                present.add(id);
            }
        }
        boolean puttingAway = present.isEmpty() && !forced.isEmpty();
        forced = List.copyOf(present);
        // Keep the gaps: hotbarWith() lays tools out by index, so only this list maps slot -> tool.
        List<String> bySlot = new ArrayList<>(slots);
        layout = present.isEmpty() ? List.of() : java.util.Collections.unmodifiableList(bySlot);
        ProxySession live = session.get().orElse(null);
        if (live == null) {
            return;
        }
        if (!present.isEmpty()) {
            defineOnClient(live);       // the client must know our items before they land in a slot
        }
        UpdatePlayerInventory pkt = new UpdatePlayerInventory();
        pkt.hotbar = present.isEmpty() ? restoreSection() : hotbarWith(slots);
        live.sendToClient(pkt);

        // The toolbar shows our items, but the client only lets the player use a tool the server
        // said they may - so the allow-list is forged too, ours added to whatever the server last
        // allowed. On clear it goes back to just the server's.
        BuilderToolsEnabledTools allow = new BuilderToolsEnabledTools();
        allow.toolIds = present.isEmpty() ? savedEnabled : allowList(present);
        live.sendToClient(allow);
        if (puttingAway) {
            // The client may be standing on a slot that was one of our tools; the server never
            // left its own, so the client goes back there.
            pushActiveSlot(serverSlot());
        }
    }

    /** The server's allowed tools plus ours, without repeats. */
    private String[] allowList(List<String> ours) {
        List<String> out = new ArrayList<>();
        if (savedEnabled != null) {
            for (String id : savedEnabled) {
                out.add(id);
            }
        }
        for (String id : ours) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out.toArray(new String[0]);
    }

    /** The allow-list the server sent while we were idle - the baseline ours is layered onto. */
    void rememberServerEnabled(String[] ids) {
        savedEnabled = ids;
    }

    @Override
    public List<String> forcedTools() {
        return forced;
    }

    /** The player's hotbar with our tools laid over the first slots, keeping their other items. */
    private InventorySection hotbarWith(List<String> slots) {
        InventorySection base = savedHotbar;
        Map<Integer, ItemWithAllMetadata> items = new HashMap<>();
        short capacity = HOTBAR_SLOTS;
        if (base != null) {
            if (base.items != null) {
                items.putAll(base.items);        // keep what the player already had
            }
            if (base.capacity > 0) {
                capacity = base.capacity;
            }
        }
        for (int i = 0; i < slots.size() && i < capacity; i++) {
            String id = slots.get(i);
            if (id != null && !id.isBlank()) {
                items.put(i, new ItemWithAllMetadata(id, 1, 0, 0, 0, false, null));
            }
        }
        return new InventorySection(items, capacity);
    }

    /** The player's real hotbar to hand back, or an empty one if none was seen. */
    private InventorySection restoreSection() {
        InventorySection saved = savedHotbar;
        return saved != null ? saved : new InventorySection(new HashMap<>(), HOTBAR_SLOTS);
    }

    /**
     * Remembers the hotbar the server sent, so it can be restored - unless it is our own injection
     * echoed back (it carries one of our tool ids), which it never puts back.
     */
    void rememberServerHotbar(InventorySection hotbar) {
        if (hotbar == null || hotbar.items == null) {
            return;
        }
        for (ItemWithAllMetadata item : hotbar.items.values()) {
            if (item != null && item.itemId != null && forced.contains(item.itemId)) {
                return;                         // our own injection coming back; ignore it
            }
        }
        savedHotbar = hotbar;
    }

    @Override
    public synchronized void onTool(BiConsumer<String, BuilderSelection.Interaction> listener) {
        useListeners.add(listener);
    }

    @Override
    public Optional<Box> current() {
        return Optional.ofNullable(current);
    }

    @Override
    public synchronized void onSelection(Consumer<Box> listener) {
        boxListeners.add(listener);
    }

    /** The tool the client believes is in this hotbar slot, or null if we did not spoof that slot. */
    String toolAt(int slot) {
        List<String> l = layout;
        if (slot < 0 || slot >= l.size()) {
            return null;
        }
        String id = l.get(slot);
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * Puts the client on a hotbar slot, using the same packet the server sends to correct a player
     * it thinks is desynced.
     *
     * <p>Needed because a tool whose own swap interaction fails on the client makes the client undo
     * the slot change by itself, even though the server accepted it. This walks the client back onto
     * the slot the server already moved to.
     */
    void pushActiveSlot(int slot) {
        ProxySession live = session.get().orElse(null);
        if (live == null || slot < 0) {
            return;
        }
        SetActiveSlot pkt = new SetActiveSlot();
        pkt.inventorySectionId = HOTBAR_SECTION_ID;
        pkt.activeSlot = slot;
        live.sendToClient(pkt);
    }

    /** The hotbar slot the server has active - where the player really is, whatever they hold. */
    int serverSlot() {
        return inventory.activeHotbarSlot();
    }

    /**
     * Rewrites a chain the client sent from one of our tool slots so it reads as sent from where the
     * server is: its active slot and the real item there. The server checks both against its own and
     * cancels the chain - and pulls the client back - on any difference.
     */
    void fromServerSlot(SyncInteractionChain chain) {
        int real = serverSlot();
        if (chain.equipSlot == chain.activeHotbarSlot) {
            chain.equipSlot = real;
        }
        chain.activeHotbarSlot = real;
        chain.itemInHandId = serverHotbarItem(real);
    }

    /**
     * Answers a slot change the server is never told about, the way the server answers one it
     * accepted: the same chain, finished, with nothing forked off it. The client predicted the change
     * and holds it as soon as the answer agrees.
     */
    void answerSwap(SyncInteractionChain asked) {
        ProxySession live = session.get().orElse(null);
        if (live == null) {
            return;
        }
        SyncInteractionChain r = new SyncInteractionChain();
        r.initial = true;
        r.desync = false;
        int root = Integer.MIN_VALUE;
        if (asked.interactionData != null) {
            InteractionSyncData[] ops = new InteractionSyncData[asked.interactionData.length];
            for (int i = 0; i < ops.length; i++) {
                InteractionSyncData op = asked.interactionData[i];
                if (op == null) {
                    continue;
                }
                op.state = InteractionState.Finished;
                ops[i] = op;
                if (root == Integer.MIN_VALUE) {
                    root = op.rootInteraction;
                }
            }
            r.interactionData = ops;
        }
        r.overrideRootInteraction = root;
        r.interactionType = asked.interactionType;
        r.equipSlot = asked.equipSlot;
        r.chainId = asked.chainId;
        r.forkedId = asked.forkedId;
        r.data = asked.data;
        r.state = InteractionState.Finished;
        r.newForks = null;
        r.operationBaseIndex = asked.operationBaseIndex;
        SyncInteractionChains pkt = new SyncInteractionChains();
        pkt.updates = new SyncInteractionChain[] {r};
        live.sendToClient(pkt);
    }

    /**
     * The tool that hides the given real item, or null if no spoofed slot holds it.
     *
     * <p>Matched by item id rather than by slot on purpose: the server leaves {@code
     * activeHotbarSlot} at its default in the answers it echoes back, so the slot there cannot be
     * trusted - only the item id it names can.
     */
    String toolHiding(String realItemId) {
        if (realItemId == null) {
            return null;
        }
        List<String> l = layout;
        for (int slot = 0; slot < l.size(); slot++) {
            String tool = toolAt(slot);
            if (tool != null && realItemId.equals(serverHotbarItem(slot))) {
                return tool;
            }
        }
        return null;
    }

    /** The item id the server really has at this hotbar slot, or null when empty/unknown. */
    String serverHotbarItem(int slot) {
        InventorySection h = savedHotbar;
        if (h == null || h.items == null) {
            return null;
        }
        ItemWithAllMetadata item = h.items.get(slot);
        return item == null ? null : item.itemId;
    }

    /** The handler saw the player act on one of our tools. */
    void fireTool(String toolId, BuilderSelection.Interaction kind) {
        List<BiConsumer<String, BuilderSelection.Interaction>> copy;
        synchronized (this) {
            copy = new ArrayList<>(useListeners);
        }
        for (BiConsumer<String, BuilderSelection.Interaction> l : copy) {
            try {
                l.accept(toolId, kind);
            } catch (RuntimeException e) {
                // a listener that throws is its own problem, not the toolbar's - but say so
                LOG.warn("meridian-core: tool listener failed on {} {}", kind, toolId, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Our clipboard in the paste tool
    // ------------------------------------------------------------------

    @Override
    public void setClipboard(List<PastePreview.Change> blocks) {
        clipboard = blocks == null || blocks.isEmpty() ? null : List.copyOf(blocks);
        turnX = turnY = turnZ = 0;
        if (clipboard != null) {
            sendClipboard(clipboard);
        }
    }

    @Override
    public void confirmTurn(List<PastePreview.Change> turned) {
        if (clipboard != null && turned != null) {
            sendClipboard(turned);
        }
    }

    /** What the server sends past this many blocks: only the bounds, no blocks. */
    private static final int PREVIEW_BLOCK_LIMIT = 4_000_000;

    @Override
    public void showPrefabPreview(List<PastePreview.Change> blocks) {
        ProxySession live = session.get().orElse(null);
        if (live == null) {
            return;
        }
        BuilderToolPrefabPreview pkt = new BuilderToolPrefabPreview();
        if (blocks != null && !blocks.isEmpty() && blocks.size() <= PREVIEW_BLOCK_LIMIT) {
            // The server's own numbers for its prefab list (PrefabPage), and its default tints
            // for where no biome is known.
            pkt.tilt = 23;
            pkt.spinSpeed = 27;
            pkt.previewScale = 100;
            pkt.biomeTint = 0x5B9E28;
            pkt.waterTint = 0x0A3355;
            pkt.blocksChange = toBlockChanges(blocks);
            pkt.fluidsChange = new FluidChange[0];
        }
        live.sendToClient(pkt);
    }

    /** Counts a turn the way the server does, so the confirmation carries the same angles. */
    void turn(BuilderSelection.Axis axis, int degrees) {
        switch (axis) {
            case X -> turnX = Math.floorMod(turnX + degrees, 360);
            case Y -> turnY = Math.floorMod(turnY + degrees, 360);
            case Z -> turnZ = Math.floorMod(turnZ + degrees, 360);
        }
    }

    void resetTurns() {
        turnX = turnY = turnZ = 0;
    }

    /**
     * Hands the client a clipboard the way the server does after a copy, a cut or a turn: every
     * block relative to the anchor, as the preview the paste tool draws under the cursor.
     *
     * <p>Always with the preview rebuilt from these blocks. The server skips the rebuild only for a
     * paste tool set to {@code RotateBlock: All}, where the client spins the preview itself; the
     * paste tool's default is {@code NonUniform}, and there the client waits for the turned blocks.
     */
    private void sendClipboard(List<PastePreview.Change> blocks) {
        ProxySession live = session.get().orElse(null);
        if (live == null) {
            return;
        }
        EditorBlocksChange pkt = new EditorBlocksChange();
        pkt.blocksChange = toBlockChanges(blocks);
        pkt.fluidsChange = new FluidChange[0];
        pkt.advancedPreview = true;
        pkt.blocksCount = blocks.size();
        pkt.skipPreviewRebuild = false;
        pkt.cumulativeRotX = turnX;
        pkt.cumulativeRotY = turnY;
        pkt.cumulativeRotZ = turnZ;
        live.sendToClient(pkt);
    }

    private static BlockChange[] toBlockChanges(List<PastePreview.Change> blocks) {
        BlockChange[] changes = new BlockChange[blocks.size()];
        for (int i = 0; i < changes.length; i++) {
            PastePreview.Change c = blocks.get(i);
            changes[i] = new BlockChange(c.dx(), c.dy(), c.dz(), c.blockId(), (byte) c.rotation());
        }
        return changes;
    }

    @Override
    public synchronized void onPaste(PasteListener listener) {
        if (listener != null) {
            pasteListeners.add(listener);
        }
    }

    /** Whether the paste tool's traffic is ours to answer right now. */
    boolean ownsClipboard() {
        return clipboard != null && !forcedTools().isEmpty();
    }

    /**
     * Answers the selection tool's own paste mode, which asks for the clipboard rather than
     * keeping the one {@link #sendClipboard} hands over.
     */
    void replyWithClipboard() {
        List<PastePreview.Change> blocks = clipboard;
        ProxySession live = session.get().orElse(null);
        if (blocks == null || live == null) {
            return;
        }
        BuilderToolSelectionToolReplyWithClipboard reply = new BuilderToolSelectionToolReplyWithClipboard();
        reply.blocksChange = toBlockChanges(blocks);
        reply.fluidsChange = new FluidChange[0];
        reply.entityChanges = new ClipboardEntityChange[0];
        live.sendToClient(reply);
    }

    /** The handler saw the paste tool turn, reset or paste our clipboard. */
    void firePaste(Consumer<PasteListener> event) {
        List<PasteListener> copy;
        synchronized (this) {
            copy = new ArrayList<>(pasteListeners);
        }
        for (PasteListener l : copy) {
            try {
                event.accept(l);
            } catch (RuntimeException e) {
                // a listener that throws is its own problem, not the paste tool's - but say so
                LOG.warn("meridian-core: paste listener failed", e);
            }
        }
    }

    /** The handler read a dragged box. */
    void report(Box box) {
        current = box;
        List<Consumer<Box>> copy;
        synchronized (this) {
            copy = new ArrayList<>(boxListeners);
        }
        for (Consumer<Box> l : copy) {
            try {
                l.accept(box);
            } catch (RuntimeException e) {
                // likewise
            }
        }
    }
}
