package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.ClearWorldMap;
import meridian.protocol.packets.worldmap.UpdateWorldMap;

/**
 * The map channel, on its way to the client.
 *
 * <p>Runs early, before the window trims chunks out of the same packet: the window decides
 * whether an update is worth forwarding at all by looking at what is left in it, markers
 * included, so the markers have to be settled first.
 *
 * <p>This is also where the session for the map channel is picked up. A forged marker update has
 * to travel the way the real ones do, and this is the only place a map packet is seen.
 */
final class MarkerChannelHandler implements PacketHandler {

    private final MapMarkersImpl markers;
    private final SessionHolder mapSession;

    MarkerChannelHandler(MapMarkersImpl markers, SessionHolder mapSession) {
        this.markers = markers;
        this.mapSession = mapSession;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateWorldMap update) {
            mapSession.capture(session);
            return markers.onUpdate(update);
        }
        if (packet instanceof ClearWorldMap) {
            mapSession.capture(session);
            markers.onCleared();
        }
        return Action.FORWARD;
    }
}
