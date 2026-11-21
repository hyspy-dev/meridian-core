package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.ServerSetBlocks;
import meridian.protocol.packets.assets.UpdateFluids;
import meridian.protocol.packets.world.ClearChunks;
import meridian.protocol.packets.world.SetAbsentChunks;
import meridian.protocol.packets.world.SetChunk;
import meridian.protocol.packets.world.SetFluids;
import meridian.protocol.packets.world.UnloadChunk;
import meridian.protocol.packets.world.UnloadChunks;

/**
 * S2C handler (registered EARLY) feeding {@link ChunkTracker} the world packets ({@code SetChunk},
 * {@code SetAbsentChunks}, {@code ServerSetBlock}, {@code ServerSetBlocks}, {@code UnloadChunk(s)},
 * {@code ClearChunks}), and the fluid layer ({@code SetFluids}, its edits {@code ServerSetFluid} /
 * {@code ServerSetFluids}), and core's one {@link FluidCatalog} the {@code UpdateFluids} that
 * names them, so water and lava can be told apart from air. Observe-only.
 */
final class ChunkObserver implements PacketHandler {
    private final ChunkTracker tracker;
    private final FluidCatalog fluidCatalog;

    ChunkObserver(ChunkTracker tracker, FluidCatalog fluidCatalog) {
        this.tracker = tracker;
        this.fluidCatalog = fluidCatalog;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SetChunk setChunk) {
            tracker.onSetChunk(setChunk);
        } else if (packet instanceof meridian.protocol.packets.world.SetColumn column) {
            tracker.onColumnHeights(column.x, column.z, column.heightmap);
        } else if (packet instanceof meridian.protocol.packets.world.SetChunkHeightmap map) {
            tracker.onColumnHeights(map.x, map.z, map.heightmap);
        } else if (packet instanceof meridian.protocol.packets.world.ServerSetHeight height) {
            tracker.onColumnHeight(height.x, height.z, height.height);
        } else if (packet instanceof meridian.protocol.packets.world.ServerSetHeights heights) {
            if (heights.heights != null) {
                for (var height : heights.heights) {
                    int index = height.index & 0x3FF;
                    tracker.onColumnHeight((heights.x << 5) + (index & 31),
                            (heights.z << 5) + (index >> 5), height.height);
                }
            }
        } else if (packet instanceof SetAbsentChunks absent) {
            tracker.onSetAbsentChunks(absent);
        } else if (packet instanceof ClearChunks) {
            tracker.onClearChunks();
        } else if (packet instanceof ServerSetBlock setBlock) {
            tracker.onServerSetBlock(setBlock);
        } else if (packet instanceof ServerSetBlocks setBlocks) {
            tracker.onServerSetBlocks(setBlocks);
        } else if (packet instanceof SetFluids fluids) {
            tracker.onSetFluids(fluids);
        } else if (packet instanceof meridian.protocol.packets.world.ServerSetFluid fluid) {
            tracker.onServerSetFluid(fluid);
        } else if (packet instanceof meridian.protocol.packets.world.ServerSetFluids fluids) {
            tracker.onServerSetFluids(fluids);
        } else if (packet instanceof UpdateFluids catalog) {
            fluidCatalog.update(catalog);
        } else if (packet instanceof UnloadChunk unload) {
            tracker.onUnloadChunk(unload);
        } else if (packet instanceof UnloadChunks unload) {
            tracker.onUnloadChunks(unload);
        }
        return Action.FORWARD;
    }
}
