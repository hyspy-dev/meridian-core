package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.ClearWorldMap;
import meridian.protocol.packets.worldmap.UpdateWorldMap;

/**
 * S2C observer on the WorldMap channel: every tile the server draws is remembered.
 *
 * <p>EARLY, and always {@code FORWARD}. What core remembers is what the <em>server</em> said,
 * which is not the same as what the player ends up seeing: the view downstream of this may hold
 * a tile back, and a tile held back must still be remembered - otherwise there would be nothing
 * left to show when it is allowed through later.
 *
 * <p>{@code ClearWorldMap} is the one boundary this stream has between worlds, so this is where
 * the map moves on to the world the player joined ({@link MapStreamWorld}). Ahead of the marker
 * handler, so markers have moved too by the time it sees the same clear.
 */
final class WorldMapObserver implements PacketHandler {

    private final WorldMapImpl map;
    private final MapStreamWorld streamWorld;
    /** Moves everything filed per world on to the world the stream now draws. */
    private final Consumer<UUID> drawWorld;
    private final Supplier<WorldMapViewImpl> view;

    WorldMapObserver(WorldMapImpl map, MapStreamWorld streamWorld, Consumer<UUID> drawWorld,
                     Supplier<WorldMapViewImpl> view) {
        this.map = map;
        this.streamWorld = streamWorld;
        this.drawWorld = drawWorld;
        this.view = view;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateWorldMap m) {
            map.ingest(m.chunks);
        } else if (packet instanceof ClearWorldMap) {
            UUID next = streamWorld.cleared();
            if (next != null) {
                drawWorld.accept(next);
            }
            // Whatever the reason, the client's map is empty after this: what the window
            // believes it put there is gone too.
            WorldMapViewImpl live = view.get();
            if (live != null) {
                live.reset();
            }
        }
        return Action.FORWARD;
    }
}
