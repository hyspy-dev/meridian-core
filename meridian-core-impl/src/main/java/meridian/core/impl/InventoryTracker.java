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
 * <p>Slot <b>contents</b> come from S2C {@code UpdatePlayerInventory} (the server
 * pushes it on every inventory change). The <b>active slot</b> comes from the
 * player's own interaction chains — the client never sends a standalone
 * {@code SetActiveSlot} for the hotbar (the server disconnects a client that
 * does), so a hotbar switch reaches us only as a {@code SwapFrom} chain.
 *
 * <p>Together these give {@code activeHotbarSlot} / {@code itemInHandId} (and the
 * utility / tools counterparts), which a forge must set to server truth.
 */
public final class InventoryTracker {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    // SetActiveSlot section ids — protocol constants (Layer-1 absorbs them).
    private static final int SECTION_HOTBAR = -1;
    private static final int SECTION_UTILITY = -5;
    private static final int SECTION_TOOLS = -8;

    /** slot index → item id, per section (from S2C {@code UpdatePlayerInventory}). */
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
     * Mirrors the active slots from an observed interaction chain. The hotbar
     * slot reaches the server only on interaction packets, so the player's own
     * chains are the one reliable mirror of it.
     *
     * <p><b>Caller must pass the resulting slot for a {@code SwapFrom}</b> — that
     * chain's {@code activeHotbarSlot} is the slot <i>before</i> the switch; the
     * new active slot is its {@code data.targetSlot}. Passing the wrong one reads
     * the held item from the previous slot (the "wrong block" bug).
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

    /** Item id in hotbar {@code slot}, or {@code null} if empty. */
    public String hotbarItem(int slot) {
        return hotbar.get(slot);
    }

    /** First hotbar slot holding an item whose id contains {@code substring}, or -1. */
    public int findHotbarSlot(String substring) {
        for (Map.Entry<Integer, String> e : hotbar.entrySet()) {
            if (e.getValue().contains(substring)) {
                return e.getKey();
            }
        }
        return -1;
    }
}
