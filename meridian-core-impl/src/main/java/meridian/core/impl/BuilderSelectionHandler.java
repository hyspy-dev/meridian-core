package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.List;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.BuilderSelection;
import meridian.core.api.BuilderSelection.Box;
import meridian.protocol.InteractionType;
import meridian.protocol.packets.buildertools.BuilderToolSelectionUpdate;
import meridian.protocol.packets.buildertools.BuilderToolsEnabledTools;
import meridian.protocol.packets.inventory.UpdatePlayerInventory;
import meridian.protocol.packets.interaction.SyncInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChains;

/**
 * The wire side of the borrowed toolbar.
 *
 * <p>Three jobs, all around one idea - the tools we force onto the client are ours to answer, not
 * the server's:
 * <ul>
 *   <li>S2C {@code BuilderToolsEnabledTools}: widen the allowed list to include every forced tool,
 *       so the client shows them in the editor toolbar.</li>
 *   <li>C2S {@code SyncInteractionChains}: an F on a tool arrives as a {@code Use} chain naming the
 *       tool held. If it names one of ours, tell the module and drop that chain - the server never
 *       granted the tool and would only reject it.</li>
 *   <li>C2S {@code BuilderToolSelectionUpdate}: the box a drag of the selection tool reports - read
 *       it and drop it, same reasoning.</li>
 * </ul>
 */
final class BuilderSelectionHandler implements PacketHandler {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("meridian-core");

    private final BuilderSelectionImpl tools;

