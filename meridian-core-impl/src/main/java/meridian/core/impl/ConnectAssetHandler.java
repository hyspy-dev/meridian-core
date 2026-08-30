package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.setup.WorldLoadFinished;

/**
 * Slips our own files into the load, while the client is still taking files in.
 *
 * <p>The client indexes everything it is given during loading, once, and is done. Anything that
 * arrives afterwards needs the index rebuilt, and rebuilding it is what the player feels as a
 * stutter. So whatever we know we will need goes in here, at the last moment before the client
 * decides it has everything.
 */
final class ConnectAssetHandler implements PacketHandler {

    private final ClientAssetsImpl assets;
    private boolean done;

    ConnectAssetHandler(ClientAssetsImpl assets) {
        this.assets = assets;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (!done && packet instanceof WorldLoadFinished && assets.hasConnectAssets()) {
            done = true;
            assets.sendAtConnect(session);
        }
        return Action.FORWARD;
    }
}
