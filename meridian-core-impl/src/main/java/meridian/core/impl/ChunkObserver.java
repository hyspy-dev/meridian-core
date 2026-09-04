package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.HashMap;
import java.util.Map;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.ServerSetBlocks;
import meridian.protocol.packets.assets.UpdateFluids;
import meridian.protocol.packets.world.SetChunk;
import meridian.protocol.packets.world.SetFluids;
import meridian.protocol.packets.world.UnloadChunk;

/**
 * MONITOR-position S2C handler feeding {@link ChunkTracker} the world packets
 * ({@code SetChunk}, {@code ServerSetBlock}, {@code ServerSetBlocks},
 * {@code UnloadChunk}), and the fluid layer ({@code SetFluids} + the {@code UpdateFluids}
 * catalog) so water and lava can be told apart from air. Observe-only.
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
        } else if (packet instanceof SetFluids fluids) {
            tracker.onSetFluids(fluids);
        } else if (packet instanceof UpdateFluids catalog) {
            tracker.onFluidCatalog(named(catalog));
        } else if (packet instanceof UnloadChunk unload) {
            tracker.onUnloadChunk(unload);
        }
        return Action.FORWARD;
    }

    /** The fluid catalog packet as an id -> name map, dropping any entry that carries neither. */
    private static Map<Integer, String> named(UpdateFluids update) {
        Map<Integer, String> out = new HashMap<>();
        if (update.fluids != null) {
            update.fluids.forEach((id, fluid) -> {
                if (fluid != null && fluid.id != null) {
                    out.put(id, fluid.id);
                }
            });
        }
        return out;
    }
}