    BuilderSelectionHandler(BuilderSelectionImpl tools) {
        this.tools = tools;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SyncInteractionChains chains) {
            // The other half of the item NAT: the server answers naming the real item it has in that
            // slot, but the client predicted the tool it is showing. Left alone the answer
            // contradicts the prediction and the client rolls the slot change back.
            if (chains.updates == null || tools.forcedTools().isEmpty()) {
                return Action.FORWARD;
            }
            boolean changed = false;
            for (SyncInteractionChain chain : chains.updates) {
                if (chain != null) {
                    changed |= toClientItems(chain);
                }
            }
            return changed ? Action.MODIFIED : Action.FORWARD;
        }
        if (packet instanceof UpdatePlayerInventory inv && inv.hotbar != null) {
            tools.rememberServerHotbar(inv.hotbar);   // so we can put the real hotbar back later
            return Action.FORWARD;
        }
        if (!(packet instanceof BuilderToolsEnabledTools enabled)) {
            return Action.FORWARD;
        }
        List<String> forced = tools.forcedTools();
        if (forced.isEmpty()) {
            tools.rememberServerEnabled(enabled.toolIds);   // baseline: what the server itself allows
            return Action.FORWARD;
        }
        List<String> widened = new ArrayList<>();
        if (enabled.toolIds != null) {
            for (String id : enabled.toolIds) {
                widened.add(id);
            }
        }
        boolean changed = false;
        for (String id : forced) {
            if (!widened.contains(id)) {
                widened.add(id);
                changed = true;
            }
        }
        if (!changed) {
            return Action.FORWARD;
        }
        enabled.toolIds = widened.toArray(new String[0]);
        return Action.MODIFIED;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SyncInteractionChains chains) {
            return handleChains(chains);
        }
        if (packet instanceof BuilderToolSelectionUpdate u && !tools.forcedTools().isEmpty()) {
            int xMin = Math.min(u.xMin, u.xMax);
            int yMin = Math.min(u.yMin, u.yMax);
            int zMin = Math.min(u.zMin, u.zMax);
            int xMax = Math.max(u.xMin, u.xMax);
            int yMax = Math.max(u.yMin, u.yMax);
            int zMax = Math.max(u.zMin, u.zMax);
            tools.report(new Box(xMin, yMin, zMin, xMax, yMax, zMax));
            return Action.DROP;                 // captured; the server must not see the tool used
        }
        return Action.FORWARD;
    }

    /**
     * Pulls the F-on-our-tool chains out of a sync and answers them; the rest go on to the server.
     */
    private Action handleChains(SyncInteractionChains chains) {
        if (chains.updates == null || chains.updates.length == 0) {
            return Action.FORWARD;
        }
        List<String> forced = tools.forcedTools();
        if (forced.isEmpty()) {
            return Action.FORWARD;
        }
        // Our tools are a fiction laid over the player's real hotbar items, so the two sides
        // disagree about what is in those slots - and every chain carries the item's id. The two
        // ends are therefore translated in both directions, exactly like a NAT:
        //   - C2S the client names our tool; hand the server the real item in that slot, so its
        //     item-in-hand check passes and it runs the swap;
        //   - S2C ({@link #toClientItems}) the server answers naming the real item; give the id back
        //     to the tool the client shows, so the answer matches what it predicted and it does not
        //     roll the slot change back.
        // Only a Primary/Secondary/Use on one of our tools is dropped - that one is ours to answer.
        // We run EARLY, before the interaction NAT, so a dropped chain never burns a server-side id.
        List<SyncInteractionChain> keep = new ArrayList<>();
        boolean changed = false;
        for (SyncInteractionChain chain : chains.updates) {
            // Logged before we touch anything: core's own chain dump runs at NORMAL, after this
            // handler, so what it prints is already our rewrite - only this line shows what the
            // client actually sent.
            LOG.info("meridian-core: tools C2S raw {} chain={} initial={} desync={} slot={} "
                            + "target={} item={} tools={}",
                    chain.interactionType, chain.chainId, chain.initial, chain.desync,
                    chain.activeHotbarSlot, chain.data == null ? "-" : chain.data.targetSlot,
                    chain.itemInHandId, chain.toolsItemId);
            if (chain.desync && isSlotSwap(chain.interactionType)) {
                // A tool whose own swap interaction fails on the client (EditorTool_Selection has
                // one) makes the client undo the slot change by itself and report a desync, even
                // though the server accepted the swap and moved. Two things follow from that:
                // the report must not reach the server (it answers by cancelling the swap it just
                // accepted, and from then on the two disagree about the active slot and every later
                // swap is cancelled too), and the client has to be walked back onto the slot the
                // server is already on - which is what the server's own correction packet does.
                int target = chain.data == null ? Integer.MIN_VALUE : chain.data.targetSlot;
                LOG.info("meridian-core: tools swallowing desync report for chain {}, "
                        + "pushing client to slot {}", chain.chainId, target);
                tools.pushActiveSlot(target);
                changed = true;
                continue;
            }
            String named = ours(forced, chain);
            BuilderSelection.Interaction kind = named == null ? null : kindOf(chain.interactionType);
            if (kind != null) {
                tools.fireTool(named, kind);    // we answer LKM / PKM / F ourselves, and drop it
                changed = true;
                continue;
            }
            changed |= toServerItems(forced, chain);
            keep.add(chain);
        }
        if (!changed) {
            return Action.FORWARD;
        }
        if (keep.isEmpty()) {
            return Action.DROP;                 // the whole sync was ours to answer
        }
        chains.updates = keep.toArray(new SyncInteractionChain[0]);
        return Action.MODIFIED;
    }

    /** A hotbar slot change - the chain type whose follow-up carries the client's desync report. */
    private static boolean isSlotSwap(InteractionType type) {
        return type == InteractionType.SwapFrom || type == InteractionType.SwapTo;
    }

    /** C2S: wherever a chain names one of our tools, name the real item the server has there. */
    private boolean toServerItems(List<String> forced, SyncInteractionChain chain) {
        boolean changed = false;
        if (chain.itemInHandId != null && forced.contains(chain.itemInHandId)) {
            chain.itemInHandId = tools.serverHotbarItem(chain.activeHotbarSlot);
            changed = true;
        }
        if (chain.newForks != null) {
            for (SyncInteractionChain fork : chain.newForks) {
                if (fork != null) {
                    changed |= toServerItems(forced, fork);
                }
            }
        }
        return changed;
    }

    /** S2C: wherever the server names the real item of a slot we spoofed, name our tool instead. */
    private boolean toClientItems(SyncInteractionChain chain) {
        boolean changed = false;
        // Only when the server actually named an item we are hiding. Its answers carry no slot (it
        // leaves activeHotbarSlot at 0) and usually no item at all - stamping a tool onto those
        // would be inventing an item the client never asked about.
        String tool = tools.toolHiding(chain.itemInHandId);
        LOG.info("meridian-core: tools S2C raw {} chain={} initial={} desync={} slot={} item={} "
                        + "-> {}",
                chain.interactionType, chain.chainId, chain.initial, chain.desync,
                chain.activeHotbarSlot, chain.itemInHandId, tool == null ? "(untouched)" : tool);
        if (tool != null) {
            chain.itemInHandId = tool;
            changed = true;
        }
        if (chain.newForks != null) {
            for (SyncInteractionChain fork : chain.newForks) {
                if (fork != null) {
                    changed |= toClientItems(fork);
                }
            }
        }
        return changed;
    }

    /** Which of our forced tools this chain names - the held hotbar item, or the tools slot. */
    private static String ours(List<String> forced, SyncInteractionChain chain) {
        if (chain.itemInHandId != null && forced.contains(chain.itemInHandId)) {
            return chain.itemInHandId;
        }
        if (chain.toolsItemId != null && forced.contains(chain.toolsItemId)) {
            return chain.toolsItemId;
        }
        return null;
    }

    /** The three interactions we own on a tool, or null for anything else (a swap, a fork, ...). */
    private static BuilderSelection.Interaction kindOf(InteractionType type) {
        return switch (type) {
            case Primary -> BuilderSelection.Interaction.PRIMARY;
            case Secondary -> BuilderSelection.Interaction.SECONDARY;
            case Use -> BuilderSelection.Interaction.USE;
            default -> null;
        };
    }
}
