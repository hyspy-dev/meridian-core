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
import meridian.protocol.packets.player.SetGameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NORMAL-position {@code BOTH}-direction handler feeding {@link InteractionControlImpl}
 * and applying the chain-id NAT ({@link ChainIdNat}).
 *
 * <p><b>Observe.</b> {@code MouseInteraction} carries the looked-at block;
 * {@code SyncInteractionChains} carries the player's own chains — the per-type
 * root ids and replay templates; {@code ClientMovement} carries the movement
 * state {@code ConditionInteraction} branches on; {@code SetGameMode} carries the
 * player's game mode ({@code ConditionInteraction.requiredGameMode}'s input).
 *
 * <p><b>Translate.</b> Every interaction {@code chainId} the player sends is
 * rewritten into the proxy-owned server-side space, and server-to-client
 * traffic is rewritten back. A chain the proxy forged is dropped on its way
 * back to the client — the client never created it.
 */
final class InteractionObserver implements PacketHandler {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

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
            control.onPlayerLook(movement.lookOrientation);
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
            log.info("meridian-core: NAT C2S cancel chain client={} -> server={}",
                    cancel.chainId, translated);
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
        log.info("meridian-core: NAT C2S {} chain client={} -> server={} (initial={})",
                chain.interactionType, chain.chainId, translated, chain.initial);
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
        if (packet instanceof SetGameMode setGameMode) {
            // The player's game mode — ConditionInteraction.requiredGameMode's input.
            control.onGameMode(setGameMode.gameMode);
            return Action.FORWARD;
        }
        if (packet instanceof SyncInteractionChains chains && chains.updates != null) {
            List<SyncInteractionChain> kept = new ArrayList<>(chains.updates.length);
            boolean changed = false;
            for (SyncInteractionChain chain : chains.updates) {
                if (chain == null) {
                    continue;
                }
                if (control.nat().isForged(chain.chainId)) {
                    // The server's echo of a chain the proxy forged — the real
                    // client never created it, so it must not see it. First, if it
                    // carries the server's own area forks for a reactive dig swing,
                    // answer them (the server hands us the HitBlock root here).
                    control.onServerForkAnnounce(chain);
                    log.info("meridian-core: NAT S2C dropped forged chain server={} (initial={})",
                            chain.chainId, chain.initial);
                    changed = true;
                    continue;
                }
                int before = chain.chainId;
                boolean chg = translateS2C(chain);
                log.info("meridian-core: NAT S2C {} chain server={} -> client={} (initial={})",
                        chain.interactionType, before, chain.chainId, chain.initial);
                changed |= chg;
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
                log.info("meridian-core: NAT S2C dropped cancel for forged chain server={}",
                        cancel.chainId);
                return Action.DROP;
            }
            int translated = control.nat().toClient(cancel.chainId);
            log.info("meridian-core: NAT S2C cancel chain server={} -> client={}",
                    cancel.chainId, translated);
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
