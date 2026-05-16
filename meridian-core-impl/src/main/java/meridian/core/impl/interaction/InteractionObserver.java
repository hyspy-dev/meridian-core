package meridian.core.impl.interaction;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.interaction.SyncInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChains;
import meridian.protocol.packets.player.MouseInteraction;

/**
 * MONITOR-position C2S handler feeding {@link InteractionControlImpl}.
 *
 * <p>{@code MouseInteraction} carries the block the player is looking at;
 * {@code SyncInteractionChains} carries the player's own interaction chains —
 * the source for the chainId high-water mark and the per-type root id. Captures
 * the session for forging. Observe-only.
 */
final class InteractionObserver implements PacketHandler {
    private final InteractionControlImpl control;

    InteractionObserver(InteractionControlImpl control) {
        this.control = control;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        control.bind(session);
        if (packet instanceof MouseInteraction mouse) {
            if (mouse.worldInteraction != null) {
                control.onTargetBlock(mouse.worldInteraction.blockPosition);
            }
        } else if (packet instanceof SyncInteractionChains chains && chains.updates != null) {
            for (SyncInteractionChain chain : chains.updates) {
                if (chain != null) {
                    control.onClientChain(chain);
                }
            }
        }
        return Action.FORWARD;
    }
}
