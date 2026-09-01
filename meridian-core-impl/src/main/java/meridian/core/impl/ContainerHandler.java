package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.HashMap;
import java.util.Map;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.Containers;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InventorySection;
import meridian.protocol.ItemWithAllMetadata;
import meridian.protocol.packets.interaction.SyncInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChains;
import meridian.protocol.packets.window.CloseWindow;
import meridian.protocol.packets.window.OpenWindow;
import meridian.protocol.packets.window.UpdateWindow;
import meridian.protocol.packets.window.WindowType;

/**
 * Feeds {@link ContainersImpl} what the player sees inside containers. Observe-only.
 *
 * <p>Both directions: the contents come from the server, but which block they belong to comes from
 * the player's own reach, which the server never repeats back. The two are matched by order - a
 * block's window is opened in answer to an interaction and nothing else.
 *
 * <p>Which block that is arrives in the interaction the player sends: a chest is opened by running
 * an interaction chain ({@code Open_Container}), and the chain carries the block it is being run
 * against. The mouse packet carries a block too, but not the one that matters - before a chest
 * opens it names whatever the cursor last crossed, which is how a chest's contents ended up filed
 * under someone else's ground. Only the chain is believed.
 *
 * <p>Every window that belongs to a block counts, not only the plain chest: a furnace, a bench and
 * a chest all hold items in the world, and a server is free to open any of them for a block. The
 * two windows a player opens out of thin air - their pocket crafting and their memories - belong
 * to nowhere, and are the only ones left alone.
 */
final class ContainerHandler implements PacketHandler {

    private final ContainersImpl containers;

    ContainerHandler(ContainersImpl containers) {
        this.containers = containers;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SyncInteractionChains chains && chains.updates != null) {
            for (SyncInteractionChain chain : chains.updates) {
                reachedFor(chain, 0);
            }
        }
        return Action.FORWARD;
    }

    /**
     * The block a chain is being run against, taken from the last part of it that names one.
     *
     * <p>A chain says what it is doing to what: the chain as a whole carries the block, and so
     * does each operation in it - the one that opens a container names it again. The later the
     * mention, the closer to the thing that just happened, so the last wins.
     *
     * @param depth guards against a chain that forks into itself; forks nest a step at a time
     */
    private void reachedFor(SyncInteractionChain chain, int depth) {
        if (chain == null || depth > 4) {
            return;
        }
        if (chain.data != null && chain.data.blockPosition != null) {
            var block = chain.data.blockPosition;
            containers.onTargetBlock(block.x, block.y, block.z);
        }
        if (chain.interactionData != null) {
            for (InteractionSyncData op : chain.interactionData) {
                if (op != null && op.blockPosition != null) {
                    containers.onTargetBlock(op.blockPosition.x, op.blockPosition.y,
                            op.blockPosition.z);
                }
            }
        }
        if (chain.newForks != null) {
            for (SyncInteractionChain fork : chain.newForks) {
                reachedFor(fork, depth + 1);
            }
        }
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof OpenWindow open) {
            // The window is bound to the block even when it arrives empty: some servers send the
            // frame first and the contents in an update a moment later, and without the binding
            // that update would have nowhere to go.
            containers.onOpened(open.id, ofABlock(open.windowType), open.windowType.name(),
                    open.inventory == null ? null : contents(open.inventory));
        } else if (packet instanceof UpdateWindow update) {
            if (update.inventory != null) {
                containers.onUpdated(update.id, contents(update.inventory));
            }
        } else if (packet instanceof CloseWindow close) {
            containers.onClosed(close.id);
        }
        return Action.FORWARD;
    }

    /**
     * Whether a window of this type stands in the world.
     *
     * <p>Pocket crafting and memories are the two a player opens on their own, with no block
     * involved; everything else the server opens because they reached for something.
     */
    private static boolean ofABlock(WindowType type) {
        return type != WindowType.PocketCrafting && type != WindowType.Memories;
    }

    /** The wire's stacks, as the plain values a module reads. */
    private static Containers.Contents contents(InventorySection section) {
        Map<Integer, Containers.Item> items = new HashMap<>();
        if (section.items != null) {
            section.items.forEach((slot, stack) -> {
                if (stack != null && stack.itemId != null && !stack.itemId.isEmpty()) {
                    items.put(slot, item(stack));
                }
            });
        }
        return ContainersImpl.contents(section.capacity, items);
    }

    private static Containers.Item item(ItemWithAllMetadata stack) {
        // Quality arrived with a later build; here every stack is the plain one.
        return new Containers.Item(stack.itemId, stack.quantity, stack.durability,
                stack.maxDurability, 0, stack.metadata);
    }
}
