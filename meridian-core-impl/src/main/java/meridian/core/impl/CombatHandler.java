package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.interaction.PlayInteractionFor;
import meridian.protocol.packets.player.DamageInfo;
import meridian.protocol.packets.player.JoinWorld;

/**
 * Reads the fight off the server's traffic for {@link CombatImpl}: a hit on the player
 * ({@code DamageInfo}), an attack another entity begins ({@code PlayInteractionFor}), entities
 * gone from view. Never changes a packet.
 */
final class CombatHandler implements PacketHandler {

    private final CombatImpl combat;

    CombatHandler(CombatImpl combat) {
        this.combat = combat;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof DamageInfo info) {
            combat.onDamage(info);
        } else if (packet instanceof PlayInteractionFor play) {
            combat.onPlay(play);
        } else if (packet instanceof EntityUpdates updates && updates.removed != null) {
            for (int id : updates.removed) {
                combat.onRemoved(id);
            }
        } else if (packet instanceof JoinWorld) {
            combat.reset();
        }
        return Action.FORWARD;
    }
}
