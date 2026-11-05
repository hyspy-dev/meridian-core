package meridian.core.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.ServerSetBlocks;
import meridian.protocol.packets.world.SetBlockCmd;
import meridian.protocol.packets.world.SetChunk;
import meridian.protocol.packets.world.SetFluids;
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
    /**
     * Section coords &rarr; which way each block faces ({@code byte[32768]}, the server's rotation
     * index), only for sections where something is turned - most have nothing, and an absent
     * entry reads as all zero.
     */
    private final Map<SectionKey, byte[]> rotations = new ConcurrentHashMap<>();
    /**
     * The same, for fluids - water and lava live here, not in the block layer.
     *
     * <p>The block array reads air where a fluid is; a player standing "on the surface" of a
     * pond would otherwise be dropped onto its floor. Fluids are a separate voxel layer with a
     * separate palette, so they are tracked separately and answered by {@link #fluidNameAt}.
     */
    private final Map<SectionKey, int[]> fluidSections = new ConcurrentHashMap<>();
    /** Fluid id &rarr; name (Water, Lava, ...), from the fluid catalog. */
    private final Map<Integer, String> fluidNames = new ConcurrentHashMap<>();

    /** Block index within a 32³ section — {@code (y&31)<<10 | (z&31)<<5 | (x&31)}. */
    private static int indexBlock(int x, int y, int z) {
        return (y & 31) << 10 | (z & 31) << 5 | (x & 31);
    }

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    void onSetChunk(SetChunk packet) {
        try {
            SectionKey key = new SectionKey(packet.x, packet.y, packet.z);
            ChunkDecoder.Section section = ChunkDecoder.decodeSection(packet.data);
            sections.put(key, section.ids());
            if (section.rotations() != null) {
                rotations.put(key, section.rotations());
            } else {
                rotations.remove(key);
            }
        } catch (RuntimeException e) {
            log.warn("meridian-core: failed to decode chunk section ({},{},{}): {}",
                    packet.x, packet.y, packet.z, e.toString());
        }
    }

    void onServerSetBlock(ServerSetBlock packet) {
        SectionKey key = new SectionKey(packet.x >> 5, packet.y >> 5, packet.z >> 5);
        int[] section = sections.get(key);
        if (section != null) {
            int index = indexBlock(packet.x, packet.y, packet.z);
            section[index] = packet.blockId;
            setRotation(key, index, packet.rotation);
        }
    }

    void onServerSetBlocks(ServerSetBlocks packet) {
        if (packet.cmds == null) {
            return;
        }
        SectionKey key = new SectionKey(packet.x, packet.y, packet.z);
        int[] section = sections.get(key);
        if (section == null) {
            return;
        }
        for (SetBlockCmd cmd : packet.cmds) {
            int index = cmd.index & 0xFFFF;
            if (index < section.length) {
                section[index] = cmd.blockId;
                setRotation(key, index, cmd.rotation);
            }
        }
    }

    /** Records which way one block now faces, making room only once something is turned. */
    private void setRotation(SectionKey key, int index, byte rotation) {
        byte[] section = rotation == 0 ? rotations.get(key)
                : rotations.computeIfAbsent(key, k -> new byte[ChunkDecoder.SECTION_BLOCKS]);
        if (section != null) {
            section[index] = rotation;
        }
    }

    void onSetFluids(SetFluids packet) {
        try {
            fluidSections.put(new SectionKey(packet.x, packet.y, packet.z),
                    ChunkDecoder.decodeFluidIds(packet.data));
        } catch (RuntimeException e) {
            log.warn("meridian-core: failed to decode fluid section ({},{},{}): {}",
                    packet.x, packet.y, packet.z, e.toString());
        }
    }

    /** Fluid id &rarr; name, as this connection numbered them. Merged; the catalog only grows. */
    void onFluidCatalog(Map<Integer, String> named) {
        fluidNames.putAll(named);
    }

    void onUnloadChunk(UnloadChunk packet) {
        unloadColumn(packet.chunkX, packet.chunkZ);
    }

    /** Everything standing over one column of the world, at every height. */
    private void unloadColumn(int chunkX, int chunkZ) {
        sections.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        rotations.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        fluidSections.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
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

    /**
     * Which way the block at a world position faces, as the server's rotation index (0..63), or
     * {@code 0} when it is not turned or its section is not loaded.
     */
    public int rotationAt(int x, int y, int z) {
        byte[] section = rotations.get(new SectionKey(x >> 5, y >> 5, z >> 5));
        return section == null ? 0 : section[indexBlock(x, y, z)] & 0xFF;
    }

    /**
     * The name of the fluid at a world position, or {@code null} when there is none.
     *
     * <p>A non-null answer means water or lava is there - enough to lift a landing clear of it,
     * and the name tells the two apart where that matters (a chain will not hop into lava). An id
     * with no name yet still counts as fluid, reported as {@code "Fluid"}.
     */
    public String fluidNameAt(int x, int y, int z) {
        int[] section = fluidSections.get(new SectionKey(x >> 5, y >> 5, z >> 5));
        if (section == null) {
            return null;
        }
        int id = section[indexBlock(x, y, z)];
        if (id <= 0) {
            return null;
        }
        return fluidNames.getOrDefault(id, "Fluid");
    }

    /** Number of sections currently mirrored. */
    public int sectionCount() {
        return sections.size();
    }
}
