package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.assets.UpdateItems;
import meridian.protocol.packets.assets.UpdateUnarmedInteractions;

/**
 * MONITOR-position S2C handler. Feeds the server's item asset packets
 * ({@code UpdateItems}, {@code UpdateUnarmedInteractions}) into
 * {@link ItemRegistry}. Observe-only — never mutates traffic.
 */
final class ItemRegistryObserver implements PacketHandler {
    private final ItemRegistry registry;

    ItemRegistryObserver(ItemRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateItems items) {
            registry.onUpdateItems(items);
        } else if (packet instanceof UpdateUnarmedInteractions unarmed) {
            registry.onUnarmedInteractions(unarmed);
        }
        return Action.FORWARD;
    }
}
