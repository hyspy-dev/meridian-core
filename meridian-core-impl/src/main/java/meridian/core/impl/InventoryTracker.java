package meridian.core.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import meridian.protocol.InventorySection;
import meridian.protocol.ItemWithAllMetadata;
import meridian.protocol.packets.inventory.SetActiveSlot;
import meridian.protocol.packets.inventory.UpdatePlayerInventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The player's hotbar / utility / tools inventory, mirrored inside the proxy.
 *
 * <p>Built from observed S2C {@code UpdatePlayerInventory} (per-slot item ids)
 * and {@code SetActiveSlot} (the active slot of each section). Forging an
 * interaction chain requires these to match server truth exactly, so this
 * tracker is the source for {@code activeHotbarSlot} / {@code itemInHandId} and
 * their utility / tools counterparts.
 */
public final class InventoryTracker {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    // SetActiveSlot section ids — protocol constants (Layer-1 absorbs them).
    private static final int SECTION_HOTBAR = -1;
    private static final int SECTION_UTILITY = -5;
    private static final int SECTION_TOOLS = -8;

    /** slot index → item id, per section. */
    private final Map<Integer, String> hotbar = new ConcurrentHashMap<>();
    private final Map<Integer, String> utility = new ConcurrentHashMap<>();
    private final Map<Integer, String> tools = new ConcurrentHashMap<>();

    private volatile int activeHotbarSlot = 0;
    private volatile int activeUtilitySlot = 0;
    private volatile int activeToolsSlot = -1;

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    void onInventory(UpdatePlayerInventory packet) {
        load(packet.hotbar, hotbar);
        load(packet.utility, utility);
        load(packet.tools, tools);
        log.info("meridian-core: inventory — hotbar={} utility={} tools={} item(s)",
                hotbar.size(), utility.size(), tools.size());
    }

    void onSetActiveSlot(SetActiveSlot packet) {
        switch (packet.inventorySectionId) {
            case SECTION_HOTBAR -> activeHotbarSlot = packet.activeSlot;
            case SECTION_UTILITY -> activeUtilitySlot = packet.activeSlot;
            case SECTION_TOOLS -> activeToolsSlot = packet.activeSlot;
            default -> { /* other inventory section — not needed for forging */ }
        }
    }

    /**
     * Observes the active slots an interaction chain carries.
     *
     * <p>The client never sends a standalone {@code SetActiveSlot} for the
     * hotbar — the server disconnects a client that does
     * ({@code hotbarChangeWithoutInteraction}). The hotbar slot reaches the
     * server only as the {@code activeHotbarSlot} field of interaction packets,
     * so the player's own chains are the one reliable mirror of it.
     */
    public void observeActiveSlots(int hotbar, int utility, int tools) {
        activeHotbarSlot = hotbar;
        activeUtilitySlot = utility;
        activeToolsSlot = tools;
    }

    /** Replaces {@code dst} with the contents of {@code section} (if present). */
    private static void load(InventorySection section, Map<Integer, String> dst) {
        if (section == null || section.items == null) {
            return; // section absent from this (possibly partial) update — keep current
        }
        dst.clear();
        for (Map.Entry<Integer, ItemWithAllMetadata> e : section.items.entrySet()) {
            ItemWithAllMetadata item = e.getValue();
            if (item != null && item.itemId != null && !item.itemId.isEmpty()) {
                dst.put(e.getKey(), item.itemId);
            }
        }
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    public int activeHotbarSlot() {
        return activeHotbarSlot;
    }

    public int activeUtilitySlot() {
        return activeUtilitySlot;
    }

    public int activeToolsSlot() {
        return activeToolsSlot;
    }

    /** Item id held in the main hand, or {@code null} for an empty hand. */
    public String itemInHandId() {
        return hotbar.get(activeHotbarSlot);
    }

    /** Item id in the active utility slot, or {@code null}. */
    public String utilityItemId() {
        return utility.get(activeUtilitySlot);
    }

    /** Item id in the active tools slot, or {@code null}. */
    public String toolsItemId() {
        return tools.get(activeToolsSlot);
    }

    /** First hotbar slot holding an item whose id contains {@code substring}. */
    int findHotbarSlot(String substring) {
        for (Map.Entry<Integer, String> e : hotbar.entrySet()) {
            if (e.getValue().contains(substring)) {
                return e.getKey();
            }
        }
        return -1;
    }
}
