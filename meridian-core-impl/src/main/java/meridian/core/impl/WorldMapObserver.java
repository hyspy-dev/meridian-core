package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.UpdateWorldMap;

/**
 * S2C observer on the WorldMap channel: every tile the server draws is remembered.
 *
 * <p>EARLY, and always {@code FORWARD}. What core remembers is what the <em>server</em> said,
 * which is not the same as what the player ends up seeing: the view downstream of this may hold
 * a tile back, and a tile held back must still be remembered - otherwise there would be nothing
 * left to show when it is allowed through later.
 */
final class WorldMapObserver implements PacketHandler {

    private final WorldMapImpl map;

    WorldMapObserver(WorldMapImpl map) {
        this.map = map;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateWorldMap m) {
            map.ingest(m.chunks);
        }
        return Action.FORWARD;
    }
}
