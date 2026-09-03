package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.HashMap;
import java.util.Map;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.BlockType;
import meridian.protocol.packets.assets.UpdateBlockTypes;

/**
 * NORMAL-position handler that writes the client's view of the block types into the
 * server's own {@code UpdateBlockTypes} as it passes.
 *
 * <p>The coalesced emit in {@link WorldStateImpl} covers a change made while the player is
 * in the world. It cannot cover a texture: the client binds a type to its textures once,
 * when the world loads, from the packet the server sends then, and a later update of the
 * type leaves that binding alone. A rule that changes what a type is drawn with therefore
 * has to be in the first packet - which is what this does. Runs after
 * {@link BlockTypeObserver} (EARLY) has taken the untouched types as server truth.
 */
final class BlockTypeRewriter implements PacketHandler {
    private final WorldStateImpl worldState;

    BlockTypeRewriter(WorldStateImpl worldState) {
        this.worldState = worldState;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (!(packet instanceof UpdateBlockTypes update) || update.blockTypes == null) {
            return Action.FORWARD;
        }
        Map<Integer, BlockType> view = new HashMap<>(update.blockTypes.size());
        boolean changed = false;
        for (Map.Entry<Integer, BlockType> e : update.blockTypes.entrySet()) {
            BlockType truth = e.getValue();
            BlockType seen = truth == null ? null : worldState.clientView(e.getKey(), truth);
            if (seen != truth) {
                changed = true;
            }
            view.put(e.getKey(), seen);
        }
        if (!changed) {
            return Action.FORWARD;
        }
        update.blockTypes = view;   // the originals stay as they were - truth keeps them
        return Action.MODIFIED;
    }
}
