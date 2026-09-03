package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.Asset;
import meridian.protocol.packets.setup.RequestAssets;
import meridian.protocol.packets.setup.WorldSettings;

/**
 * Makes the connect-time files part of the server's own asset exchange.
 *
 * <p>The exchange: the server announces every file the client must have, hash and name, in
 * {@code WorldSettings.requiredAssets}; the client keeps a cache by hash and asks for what it
 * lacks with {@code RequestAssets}; the server sends those, asks for an index rebuild, and only
 * then declares the block types - which is where the client binds each type to its textures,
 * once, from the names that announcement gave. A file the client merely holds is not in that
 * index (measured: only the block-breaking overlay, which resolves by name, ever drew such a
 * file), so this handler adds the registered files to the announcement on its way to the
 * client, then answers the client's request for them itself and strips them from the request
 * before it reaches the server - which throws on a hash it does not know.
 *
 * <p>One instance serves one direction; the two share nothing but {@link ClientAssetsImpl}.
 */
final class ConnectAssetHandler implements PacketHandler {

    private final ClientAssetsImpl assets;
    private boolean done;

    ConnectAssetHandler(ClientAssetsImpl assets) {
        this.assets = assets;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (!(packet instanceof WorldSettings settings) || !assets.hasConnectAssets()) {
            return Action.FORWARD;
        }
        Set<String> announced = new HashSet<>();
        List<Asset> merged = new ArrayList<>();
        if (settings.requiredAssets != null) {
            for (Asset a : settings.requiredAssets) {
                merged.add(a);
                announced.add(a.name);
            }
        }
        int added = 0;
        for (Asset ours : assets.connectAssets()) {
            if (announced.add(ours.name)) {   // a name the server ships itself is its own
                merged.add(ours);
                added++;
            }
        }
        if (added == 0) {
            return Action.FORWARD;
        }
        settings.requiredAssets = merged.toArray(Asset[]::new);
        return Action.MODIFIED;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (done || !(packet instanceof RequestAssets request) || !assets.hasConnectAssets()) {
            return Action.FORWARD;
        }
        done = true;
        // Send every registered file now, whether or not the client asked for it: the announcement
        // in WorldSettings may leave the client thinking it already has one, but the index built
        // from these bytes is what the block types bind against a moment later, so they must be in.
        assets.sendAtConnect(session);
        // Strip any of ours the client did ask for from the request that goes on to the server -
        // it throws on a hash it does not know.
        if (request.assets == null || request.assets.length == 0) {
            return Action.FORWARD;
        }
        List<Asset> forServer = new ArrayList<>();
        for (Asset a : request.assets) {
            if (!assets.isConnectHash(a.hash)) {
                forServer.add(a);
            }
        }
        if (forServer.size() == request.assets.length) {
            return Action.FORWARD;
        }
        request.assets = forServer.toArray(Asset[]::new);
        return Action.MODIFIED;
    }
}
