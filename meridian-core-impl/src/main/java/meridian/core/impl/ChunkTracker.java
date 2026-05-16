package meridian.core.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.ServerSetBlocks;
import meridian.protocol.packets.world.SetBlockCmd;
import meridian.protocol.packets.world.SetChunk;
import meridian.protocol.packets.world.UnloadChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live block-id mirror of the loaded world, inside the proxy.
 *
 * <p>Built from observed S2C {@code SetChunk} (full 32³ sections),
 * {@code ServerSetBlock} / {@code ServerSetBlocks} (single / batch edits) and
 * {@code UnloadChunk}. Answers {@link #blockIdAt} for any world position —
 * the world-state input the interaction VM and the farmer need.
 *
 * <p>Coordinate conventions (verified against the server):
 * <ul>
 *   <li>{@code SetChunk.x/y/z} and {@code ServerSetBlocks.x/y/z} are
 *       <em>section</em> coordinates (block &gt;&gt; 5).</li>
 *   <li>{@code ServerSetBlock.x/y/z} are absolute block coordinates.</li>
 *   <li>{@code SetBlockCmd.index} is the in-section block index.</li>
 *   <li>{@code UnloadChunk.chunkX/chunkZ} unload a whole vertical column.</li>
 * </ul>
 */
public final class ChunkTracker {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** Section coordinate triple. */
    private record SectionKey(int x, int y, int z) {}

    /** Section coords &rarr; {@code int[32768]} block ids in {@code indexBlock} order. */
    private final Map<SectionKey, int[]> sections = new ConcurrentHashMap<>();

    /** Block index within a 32³ section — {@code (y&31)<<10 | (z&31)<<5 | (x&31)}. */
    private static int indexBlock(int x, int y, int z) {
        return (y & 31) << 10 | (z & 31) << 5 | (x & 31);
    }

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    void onSetChunk(SetChunk packet) {
        try {
            sections.put(new SectionKey(packet.x, packet.y, packet.z),
                    ChunkDecoder.decodeBlockIds(packet.data));
        } catch (RuntimeException e) {
            log.warn("meridian-core: failed to decode chunk section ({},{},{}): {}",
                    packet.x, packet.y, packet.z, e.toString());
        }
    }

    void onServerSetBlock(ServerSetBlock packet) {
        int[] section = sections.get(new SectionKey(packet.x >> 5, packet.y >> 5, packet.z >> 5));
        if (section != null) {
            section[indexBlock(packet.x, packet.y, packet.z)] = packet.blockId;
        }
    }

    void onServerSetBlocks(ServerSetBlocks packet) {
        if (packet.cmds == null) {
            return;
        }
        int[] section = sections.get(new SectionKey(packet.x, packet.y, packet.z));
        if (section == null) {
            return;
        }
        for (SetBlockCmd cmd : packet.cmds) {
            int index = cmd.index & 0xFFFF;
            if (index < section.length) {
                section[index] = cmd.blockId;
            }
        }
    }

    void onUnloadChunk(UnloadChunk packet) {
        sections.keySet().removeIf(k -> k.x() == packet.chunkX && k.z() == packet.chunkZ);
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /**
     * Block id at world position {@code (x,y,z)}, or {@code -1} if the section
     * is not loaded. {@code 0} is air.
     */
    public int blockIdAt(int x, int y, int z) {
        int[] section = sections.get(new SectionKey(x >> 5, y >> 5, z >> 5));
        return section == null ? -1 : section[indexBlock(x, y, z)];
    }

    /** Number of sections currently mirrored. */
    public int sectionCount() {
        return sections.size();
    }
}
