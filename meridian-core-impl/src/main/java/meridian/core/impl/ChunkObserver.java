package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.ServerSetBlocks;
import meridian.protocol.packets.world.SetChunk;
import meridian.protocol.packets.world.UnloadChunk;

/**
 * MONITOR-position S2C handler feeding {@link ChunkTracker} the world packets
 * ({@code SetChunk}, {@code ServerSetBlock}, {@code ServerSetBlocks},
 * {@code UnloadChunk}). Observe-only.
 */
final class ChunkObserver implements PacketHandler {
    private final ChunkTracker tracker;

    ChunkObserver(ChunkTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SetChunk setChunk) {
            tracker.onSetChunk(setChunk);
        } else if (packet instanceof ServerSetBlock setBlock) {
            tracker.onServerSetBlock(setBlock);
        } else if (packet instanceof ServerSetBlocks setBlocks) {
            tracker.onServerSetBlocks(setBlocks);
        } else if (packet instanceof UnloadChunk unload) {
            tracker.onUnloadChunk(unload);
        }
        return Action.FORWARD;
    }
}
