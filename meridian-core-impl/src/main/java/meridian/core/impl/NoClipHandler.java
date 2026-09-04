package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.player.JoinWorld;
import meridian.protocol.packets.player.SetNoClip;

/**
 * Keeps client no-clip on while {@link NoClipImpl} wants it on.
 *
 * <p>Two ways the client would otherwise lose it: the server sends its own {@code SetNoClip(false)}
 * (on a gamemode change, say), and a world join resets the client's movement state. The first is
 * caught and turned back on in place; the second is re-asserted after the join goes through.
 *
 * <p>Only ever holds it on - it never turns the client's no-clip off behind the player's back.
 */
final class NoClipHandler implements PacketHandler {

    private final NoClipImpl noClip;

    NoClipHandler(NoClipImpl noClip) {
        this.noClip = noClip;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (!noClip.isEnabled()) {
            return Action.FORWARD;
        }
        if (packet instanceof SetNoClip s && !s.enabled) {
            s.enabled = true;                   // the server tried to turn it off; we want it on
            return Action.MODIFIED;
        }
        if (packet instanceof JoinWorld) {
            // The join resets movement state on the client; re-assert once it has landed.
            noClip.assertState();
        }
        return Action.FORWARD;
    }
}
