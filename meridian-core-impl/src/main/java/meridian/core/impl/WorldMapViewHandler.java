package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.MapChunk;
import meridian.protocol.packets.worldmap.UpdateWorldMap;

/**
 * Lets {@link WorldMapViewImpl} edit the server's map updates on their way to the client.
 *
 * <p>NORMAL, not MONITOR: this one mutates. It records what the client is being given and
 * removes an unload for a tile the view wants kept — the mechanism that stops explored ground
 * from fading out behind the player. A packet left with nothing to say (its only content was a
 * suppressed unload, and it carries no markers) is dropped rather than sent empty.
 */
final class WorldMapViewHandler implements PacketHandler {

    private final WorldMapViewImpl view;

    WorldMapViewHandler(WorldMapViewImpl view) {
        this.view = view;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (!(packet instanceof UpdateWorldMap m) || m.chunks == null) {
            return Action.FORWARD;
        }
        MapChunk[] filtered = view.filterServerUpdate(m.chunks);
        if (filtered == m.chunks) {
            return Action.FORWARD;      // untouched: the server's own bytes go on unchanged
        }
        if (filtered == null && m.addedMarkers == null && m.removedMarkers == null) {
            return Action.DROP;
        }
        m.chunks = filtered;
        // MODIFIED, not FORWARD: the router re-serialises only what a handler says it changed,
        // so a mutated packet forwarded as FORWARD reaches the client as it was.
        return Action.MODIFIED;
    }
}
