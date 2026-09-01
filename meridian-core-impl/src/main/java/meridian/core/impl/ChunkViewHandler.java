package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.player.JoinWorld;
import meridian.protocol.packets.setup.ViewRadius;
import meridian.protocol.packets.world.UnloadChunk;

/**
 * Carries out {@link ChunkViewImpl}: holds back the server's unloads and widens the client's view.
 *
 * <p>NORMAL, because it drops. Core's own chunk feed sits at EARLY and has already seen every one
 * of these packets, so nothing that reads the world loses an event to this.
 *
 * <p>{@code ClearChunks} is deliberately not touched. It is not an unload but the seam between two
 * worlds, and a client that keeps the old world's chunks while the new one streams in is a client
 * showing two worlds at once.
 */
final class ChunkViewHandler implements PacketHandler {

    private final ChunkViewImpl view;
    private boolean onDefaultStream;
    private boolean radiusSent;

    ChunkViewHandler(ChunkViewImpl view) {
        this.view = view;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        // One kind of unload to hold back: this build has no batched or per-section form.
        if (view.keepLoaded() && packet instanceof UnloadChunk) {
            return Action.DROP;
        }

        // The radius rides Default, and must go out after the join burst: sent during it, the
        // server's own ViewRadius would land afterwards and win.
        if (packet instanceof ViewRadius || packet instanceof JoinWorld) {
            onDefaultStream = true;
            if (packet instanceof JoinWorld) {
                radiusSent = false;     // a new world starts from the server's own radius
            }
            return Action.FORWARD;
        }
        int radius = view.viewRadius();
        if (radius > 0 && !radiusSent && onDefaultStream) {
            radiusSent = true;
            session.sendToClient(new ViewRadius(radius));
        }
        return Action.FORWARD;
    }
}
