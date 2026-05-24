package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.inventory.SetActiveSlot;
import meridian.protocol.packets.inventory.UpdatePlayerInventory;

/**
 * MONITOR-position handler feeding {@link InventoryTracker}.
 *
 * <p>{@code UpdatePlayerInventory} is server-to-client; {@code SetActiveSlot}
 * travels both ways — observing it in either direction keeps the active-slot
 * mirror current. Observe-only.
 */
final class InventoryObserver implements PacketHandler {
    private final InventoryTracker tracker;

    InventoryObserver(InventoryTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdatePlayerInventory inventory) {
            tracker.onInventory(inventory);
        } else if (packet instanceof SetActiveSlot slot) {
            tracker.onSetActiveSlot(slot);
        }
        return Action.FORWARD;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SetActiveSlot slot) {
            tracker.onSetActiveSlot(slot);
        }
        return Action.FORWARD;
    }
}
