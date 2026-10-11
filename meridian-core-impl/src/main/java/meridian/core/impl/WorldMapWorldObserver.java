package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.player.ClientReady;
import meridian.protocol.packets.player.JoinWorld;

/**
 * Keeps the map pointed at the world the player is actually in, and captures the session.
 *
 * <p>{@code JoinWorld} arrives on the Default channel on every world change (first join,
 * portal, server move). Several things hang off it:
 *
 * <ul>
 *   <li>queries switch worlds at once - their coordinate spaces are unrelated, and mixing them
 *       would draw one world's terrain onto another's;</li>
 *   <li>what the WorldMap stream sends is filed under the new world only once that stream says
 *       so ({@link MapStreamWorld}): the old world's tail is still on its way. The client's
 *       {@code ClientReady} for the join, seen on the way up, is what arms that;</li>
 *   <li>the session is captured here rather than from a map packet, because a session is
 *       per-stream: forging from one captured on another channel sends to the wrong place.</li>
 * </ul>
 *
 * <p>The client's map also starts empty in a new world, so everything that tracks what is on it -
 * the chunk window and the pushed assets - is reset at the same moment.
 */
final class WorldMapWorldObserver implements PacketHandler {

    private final WorldMapImpl map;
    private final MapStreamWorld streamWorld;
    private final Consumer<UUID> drawWorld;
    private final SessionHolder session;
    private final ClientAssetsImpl assets;
    private final Supplier<WorldMapViewImpl> view;

    WorldMapWorldObserver(WorldMapImpl map, MapStreamWorld streamWorld, Consumer<UUID> drawWorld,
                          SessionHolder session, ClientAssetsImpl assets,
                          Supplier<WorldMapViewImpl> view) {
        this.map = map;
        this.streamWorld = streamWorld;
        this.drawWorld = drawWorld;
        this.session = session;
        this.assets = assets;
        this.view = view;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof JoinWorld j) {
            this.session.capture(session);
            map.setCurrentWorld(j.worldUuid);
            UUID now = streamWorld.joined(j.worldUuid);
            if (now != null) {
                drawWorld.accept(now);      // the first world: nothing of another is in flight
            }
            WorldMapViewImpl live = view.get();
            if (live != null) {
                live.reset();
            }
            // A world change re-sends the client's assets, so what we believe it holds must go
            // with it: keeping stale hashes would suppress pushes the client now needs.
            assets.reset();
        }
        return Action.FORWARD;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof ClientReady r && r.readyForChunks) {
            streamWorld.clientReady();
        }
        return Action.FORWARD;
    }
}
