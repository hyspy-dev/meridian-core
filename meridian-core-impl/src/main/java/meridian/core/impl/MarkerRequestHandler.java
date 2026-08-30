package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.player.RemoveMapMarker;
import meridian.protocol.packets.worldmap.CreateUserMarker;
import meridian.protocol.packets.worldmap.TeleportToWorldMapMarker;

/**
 * What the player does with markers in game, on its way to the server.
 *
 * <p>Each of these is intercepted rather than watched, because each can be answered here: a
 * marker on a map only we keep is ours to make or delete, and asking the server about one it has
 * never heard of would at best do nothing.
 */
final class MarkerRequestHandler implements PacketHandler {

    private final MapMarkersImpl markers;

    MarkerRequestHandler(MapMarkersImpl markers) {
        this.markers = markers;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof CreateUserMarker create) {
            return markers.onClientCreate(create);
        }
        if (packet instanceof RemoveMapMarker remove) {
            return markers.onClientRemove(remove.markerId);
        }
        if (packet instanceof TeleportToWorldMapMarker teleport) {
            return markers.onClientTeleport(teleport.id);
        }
        return Action.FORWARD;
    }
}
