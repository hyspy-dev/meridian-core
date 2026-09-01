package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.HashMap;
import java.util.Map;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.assets.UpdateEnvironments;
import meridian.protocol.packets.assets.UpdateFluids;
import meridian.protocol.packets.player.JoinWorld;
import meridian.protocol.packets.world.SetChunk;
import meridian.protocol.packets.world.SetChunkEnvironments;
import meridian.protocol.packets.world.SetChunkHeightmap;
import meridian.protocol.packets.world.SetChunkTintmap;
import meridian.protocol.packets.world.SetFluids;
import meridian.protocol.packets.world.UnloadChunk;

/**
 * Feeds {@link WorldChunksImpl} the traffic that describes the world. Observe-only.
 *
 * <p>Sits at {@code EARLY} rather than {@code MONITOR}, unlike core's other observers: a module may
 * legitimately drop one of these packets on its way to the client - the world downloader drops
 * {@code UnloadChunk} to keep flown-over chunks drawn - and a feed of "what the server said" must
 * not lose an event because somebody downstream chose not to show it.
 */
final class WorldChunksHandler implements PacketHandler {

    private final WorldChunksImpl chunks;

    WorldChunksHandler(WorldChunksImpl chunks) {
        this.chunks = chunks;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SetChunk c) {
            chunks.onSection(c.x, c.y, c.z, c.data, c.localLight, c.globalLight);
        } else if (packet instanceof SetFluids f) {
            chunks.onFluids(f.x, f.y, f.z, f.data);
        } else if (packet instanceof SetChunkHeightmap h) {
            chunks.onHeightmap(h.x, h.z, h.heightmap);
        } else if (packet instanceof SetChunkTintmap t) {
            chunks.onTintmap(t.x, t.z, t.tintmap);
        } else if (packet instanceof SetChunkEnvironments e) {
            chunks.onEnvironments(e.x, e.z, e.environments);
        } else if (packet instanceof UnloadChunk u) {
            chunks.onUnload(u.chunkX, u.chunkZ);
        } else if (packet instanceof JoinWorld j) {
            // The join is the seam itself here. Later builds announce it on the chunk channel with
            // a packet of its own, which is ordered against the chunks and so can be waited for;
            // this one has nothing of the kind, so the world turns over the moment it is named.
            chunks.onJoinWorld(j.worldUuid, false);
        } else if (packet instanceof UpdateFluids u) {
            chunks.onFluidCatalog(named(u));
        } else if (packet instanceof UpdateEnvironments u) {
            chunks.onEnvironmentCatalog(named(u));
        }
        return Action.FORWARD;
    }

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

    private static Map<Integer, String> named(UpdateEnvironments update) {
        Map<Integer, String> out = new HashMap<>();
        if (update.environments != null) {
            update.environments.forEach((id, environment) -> {
                if (environment != null && environment.id != null) {
                    out.put(id, environment.id);
                }
            });
        }
        return out;
    }
}
