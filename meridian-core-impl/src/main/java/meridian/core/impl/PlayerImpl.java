package meridian.core.impl;

import meridian.core.api.InteractionControl;
import meridian.core.api.Player;
import meridian.core.api.Vec3;

/**
 * The local {@link Player} — position from {@link EntityTrackerImpl}, hotbar
 * from {@link InventoryTracker}, the slot-switch action via {@link InteractionControl}.
 */
final class PlayerImpl implements Player {
    private final EntityTrackerImpl entities;
    private final InventoryTracker inventory;
    private final InteractionControl interactions;

    PlayerImpl(EntityTrackerImpl entities, InventoryTracker inventory,
               InteractionControl interactions) {
        this.entities = entities;
        this.inventory = inventory;
        this.interactions = interactions;
    }

    @Override
    public Vec3 position() {
        return entities.localPosition().orElse(null);
    }

    @Override
    public String heldItem() {
        return inventory.itemInHandId();
    }

    @Override
    public int activeHotbarSlot() {
        return inventory.activeHotbarSlot();
    }

    @Override
    public int hotbarSlotOf(String substring) {
        return inventory.findHotbarSlot(substring);
    }

    @Override
    public void selectHotbarSlot(int slot) {
        interactions.switchHotbarSlot(slot);
    }
}
