package meridian.core.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.ServerSetBlocks;
import meridian.protocol.packets.world.ServerSetFluid;
import meridian.protocol.packets.world.ServerSetFluids;
import meridian.protocol.packets.world.SetBlockCmd;
import meridian.protocol.packets.world.SetChunk;
import meridian.protocol.packets.world.SetFluidCmd;
import meridian.protocol.packets.world.SetFluids;
import meridian.protocol.packets.world.UnloadChunk;
import meridian.protocol.packets.world.UnloadChunks;
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
     * Section coords &rarr; per block, the offset back to the cell a bigger block stands on
     * ({@code int[32768]}, see {@code Block.filler()}), only for sections where some block reaches
     * past its own cell; an absent entry reads as all zero.
     */
    private final Map<SectionKey, int[]> fillers = new ConcurrentHashMap<>();
    /**
     * The same, for fluids - water and lava live here, not in the block layer.
     *
     * <p>The block array reads air where a fluid is; a player standing "on the surface" of a
     * pond would otherwise be dropped onto its floor. Fluids are a separate voxel layer with a
     * separate palette, so they are tracked separately and answered by {@link #fluidNameAt}.
     */
    private final Map<SectionKey, int[]> fluidSections = new ConcurrentHashMap<>();
    /**
     * Section coords &rarr; fluid levels, packed a nibble a cell as the server sends them (see
     * {@link ChunkDecoder.Fluids}), only for sections where some level is not nought.
     */
    private final Map<SectionKey, byte[]> fluidLevels = new ConcurrentHashMap<>();
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
            if (section.fillers() != null) {
                fillers.put(key, section.fillers());
            } else {
                fillers.remove(key);
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
            setFiller(key, index, packet.filler);
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
                setFiller(key, index, cmd.filler);
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

    /** Records one cell's filler offset, making room only once some block reaches past its cell. */
    private void setFiller(SectionKey key, int index, short filler) {
        int value = filler & 0xFFFF;
        int[] section = value == 0 ? fillers.get(key)
                : fillers.computeIfAbsent(key, k -> new int[ChunkDecoder.SECTION_BLOCKS]);
        if (section != null) {
            section[index] = value;
        }
    }

    void onSetFluids(SetFluids packet) {
        try {
            SectionKey key = new SectionKey(packet.x, packet.y, packet.z);
            ChunkDecoder.Fluids fluids = ChunkDecoder.decodeFluids(packet.data);
            fluidSections.put(key, fluids.ids());
            if (fluids.levels() != null) {
                fluidLevels.put(key, fluids.levels());
            } else {
                fluidLevels.remove(key);
            }
        } catch (RuntimeException e) {
            log.warn("meridian-core: failed to decode fluid section ({},{},{}): {}",
                    packet.x, packet.y, packet.z, e.toString());
        }
    }

    /** One fluid cell changed; {@code ServerSetFluid.x/y/z} are block coordinates. */
    void onServerSetFluid(ServerSetFluid packet) {
        setFluid(new SectionKey(packet.x >> 5, packet.y >> 5, packet.z >> 5),
                indexBlock(packet.x, packet.y, packet.z), packet.fluidId, packet.fluidLevel);
    }

    /** Several fluid cells of one section changed; its x/y/z are section coordinates. */
    void onServerSetFluids(ServerSetFluids packet) {
        if (packet.cmds == null) {
            return;
        }
        SectionKey key = new SectionKey(packet.x, packet.y, packet.z);
        for (SetFluidCmd cmd : packet.cmds) {
            int index = cmd.index & 0xFFFF;
            if (index < ChunkDecoder.SECTION_BLOCKS) {
                setFluid(key, index, cmd.fluidId, cmd.fluidLevel);
            }
        }
    }

    /**
     * Records one fluid cell. A section whose blocks are loaded but whose fluids never came is a
     * dry one and gets its fluid layer here; one not loaded at all is left alone, as unknown.
     */
    private void setFluid(SectionKey key, int index, int fluidId, byte level) {
        int[] ids = fluidSections.get(key);
        if (ids == null) {
            if (!sections.containsKey(key)) {
                return;
            }
            ids = fluidSections.computeIfAbsent(key, k -> new int[ChunkDecoder.SECTION_BLOCKS]);
        }
        ids[index] = fluidId;
        int value = level & 0x0F;
        byte[] levels = value == 0 ? fluidLevels.get(key)
                : fluidLevels.computeIfAbsent(key, k -> new byte[ChunkDecoder.LEVEL_BYTES]);
        if (levels != null) {
            ChunkDecoder.setLevel(levels, index, value);
        }
    }

    /** Fluid id &rarr; name, as this connection numbered them. Merged; the catalog only grows. */
    void onFluidCatalog(Map<Integer, String> named) {
        fluidNames.putAll(named);
    }

    void onUnloadChunk(UnloadChunk packet) {
        unloadColumn(packet.chunkX, packet.chunkZ);
    }

    /**
     * The batched unload - the one this build actually sends.
     *
     * <p>A move of any size unloads more than one thing, so the server gathers them: flat triples
     * of section coordinates in {@code sections}, flat pairs of column coordinates in
     * {@code columns}, one array or the other per packet. The single {@link UnloadChunk} above
     * still arrives for odd cases and is still answered, but a session that only listens for it
     * hears almost nothing - which is what left this mirror holding every section the server ever
     * sent, at 128 KB apiece, until the heap was gone.
     */
    void onUnloadChunks(UnloadChunks packet) {
        int[] sectionCoords = packet.sections;
        if (sectionCoords != null) {
            for (int i = 0; i + 2 < sectionCoords.length; i += 3) {
                SectionKey key = new SectionKey(sectionCoords[i], sectionCoords[i + 1],
                        sectionCoords[i + 2]);
                sections.remove(key);
                rotations.remove(key);
                fillers.remove(key);
                fluidSections.remove(key);
                fluidLevels.remove(key);
            }
        }
        int[] columnCoords = packet.columns;
        if (columnCoords != null) {
            for (int i = 0; i + 1 < columnCoords.length; i += 2) {
                unloadColumn(columnCoords[i], columnCoords[i + 1]);
            }
        }
    }

    /** Everything standing over one column of the world, at every height. */
    private void unloadColumn(int chunkX, int chunkZ) {
        sections.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        rotations.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        fillers.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        fluidSections.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        fluidLevels.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
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
     * The filler offset of the cell at a world position (see {@code Block.filler()}), or {@code 0}
     * when it is a block's own cell or its section is not loaded.
     */
    public int fillerAt(int x, int y, int z) {
        int[] section = fillers.get(new SectionKey(x >> 5, y >> 5, z >> 5));
        return section == null ? 0 : section[indexBlock(x, y, z)];
    }

    /**
     * The fluid at a world position with its id and level, or {@code null} when there is none or
     * its section's fluids have not arrived.
     */
    public meridian.core.api.FluidCell fluidCellAt(int x, int y, int z) {
        SectionKey key = new SectionKey(x >> 5, y >> 5, z >> 5);
        int[] section = fluidSections.get(key);
        if (section == null) {
            return null;
        }
        int index = indexBlock(x, y, z);
        int id = section[index];
        if (id <= 0) {
            return null;
        }
        return new meridian.core.api.FluidCell(id, fluidNames.getOrDefault(id, "Fluid"),
                ChunkDecoder.levelOf(fluidLevels.get(key), index));
    }

    /** The id of a fluid by name, or {@code -1} when the catalog has no such fluid. */
    public int fluidIdByName(String name) {
        if (name != null) {
            for (Map.Entry<Integer, String> e : fluidNames.entrySet()) {
                if (name.equals(e.getValue())) {
                    return e.getKey();
                }
            }
        }
        return -1;
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
