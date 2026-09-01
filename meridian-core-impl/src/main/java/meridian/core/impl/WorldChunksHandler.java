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
import meridian.protocol.packets.world.ClearChunks;
import meridian.protocol.packets.world.SetChunk;
import meridian.protocol.packets.world.SetChunkEnvironments;
import meridian.protocol.packets.world.SetChunkHeightmap;
import meridian.protocol.packets.world.SetChunkTintmap;
import meridian.protocol.packets.world.SetColumn;
import meridian.protocol.packets.world.SetFluids;
import meridian.protocol.packets.world.UnloadChunk;
import meridian.protocol.packets.world.UnloadChunks;

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
        } else if (packet instanceof SetColumn c) {
            // A column arriving for the first time brings its three maps together in one packet.
            // The single-map packets below are only sent afterwards, when one of them changes -
            // so a downloader that watched only those would save a world with no tints and no
            // biomes, which is a world of black grass.
            chunks.onHeightmap(c.x, c.z, c.heightmap);
            chunks.onTintmap(c.x, c.z, c.tintmap);
            chunks.onEnvironments(c.x, c.z, c.environments);
        } else if (packet instanceof SetChunkHeightmap h) {
            chunks.onHeightmap(h.x, h.z, h.heightmap);
        } else if (packet instanceof SetChunkTintmap t) {
            chunks.onTintmap(t.x, t.z, t.tintmap);
        } else if (packet instanceof SetChunkEnvironments e) {
            chunks.onEnvironments(e.x, e.z, e.environments);
        } else if (packet instanceof UnloadChunk u) {
            chunks.onUnload(u.chunkX, u.chunkZ);
        } else if (packet instanceof UnloadChunks u) {
            // Columns come as a flat run of (x, z) pairs; the sections array unloads parts of a
            // column, which leaves the column itself standing and is not a column event.
            if (u.columns != null) {
                for (int i = 0; i + 1 < u.columns.length; i += 2) {
                    chunks.onUnload(u.columns[i], u.columns[i + 1]);
                }
            }
        } else if (packet instanceof ClearChunks) {
            // The seam between two worlds, and it rides the chunk channel on purpose: the server
            // sends it ahead of the new world's chunks, so it is ordered against them.
            chunks.onChunksCleared();
        } else if (packet instanceof JoinWorld j) {
            chunks.onJoinWorld(j.worldUuid, j.clearWorld);
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
