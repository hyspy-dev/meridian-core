package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.entities.PlayAnimation;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.player.SetGameMode;
import meridian.protocol.packets.player.UpdateMovementSettings;

/**
 * NORMAL-position S2C handler: what {@link ClientSpoofImpl} wants the client shown, written into
 * the packets the server sends it - the game mode, flight, the stamina and health bars, the death
 * window and animations.
 *
 * <p>Everything core keeps of these packets it has read by now, at EARLY: the game mode and the
 * stats ({@link PlayerStateHandler}), the movement settings (core's movement), the player's id
 * (the entity tracker, from {@code SetClientId}). Only the client sees what this changes.
 */
final class ClientSpoofHandler implements PacketHandler {

    private final ClientSpoofImpl spoof;

    ClientSpoofHandler(ClientSpoofImpl spoof) {
        this.spoof = spoof;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof EntityUpdates updates) {
            return spoof.rewrite(updates) ? Action.MODIFIED : Action.FORWARD;
        }
        if (packet instanceof SetGameMode mode) {
            return spoof.rewrite(mode) ? Action.MODIFIED : Action.FORWARD;
        }
        if (packet instanceof UpdateMovementSettings settings) {
            return spoof.rewrite(settings) ? Action.MODIFIED : Action.FORWARD;
        }
        if (packet instanceof CustomPage page) {
            return spoof.hides(page) ? Action.DROP : Action.FORWARD;
        }
        if (packet instanceof meridian.protocol.packets.interface_.SetPage set
                && set.page == meridian.protocol.packets.interface_.Page.None) {
            spoof.pageClosed();
            return Action.FORWARD;
        }
        if (packet instanceof PlayAnimation animation) {
            return spoof.hides(animation) ? Action.DROP : Action.FORWARD;
        }
        return Action.FORWARD;
    }
}
