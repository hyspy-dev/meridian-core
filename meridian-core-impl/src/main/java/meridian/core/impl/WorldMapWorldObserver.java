package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.function.Supplier;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.player.JoinWorld;

/**
 * Keeps the map pointed at the world the player is actually in, and captures the session.
 *
 * <p>{@code JoinWorld} arrives on the Default channel on every world change (first join,
 * portal, server move). Two things hang off it:
 *
 * <ul>
 *   <li>the map switches worlds — their coordinate spaces are unrelated, and mixing them would
 *       draw one world's terrain onto another's;</li>
 *   <li>the session is captured here rather than from a map packet, because a session is
 *       per-stream: forging from one captured on another channel sends to the wrong place.</li>
 * </ul>
 *
 * <p>The client's map also starts empty in a new world, so everything that tracks what is on it -
 * the chunk window, the markers, the pushed assets - is reset at the same moment.
 */
final class WorldMapWorldObserver implements PacketHandler {

    private final WorldMapImpl map;
    private final SessionHolder session;
    private final ClientAssetsImpl assets;
    private final Supplier<WorldMapViewImpl> view;
    private final Supplier<MapMarkersImpl> markers;

    WorldMapWorldObserver(WorldMapImpl map, SessionHolder session, ClientAssetsImpl assets,
                          Supplier<WorldMapViewImpl> view, Supplier<MapMarkersImpl> markers) {
        this.map = map;
        this.session = session;
        this.assets = assets;
        this.view = view;
        this.markers = markers;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof JoinWorld j) {
            this.session.capture(session);
            if (j.worldUuid != null) {
                map.setCurrentWorld(j.worldUuid);
            }
            WorldMapViewImpl live = view.get();
            if (live != null) {
                live.reset();
            }
            MapMarkersImpl liveMarkers = markers.get();
            if (liveMarkers != null) {
                liveMarkers.setWorld(j.worldUuid);
            }
            // A world change re-sends the client's assets, so what we believe it holds must go
            // with it: keeping stale hashes would suppress pushes the client now needs.
            assets.reset();
        }
        return Action.FORWARD;
    }
}
