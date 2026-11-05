package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.api.packet.Packet;
import meridian.protocol.packets.connection.Connect;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.interface_.CustomPageEvent;
import meridian.protocol.packets.interface_.SetPage;

/**
 * Keeps what the client says about our pages from the server, and notices when the server's own
 * page takes the screen from ours.
 */
final class PagesHandler implements PacketHandler {

    private final PagesImpl pages;

    PagesHandler(PagesImpl pages) {
        this.pages = pages;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof Connect) {
            pages.reset();
            return Action.FORWARD;
        }
        if (!(packet instanceof CustomPageEvent event)) {
            return Action.FORWARD;
        }
        switch (event.type) {
            case Acknowledge:
                return pages.takeAck() ? Action.DROP : Action.FORWARD;
            case Data:
                if (!pages.ownsPage()) {
                    return Action.FORWARD;
                }
                pages.onData(event.data);
                return Action.DROP;
            case Dismiss:
                if (!pages.ownsPage()) {
                    return Action.FORWARD;
                }
                pages.onDismiss();
                return Action.DROP;
            default:
                return Action.FORWARD;
        }
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof CustomPage page) {
            pages.onServerPage(page.key);
        } else if (packet instanceof SetPage) {
            pages.onServerPage(null);
        }
        return Action.FORWARD;
    }
}
