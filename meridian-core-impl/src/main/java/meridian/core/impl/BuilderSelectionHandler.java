package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.protocol.packets.interface_.ChatMessage;
import meridian.protocol.InteractionState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.BuilderSelection;
import meridian.core.api.BuilderSelection.Box;
import meridian.protocol.InteractionType;
import meridian.protocol.packets.buildertools.BuilderToolAction;
import meridian.protocol.packets.buildertools.BuilderToolGeneralAction;
import meridian.protocol.packets.buildertools.BuilderToolPasteClipboard;
import meridian.protocol.packets.buildertools.BuilderToolRandomizeClipboard;
import meridian.protocol.packets.buildertools.BuilderToolResetClipboardRotation;
import meridian.protocol.packets.buildertools.BuilderToolRotateClipboard;
import meridian.protocol.packets.buildertools.BuilderToolSelectionToolAskForClipboard;
import meridian.protocol.packets.buildertools.BuilderToolSelectionUpdate;
import meridian.protocol.packets.buildertools.BuilderToolsEnabledTools;
import meridian.protocol.packets.interface_.EditorBlocksChange;
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
    /**
     * Slot changes onto our tools that we answered instead of the server: chain id to the slot it
     * went to. Their follow-ups - the client's own completion or desync report - belong to us too.
     */
    private final Map<Integer, Integer> answeredSwaps = new LinkedHashMap<>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
            return size() > 64;
        }
    };

    /**
     * A new chain of the same kind this soon after the last one let go is the button still held -
     * the client restarts a finished chain while it is down (the builder tool's own cooldown is
     * 50 ms) - not a second click.
     */
    private static final long REPEAT_GAP_MS = 100;

    /** Per kind of input on our tools: the chain of the press in progress, and when it last spoke. */
    private static final class Press {
        int chainId;
        boolean released;
        long lastSeen;
    }

    private final Map<InteractionType, Press> presses = new EnumMap<>(InteractionType.class);
    /** Chains on our tools we took, by id: their follow-ups are ours too, and say when it let go. */
    private final Map<Integer, InteractionType> takenChains = new LinkedHashMap<>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, InteractionType> eldest) {
            return size() > 64;
        }
    };

    BuilderSelectionHandler(BuilderSelectionImpl tools) {
        this.tools = tools;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof EditorBlocksChange change && tools.ownsClipboard()
                && (change.blocksChange != null || change.blocksOmitted || change.shapeOccupancy != null)) {
            // The server's own clipboard, which would replace ours in the paste tool. A change that
            // only moves the selection box carries no blocks and still goes through.
            return Action.DROP;
        }
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
            if (u.xMin == Integer.MIN_VALUE || u.xMax == Integer.MIN_VALUE) {
                // No box: the client sends this as the selection tool is put away. Not a selection.
                return Action.DROP;
            }
            int xMin = Math.min(u.xMin, u.xMax);
            int yMin = Math.min(u.yMin, u.yMax);
            int zMin = Math.min(u.zMin, u.zMax);
            int xMax = Math.max(u.xMin, u.xMax);
            int yMax = Math.max(u.yMin, u.yMax);
            int zMax = Math.max(u.zMin, u.zMax);
            tools.report(new Box(xMin, yMin, zMin, xMax, yMax, zMax));
            return Action.DROP;                 // captured; the server must not see the tool used
        }
        // Copying the selection fills the paste tool - ours to do while our tools are out, whatever
        // the paste tool holds now: the copy key comes as a general builder action, a typed /copy
        // as chat.
        if (!tools.forcedTools().isEmpty()
                && ((packet instanceof BuilderToolGeneralAction g && g.action == BuilderToolAction.SelectionCopy)
                    || (packet instanceof ChatMessage c && c.message != null && c.message.trim().equalsIgnoreCase("/copy")))) {
            LOG.info("meridian-core: tools taking the selection copy");
            tools.firePaste(BuilderSelection.PasteListener::copyAsked);
            return Action.DROP;
        }
        if (tools.ownsClipboard()) {
            // The paste tool is holding our clipboard: the server's would be the wrong blocks, and a
            // paste must not reach it - the module places a projection, not a building.
            if (packet instanceof BuilderToolSelectionToolAskForClipboard) {
                tools.replyWithClipboard();
                return Action.DROP;
            }
            if (packet instanceof BuilderToolRotateClipboard r) {
                BuilderSelection.Axis axis = switch (r.axis) {
                    case X -> BuilderSelection.Axis.X;
                    case Y -> BuilderSelection.Axis.Y;
                    case Z -> BuilderSelection.Axis.Z;
                };
                tools.turn(axis, r.angle);
                tools.firePaste(l -> l.turned(axis, r.angle));
                return Action.DROP;
            }
            if (packet instanceof BuilderToolResetClipboardRotation) {
                tools.resetTurns();
                tools.firePaste(BuilderSelection.PasteListener::reset);
                return Action.DROP;
            }
            if (packet instanceof BuilderToolPasteClipboard p) {
                tools.firePaste(l -> l.pasted(p.x, p.y, p.z));
                return Action.DROP;
            }
            if (packet instanceof BuilderToolRandomizeClipboard) {
                return Action.DROP;             // nothing of ours to scatter; the server's would be wrong
            }
            // The paste tool's flip key, which the client sends as a command the server keeps to its
            // editors - and would run on its own clipboard anyway.
            if (packet instanceof ChatMessage chat && chat.message != null) {
                String[] words = chat.message.trim().split("\\s+");
                if (words[0].equalsIgnoreCase("/flip") && words.length <= 2) {
                    BuilderSelection.Axis axis = tools.flipAxis(words.length == 2 ? words[1] : null);
                    if (axis != null) {
                        tools.firePaste(l -> l.flipped(axis));
                        return Action.DROP;
                    }
                }
            }
        }
        if (packet instanceof ChatMessage cmd && cmd.message != null && cmd.message.startsWith("/")
                && !tools.forcedTools().isEmpty()) {
            LOG.info("meridian-core: tools see command '{}' (forced={}, clipboard={})",
                    cmd.message, tools.forcedTools(), tools.ownsClipboard());
        }
        // The prefab list is how a clipboard gets chosen in the first place, so it is ours whenever
        // the paste tool is one we handed out - with or without a clipboard in it yet.
        if (packet instanceof ChatMessage chat && chat.message != null
                && tools.forcedTools().contains(BuilderSelection.PASTE_TOOL)) {
            String[] words = chat.message.trim().split("\\s+");
            if (words.length == 2 && words[0].equalsIgnoreCase("/prefab") && words[1].equalsIgnoreCase("list")) {
                tools.firePaste(BuilderSelection.PasteListener::listAsked);
                return Action.DROP;
            }
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
        // Slot changes are kept from the server altogether while they touch our tools: the server
        // would really move onto the item it has in that slot, start that item's own swap-in (a
        // sword's draw, a bow's), and the answer would not match the tool the client predicted - so
        // the client rolls the change back. A change onto a tool is answered here and the server
        // stays where it was; a change off a tool reaches it as if made from that real slot.
        // Only a Primary/Secondary/Use on one of our tools is dropped - that one is ours to answer.
        // We run EARLY, before the interaction NAT, so a dropped chain never burns a server-side id.
        List<SyncInteractionChain> keep = new ArrayList<>();
        boolean changed = false;
        for (SyncInteractionChain chain : chains.updates) {
            // Logged before we touch anything: core's own chain dump runs at NORMAL, after this
            // handler, so what it prints is already our rewrite - only this line shows what the
            // client actually sent.
            LOG.info("meridian-core: tools C2S raw {} chain={} initial={} desync={} state={} slot={} "
                            + "target={} item={} tools={}",
                    chain.interactionType, chain.chainId, chain.initial, chain.desync, chain.state,
                    chain.activeHotbarSlot, chain.data == null ? "-" : chain.data.targetSlot,
                    chain.itemInHandId, chain.toolsItemId);
            Integer answeredTarget;
            synchronized (answeredSwaps) {
                answeredTarget = chain.initial ? null : answeredSwaps.get(chain.chainId);
            }
            if (answeredTarget != null) {
                // A follow-up to a change we answered. A desync means the client let go of it
                // anyway; put it back - the server is not involved either way.
                if (chain.desync) {
                    tools.pushActiveSlot(answeredTarget);
                }
                changed = true;
                continue;
            }
            if (isSlotSwap(chain.interactionType) && chain.initial && chain.data != null
                    && tools.toolAt(chain.data.targetSlot) != null) {
                // Onto one of our tools: the server never hears of it.
                LOG.info("meridian-core: tools answering swap chain {} onto tool slot {} "
                        + "(server stays on {})", chain.chainId, chain.data.targetSlot, tools.serverSlot());
                synchronized (answeredSwaps) {
                    answeredSwaps.put(chain.chainId, chain.data.targetSlot);
                }
                tools.answerSwap(chain);
                changed = true;
                continue;
            }
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
            InteractionType takenType;
            synchronized (presses) {
                takenType = chain.initial ? null : takenChains.get(chain.chainId);
            }
            if (takenType != null) {
                // The rest of a press we took: never the server's (it would cancel a chain it never
                // saw start). Finished, failed or given up on means the button is up.
                synchronized (presses) {
                    Press press = presses.get(takenType);
                    if (press != null && press.chainId == chain.chainId) {
                        press.lastSeen = System.currentTimeMillis();
                        if (chain.desync || chain.state != InteractionState.NotFinished) {
                            press.released = true;
                        }
                    }
                }
                changed = true;
                continue;
            }
            String named = ours(forced, chain);
            BuilderSelection.Interaction kind = named == null ? null : kindOf(chain.interactionType);
            if (kind != null) {
                // We answer LKM / PKM / F ourselves, and drop it - once per press: a held button
                // keeps sending chains, and one click is one step.
                if (press(chain)) {
                    tools.fireTool(named, kind);
                }
                changed = true;
                continue;
            }
            if (tools.toolAt(chain.activeHotbarSlot) != null) {
                // Sent while holding a tool - a change off it, or anything else: the server has
                // never left its own slot, so the chain has to say that slot and its real item.
                tools.fromServerSlot(chain);
                changed = true;
            } else {
                changed |= toServerItems(forced, chain);
            }
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

    /**
     * Records the start of a chain on one of our tools.
     *
     * @return whether it is a new press - the button was up - rather than the same one still held
     */
    private boolean press(SyncInteractionChain chain) {
        long now = System.currentTimeMillis();
        synchronized (presses) {
            takenChains.put(chain.chainId, chain.interactionType);
            Press press = presses.computeIfAbsent(chain.interactionType, t -> new Press());
            boolean held = press.lastSeen != 0
                    && (!press.released || now - press.lastSeen < REPEAT_GAP_MS);
            LOG.info("meridian-core: tools {} chain {} state={} - {} (last {} ms ago, released={})",
                    chain.interactionType, chain.chainId, chain.state, held ? "held, no step" : "press",
                    press.lastSeen == 0 ? -1 : now - press.lastSeen, press.released);
            press.chainId = chain.chainId;
            press.released = chain.state != InteractionState.NotFinished;
            press.lastSeen = now;
            return !held;
        }
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
