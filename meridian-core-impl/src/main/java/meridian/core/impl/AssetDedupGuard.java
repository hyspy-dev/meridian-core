package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.setup.AssetFinalize;
import meridian.protocol.packets.setup.AssetInitialize;
import meridian.protocol.packets.setup.AssetPart;

/**
 * Shields the client's blob store from a duplicate push.
 *
 * <p>The client stores assets content-addressed and treats a second {@code AssetInitialize} for
 * a hash whose download already started as fatal — it disconnects with "a blob download has
 * already started". A server that re-pushes its own assets trips this on its own; sharing the
 * pipeline with our pushes makes it likelier still.
 *
 * <p>So every hash seen this session is remembered, and an identical re-push is dropped along
 * with the parts that follow it. A push is {@code Initialize → Part* → Finalize} and is never
 * interleaved with another, so the parts after a dropped initialize belong to it. Re-pushed
 * content is byte-identical by definition of content addressing, so dropping loses nothing.
 */
final class AssetDedupGuard implements PacketHandler {

    private final ClientAssetsImpl assets;
    /** True between a dropped {@code AssetInitialize} and its {@code AssetFinalize}. */
    private boolean dropping;

    AssetDedupGuard(ClientAssetsImpl assets) {
        this.assets = assets;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof AssetInitialize init) {
            String hash = init.asset == null ? null : init.asset.hash;
            if (assets.seen(hash)) {
                dropping = true;
                return Action.DROP;
            }
            dropping = false;   // a fresh asset begins — resync in case a Finalize was missed
            assets.noteServerPush(hash, init.asset == null ? null : init.asset.name);
            return Action.FORWARD;
        }
        if (dropping && packet instanceof AssetPart) {
            return Action.DROP;
        }
        if (dropping && packet instanceof AssetFinalize) {
            dropping = false;
            return Action.DROP;
        }
        return Action.FORWARD;
    }
}
