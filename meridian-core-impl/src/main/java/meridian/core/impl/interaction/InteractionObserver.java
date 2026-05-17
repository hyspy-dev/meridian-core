package meridian.core.impl.interaction;

import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.List;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.interaction.CancelInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChains;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.MouseInteraction;

/**
 * NORMAL-position {@code BOTH}-direction handler feeding {@link InteractionControlImpl}
 * and applying the chain-id NAT ({@link ChainIdNat}).
 *
 * <p><b>Observe.</b> {@code MouseInteraction} carries the looked-at block;
 * {@code SyncInteractionChains} carries the player's own chains — the per-type
 * root ids and replay templates; {@code ClientMovement} carries the movement
 * state {@code ConditionInteraction} branches on.
 *
 * <p><b>Translate.</b> Every interaction {@code chainId} the player sends is
 * rewritten into the proxy-owned server-side space, and server-to-client
 * traffic is rewritten back. A chain the proxy forged is dropped on its way
 * back to the client — the client never created it.
 */
final class InteractionObserver implements PacketHandler {
    private final InteractionControlImpl control;

    InteractionObserver(InteractionControlImpl control) {
        this.control = control;
    }

    // ------------------------------------------------------------------
    // Client -> Server: observe, then translate chain ids up into NAT space
    // ------------------------------------------------------------------

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        control.bind(session);
        if (packet instanceof MouseInteraction mouse) {
            if (mouse.worldInteraction != null) {
                control.onTargetBlock(mouse.worldInteraction.blockPosition);
            }
        } else if (packet instanceof ClientMovement movement) {
            control.onMovementStates(movement.movementStates);
        } else if (packet instanceof SyncInteractionChains chains && chains.updates != null) {
            boolean changed = false;
            for (SyncInteractionChain chain : chains.updates) {
                if (chain == null) {
                    continue;
                }
                control.onClientChain(chain);
                // The client does not stream the looked-at block; its own
                // interaction chains are the reliable target source.
                if (chain.data != null) {
                    control.onTargetBlock(chain.data.blockPosition);
                }
                changed |= translateC2S(chain);
            }
            return changed ? Action.MODIFIED : Action.FORWARD;
        } else if (packet instanceof CancelInteractionChain cancel) {
            int translated = control.nat().toServer(cancel.chainId);
            if (translated != cancel.chainId) {
                cancel.chainId = translated;
                return Action.MODIFIED;
            }
        }
        return Action.FORWARD;
    }

    /** Rewrites a chain's {@code chainId} (and any nested forks) into NAT space. */
    private boolean translateC2S(SyncInteractionChain chain) {
        boolean changed = false;
        int translated = control.nat().toServer(chain.chainId);
        if (translated != chain.chainId) {
            chain.chainId = translated;
            changed = true;
        }
        if (chain.newForks != null) {
            for (SyncInteractionChain fork : chain.newForks) {
                if (fork != null) {
                    changed |= translateC2S(fork);
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Server -> Client: translate chain ids back, drop the proxy's own echoes
    // ------------------------------------------------------------------

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SyncInteractionChains chains && chains.updates != null) {
            List<SyncInteractionChain> kept = new ArrayList<>(chains.updates.length);
            boolean changed = false;
            for (SyncInteractionChain chain : chains.updates) {
                if (chain == null) {
                    continue;
                }
                if (control.nat().isForged(chain.chainId)) {
                    // The server's echo of a chain the proxy forged — the real
                    // client never created it, so it must not see it.
                    changed = true;
                    continue;
                }
                changed |= translateS2C(chain);
                kept.add(chain);
            }
            if (kept.isEmpty()) {
                return Action.DROP;
            }
            if (changed) {
                chains.updates = kept.toArray(new SyncInteractionChain[0]);
                return Action.MODIFIED;
            }
        } else if (packet instanceof CancelInteractionChain cancel) {
            if (control.nat().isForged(cancel.chainId)) {
                return Action.DROP;
            }
            int translated = control.nat().toClient(cancel.chainId);
            if (translated != cancel.chainId) {
                cancel.chainId = translated;
                return Action.MODIFIED;
            }
        }
        return Action.FORWARD;
    }

    /** Rewrites a chain's {@code chainId} (and any nested forks) back to client space. */
    private boolean translateS2C(SyncInteractionChain chain) {
        boolean changed = false;
        int translated = control.nat().toClient(chain.chainId);
        if (translated != chain.chainId) {
            chain.chainId = translated;
            changed = true;
        }
        if (chain.newForks != null) {
            for (SyncInteractionChain fork : chain.newForks) {
                if (fork != null) {
                    changed |= translateS2C(fork);
                }
            }
        }
        return changed;
    }
}
