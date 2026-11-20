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
    /** The hotbar's stacks that have worn out: a durability, and none of it left. */
    private final java.util.Set<Integer> brokenHotbar = ConcurrentHashMap.newKeySet();
    /**
     * The hotbar's stacks repaired as far as they go: a kit's repair takes from the most durability
     * the stack has, and at {@link #REPAIR_FLOOR} it is held there - a repair gives it no more than
     * that ({@code RepairItemInteraction}).
     */
    private final java.util.Set<Integer> spentHotbar = ConcurrentHashMap.newKeySet();
    /** The most durability a repair leaves a stack at the least ({@code RepairItemInteraction}). */
    public static final double REPAIR_FLOOR = 10;

    /** How many slots the hotbar has, as the server last said. */
    private volatile int hotbarCapacity = 9;
    private volatile int activeHotbarSlot = 0;
    private volatile int activeUtilitySlot = 0;
    private volatile int activeToolsSlot = -1;
    /** Whether the active utility slot is known - the server or the player's own chains said it. */
    private volatile boolean utilityKnown;

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    void onInventory(UpdatePlayerInventory packet) {
        load(packet.hotbar, hotbar);
        if (packet.hotbar != null && packet.hotbar.items != null) {
            brokenHotbar.clear();
            spentHotbar.clear();
            packet.hotbar.items.forEach((slot, item) -> {
                if (item != null && item.maxDurability > 0 && item.durability <= 0) {
                    brokenHotbar.add(slot);
                }
                if (item != null && item.maxDurability > 0 && item.maxDurability <= REPAIR_FLOOR) {
                    spentHotbar.add(slot);
                }
            });
        }
        if (packet.hotbar != null && packet.hotbar.capacity > 0) {
            hotbarCapacity = packet.hotbar.capacity;
        }
        load(packet.utility, utility);
        load(packet.tools, tools);
        log.info("meridian-core: inventory — hotbar={} utility={} tools={} item(s)",
                hotbar.size(), utility.size(), tools.size());
    }

    void onSetActiveSlot(SetActiveSlot packet) {
        switch (packet.inventorySectionId) {
            case SECTION_HOTBAR -> activeHotbarSlot = packet.activeSlot;
            case SECTION_UTILITY -> {
                activeUtilitySlot = packet.activeSlot;
                utilityKnown = true;
            }
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
        utilityKnown = true;
    }

    /** Whether the active utility slot is known: the server set it, or the player's own chain carried it. */
    public boolean utilitySlotKnown() {
        return utilityKnown;
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

    /** Whether the stack in hotbar {@code slot} has worn out - a durability with none of it left. */
    public boolean hotbarBroken(int slot) {
        return brokenHotbar.contains(slot);
    }

    /** Whether the stack in hotbar {@code slot} is repaired as far as it goes ({@link #REPAIR_FLOOR}). */
    public boolean hotbarSpent(int slot) {
        return spentHotbar.contains(slot);
    }

    /** How many slots the hotbar has. */
    public int hotbarCapacity() {
        return hotbarCapacity;
    }

    /** The hotbar as it stands: slot to item id, empty slots left out. */
    public Map<Integer, String> hotbarItems() {
        return Map.copyOf(hotbar);
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
