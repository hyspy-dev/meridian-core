package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.UpdateWorldMap;

/**
 * S2C MONITOR observer on the WorldMap channel: every tile the server draws is remembered.
 *
 * <p>MONITOR and always {@code FORWARD} — core collects, it does not decide what the player
 * sees. A module that wants to change the client's view (keep explored tiles, thin them out,
 * change resolution) does that itself, downstream of this.
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
