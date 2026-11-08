package meridian.core.impl.assets;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.setup.WorldSettings;

/**
 * Hears which client files the server has ({@code WorldSettings.requiredAssets}), for
 * {@link GameAssetsImpl}: a file it announced that the game lacks as is is the server's own.
 * Early, so it sees the list as the server sent it.
 */
public final class GameAssetsObserver implements PacketHandler {

    private final GameAssetsImpl assets;

    public GameAssetsObserver(GameAssetsImpl assets) {
        this.assets = assets;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof WorldSettings settings) {
            assets.announced(settings.requiredAssets);
        }
        return Action.FORWARD;
    }
}
