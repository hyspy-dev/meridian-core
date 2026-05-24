package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.assets.UpdateInteractions;
import meridian.protocol.packets.assets.UpdateRootInteractions;

/**
 * MONITOR-position S2C handler. Feeds the server's interaction catalog packets
 * ({@code UpdateRootInteractions}, {@code UpdateInteractions}) into
 * {@link InteractionRegistry}. Observe-only — never mutates traffic.
 */
final class InteractionRegistryObserver implements PacketHandler {
    private final InteractionRegistry registry;

    InteractionRegistryObserver(InteractionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateRootInteractions roots) {
            registry.onRootInteractions(roots);
        } else if (packet instanceof UpdateInteractions interactions) {
            registry.onInteractions(interactions);
        }
        return Action.FORWARD;
    }
}
