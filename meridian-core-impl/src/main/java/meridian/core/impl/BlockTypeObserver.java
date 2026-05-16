package meridian.core.impl;

import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.assets.UpdateBlockTypes;
import io.netty.channel.ChannelHandlerContext;

/**
 * MONITOR-position handler that feeds observed S2C {@code UpdateBlockTypes}
 * into {@link WorldStateImpl} as server truth. Never mutates the packet.
 */
final class BlockTypeObserver implements PacketHandler {
    private final WorldStateImpl worldState;

    BlockTypeObserver(WorldStateImpl worldState) {
        this.worldState = worldState;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateBlockTypes update) {
            worldState.onServerBlockTypes(update, session);
        }
        return Action.FORWARD;
    }
}
