package meridian.core.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.ServerSetBlocks;
import meridian.protocol.packets.world.ServerSetFluid;
import meridian.protocol.packets.world.ServerSetFluids;
import meridian.protocol.packets.world.SetAbsentChunks;
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
 *
 * <p>Fed on the network threads, read from any thread without a lock: the maps are concurrent and
 * a read may see a section a moment before or after an edit to it, never a torn one.
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
    /** Fluid id &rarr; name (Water, Lava, ...): core's one fluid catalog. */
    private final FluidCatalog fluidCatalog;

    public ChunkTracker(FluidCatalog fluidCatalog) {
        this.fluidCatalog = fluidCatalog;
    }

    /** Block index within a 32³ section — {@code (y&31)<<10 | (z&31)<<5 | (x&31)}. */
    private static int indexBlock(int x, int y, int z) {
        return (y & 31) << 10 | (z & 31) << 5 | (x & 31);
    }

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    /** Columns (by section x, z) the server has sent sections of. */
    private final Set<Long> columns = ConcurrentHashMap.newKeySet();
    /** The server's column tops, used to distinguish a cave ceiling from a surface still loading. */
    private final Map<Long, int[]> heights = new ConcurrentHashMap<>();
    private volatile boolean heightmapsSent;

    void onColumnHeights(int x, int z, int[] data) {
        if (data == null || data.length != 1024) return;
        heightmapsSent = true;
        heights.put(column(x, z), data.clone());
        changedColumn(x, z);
    }

    void onColumnHeight(int x, int z, int height) {
        heights.computeIfPresent(column(x >> 5, z >> 5), (k, old) -> {
            int[] updated = old.clone();
            updated[(z & 31) * 32 + (x & 31)] = height;
            return updated;
        });
        changedColumn(x >> 5, z >> 5);
    }

    public int columnHeightAt(int x, int z) {
        int[] data = heights.get(column(x >> 5, z >> 5));
        return data == null ? Integer.MIN_VALUE : data[(z & 31) * 32 + (x & 31)];
    }

    public boolean columnHeightsPending(int x, int z) {
        return heightmapsSent && !heights.containsKey(column(x >> 5, z >> 5));
    }

    /** Explicitly received, unlike the old protocol's implicit air in an unfinished column. */
    public boolean receivedSection(int x, int y, int z) {
        SectionKey key = new SectionKey(x >> 5, y >> 5, z >> 5);
        return sections.containsKey(key) || absent.contains(key);
    }
    /**
     * Sections the server said hold nothing ({@code SetAbsentChunks}): known, and all air, without
     * a {@code SetChunk} of their own.
     */
    private final Set<SectionKey> absent = ConcurrentHashMap.newKeySet();
    /**
     * Whether this server says which sections are absent. One that does streams the sections in a
     * sphere about the player and unloads them one at a time, so a section it has not sent is
     * unknown even in a column it has sent; one that does not (an older server) leaves out a
     * column's air sections and unloads whole columns, so there a missing section is air.
     */
    private volatile boolean announcesAbsent;
    /** Whether this server sends fluids at all ({@code SetFluids}) - one with no fluid layer does not. */
    private volatile boolean fluidsSent;
    /** Sections sent as nothing but air - a search steps over them. Left as soon as a block is set. */
    private final Set<SectionKey> airOnly = ConcurrentHashMap.newKeySet();

    private static long column(int sectionX, int sectionZ) {
        return ((long) sectionX << 32) | (sectionZ & 0xFFFFFFFFL);
    }

    /**
     * Whether the server has sent the column a world position stands in - some section of it.
     * Whether a given cell of it is known is {@link #known}'s to say.
     */
    public boolean columnKnown(int x, int z) {
        return columns.contains(column(x >> 5, z >> 5));
    }

    /**
     * Whether the blocks of the cell at a world position are known: its section was sent, or the
     * server said the section holds nothing - or, from a server that does not say so, the section
     * was left out of a column that was sent, as a section of nothing but air is.
     */
    public boolean known(int x, int y, int z) {
        SectionKey key = new SectionKey(x >> 5, y >> 5, z >> 5);
        if (sections.containsKey(key)) {
            return true;
        }
        return announcesAbsent ? absent.contains(key) : columns.contains(column(key.x(), key.z()));
    }

    /**
     * Whether the fluids of the cell at a world position are known: its section's fluids came, or
     * the section is {@linkplain #known known} to hold nothing at all. A section's blocks and its
     * fluids come in packets of their own, one after the other; between the two the blocks are
     * known and the fluids are not, and a pond reads dry. A server that has sent no fluids at all
     * has no fluid layer to wait for.
     */
    public boolean fluidsKnown(int x, int y, int z) {
        SectionKey key = new SectionKey(x >> 5, y >> 5, z >> 5);
        if (!fluidsSent || fluidSections.containsKey(key)) {
            return true;
        }
        return !sections.containsKey(key) && known(x, y, z);
    }

    void onSetChunk(SetChunk packet) {
        try {
            SectionKey key = new SectionKey(packet.x, packet.y, packet.z);
            ChunkDecoder.Section section = ChunkDecoder.decodeSection(packet.data);
            if (allAir(section.ids())) {
                airOnly.add(key);
            } else {
                airOnly.remove(key);
            }
            sections.put(key, section.ids());
            absent.remove(key);
            columns.add(column(packet.x, packet.z));
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
            return;
        }
        // The whole column: from an older server, its first section makes the rest known as air.
        changedColumn(packet.x, packet.z);
    }

    /** Sections of a column the server says hold nothing; {@code ys} are section coordinates. */
    void onSetAbsentChunks(SetAbsentChunks packet) {
        announcesAbsent = true;
        if (packet.ys == null) {
            return;
        }
        for (int y : packet.ys) {
            SectionKey key = new SectionKey(packet.x, y, packet.z);
            // Absent now, whatever was there: the server's word is the latest.
            sections.remove(key);
            rotations.remove(key);
            fillers.remove(key);
            fluidSections.remove(key);
            fluidLevels.remove(key);
            airOnly.remove(key);
            absent.add(key);
            changedSection(key);
        }
    }

    /** The world was torn down ({@code ClearChunks}): a world change, or a resend of this one. */
    void onClearChunks() {
        sections.clear();
        rotations.clear();
        fillers.clear();
        fluidSections.clear();
        fluidLevels.clear();
        columns.clear();
        heights.clear();
        absent.clear();
        airOnly.clear();
    }

    private static boolean allAir(int[] ids) {
        for (int id : ids) {
            if (id > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The blocks of a section, for a change to them: one known to hold nothing - absent by the
     * server's word, or left out of a column an older server sent - is all air and gets made now
     * (a block placed high in the sky); one not known stays unknown.
     */
    private int[] sectionForChange(SectionKey key) {
        int[] section = sections.get(key);
        if (section == null && (announcesAbsent ? absent.contains(key)
                : columns.contains(column(key.x(), key.z())))) {
            airOnly.add(key);
            // Known to hold nothing, fluids included: no fluid packet will come for it.
            fluidSections.computeIfAbsent(key, k -> new int[ChunkDecoder.SECTION_BLOCKS]);
            section = sections.computeIfAbsent(key, k -> new int[ChunkDecoder.SECTION_BLOCKS]);
            absent.remove(key);
        }
        return section;
    }

    void onServerSetBlock(ServerSetBlock packet) {
        SectionKey key = new SectionKey(packet.x >> 5, packet.y >> 5, packet.z >> 5);
        int[] section = sectionForChange(key);
        if (section != null) {
            int index = indexBlock(packet.x, packet.y, packet.z);
            if (packet.blockId > 0) {
                airOnly.remove(key);    // before the block is there: a search must not step over it
            }
            section[index] = packet.blockId;
            setRotation(key, index, packet.rotation);
            setFiller(key, index, packet.filler);
            changedCell(packet.x, packet.y, packet.z);
        }
    }

    void onServerSetBlocks(ServerSetBlocks packet) {
        if (packet.cmds == null) {
            return;
        }
        SectionKey key = new SectionKey(packet.x, packet.y, packet.z);
        int[] section = sectionForChange(key);
        if (section == null) {
            return;
        }
        for (SetBlockCmd cmd : packet.cmds) {
            if (cmd.blockId > 0) {
                airOnly.remove(key);
                break;
            }
        }
        for (SetBlockCmd cmd : packet.cmds) {
            int index = cmd.index & 0xFFFF;
            if (index < section.length) {
                section[index] = cmd.blockId;
                setRotation(key, index, cmd.rotation);
                setFiller(key, index, cmd.filler);
            }
        }
        changedSection(key);
    }

    // ------------------------------------------------------------------
    // Watching a cell
    // ------------------------------------------------------------------

    /** A watch on one cell, from {@link #watchCell}; closing it stops it. */
    public interface CellWatch extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * One watched cell - or column, at every height - and what to tell. Compared by identity: two
     * watches of a cell are two.
     */
    private static final class Watch {
        final int x;
        final int y;
        final int z;
        final boolean column;
        final Runnable changed;

        Watch(int x, int y, int z, boolean column, Runnable changed) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.column = column;
            this.changed = changed;
        }
    }

    /** Watches by the column (section x, z) they are in - what a change looks them up by. */
    private final Map<Long, Set<Watch>> watches = new ConcurrentHashMap<>();

    /**
     * Calls {@code changed} after every change the server makes that may touch the cell at a world
     * position: a block or a fluid set there, a batch of edits to its section, its section's blocks
     * or fluids or another section of its column arriving, its section said to be absent. It runs
     * on the network thread that carried the change, after the mirror took it, and is told the cell
     * may have changed - it reads what the cell holds now. Until {@link CellWatch#close} is called.
     */
    public CellWatch watchCell(int x, int y, int z, Runnable changed) {
        return watch(new Watch(x, y, z, false, changed));
    }

    /** As {@link #watchCell}, for every cell of the column at {@code (x, z)}, at any height. */
    public CellWatch watchColumn(int x, int z, Runnable changed) {
        return watch(new Watch(x, 0, z, true, changed));
    }

    private CellWatch watch(Watch w) {
        long col = column(w.x >> 5, w.z >> 5);
        watches.compute(col, (k, set) -> {
            Set<Watch> s = set != null ? set : ConcurrentHashMap.newKeySet();
            s.add(w);
            return s;
        });
        return () -> watches.computeIfPresent(col, (k, set) -> {
            set.remove(w);
            return set.isEmpty() ? null : set;
        });
    }

    private void changedCell(int x, int y, int z) {
        if (watches.isEmpty()) {
            return;
        }
        Set<Watch> set = watches.get(column(x >> 5, z >> 5));
        if (set != null) {
            for (Watch w : set) {
                if (w.x == x && w.z == z && (w.column || w.y == y)) {
                    tell(w);
                }
            }
        }
    }

    private void changedSection(SectionKey key) {
        if (watches.isEmpty()) {
            return;
        }
        Set<Watch> set = watches.get(column(key.x(), key.z()));
        if (set != null) {
            for (Watch w : set) {
                if (w.column || w.y >> 5 == key.y()) {
                    tell(w);
                }
            }
        }
    }

    private void changedColumn(int sectionX, int sectionZ) {
        if (watches.isEmpty()) {
            return;
        }
        Set<Watch> set = watches.get(column(sectionX, sectionZ));
        if (set != null) {
            set.forEach(this::tell);
        }
    }

    /** Tells one watch, never letting what it does break the feed. */
    private void tell(Watch w) {
        try {
            w.changed.run();
        } catch (RuntimeException e) {
            log.warn("meridian-core: a block watch at ({},{},{}) failed: {}", w.x, w.y, w.z, e.toString());
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
            fluidsSent = true;
            fluidSections.put(key, fluids.ids());
            if (fluids.levels() != null) {
                fluidLevels.put(key, fluids.levels());
            } else {
                fluidLevels.remove(key);
            }
        } catch (RuntimeException e) {
            log.warn("meridian-core: failed to decode fluid section ({},{},{}): {}",
                    packet.x, packet.y, packet.z, e.toString());
            return;
        }
        changedSection(new SectionKey(packet.x, packet.y, packet.z));
    }

    /** One fluid cell changed; {@code ServerSetFluid.x/y/z} are block coordinates. */
    void onServerSetFluid(ServerSetFluid packet) {
        setFluid(new SectionKey(packet.x >> 5, packet.y >> 5, packet.z >> 5),
                indexBlock(packet.x, packet.y, packet.z), packet.fluidId, packet.fluidLevel);
        changedCell(packet.x, packet.y, packet.z);
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
        changedSection(key);
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
                absent.remove(key);
                airOnly.remove(key);
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
        columns.remove(column(chunkX, chunkZ));
        heights.remove(column(chunkX, chunkZ));
        sections.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        rotations.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        fillers.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        fluidSections.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        fluidLevels.keySet().removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        absent.removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
        airOnly.removeIf(k -> k.x() == chunkX && k.z() == chunkZ);
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
        return new meridian.core.api.FluidCell(id, fluidName(id),
                ChunkDecoder.levelOf(fluidLevels.get(key), index));
    }

    /** The id of a fluid by name, or {@code -1} when the catalog has no such fluid. */
    public int fluidIdByName(String name) {
        return fluidCatalog.idByName(name);
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
        return fluidName(id);
    }

    /** A fluid id's name, or {@code "Fluid"} for one the catalog has not named yet. */
    private String fluidName(int id) {
        String name = fluidCatalog.name(id);
        return name != null ? name : "Fluid";
    }

    /** Number of sections currently mirrored. */
    public int sectionCount() {
        return sections.size();
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** A block a search found: its cell, its id, and how far its middle is from the start, squared. */
    public record Found(int x, int y, int z, int id, double distanceSq) {}

    private static final Comparator<Found> NEAREST_FIRST = Comparator.comparingDouble(Found::distanceSq)
            .thenComparingInt(Found::y).thenComparingInt(Found::z).thenComparingInt(Found::x);

    /**
     * The blocks whose middle is within {@code radius} of {@code (fx, fy, fz)} and whose id
     * {@code type} accepts, nearest first, at most {@code limit}.
     *
     * <p>Walks the mirrored sections the sphere reaches, not the cells of a cube: a section not
     * sent, or sent as nothing but air, costs nothing, and no cell is looked at twice. Air is
     * never offered to {@code type}, and nor is a cell a bigger block only fills - such a block is
     * found at the cell it stands on. {@code type} is asked for every block in reach, so a caller
     * whose test is dear answers it once per id.
     */
    public List<Found> find(double fx, double fy, double fz, double radius, IntPredicate type, int limit) {
        if (limit < 1 || !(radius >= 0) || !Double.isFinite(fx) || !Double.isFinite(fy) || !Double.isFinite(fz)) {
            return List.of();
        }
        double r2 = radius * radius;
        // The cells whose middle (c + 0.5) can be within reach; a cast past the int range saturates.
        int x0 = (int) Math.ceil(fx - radius - 0.5);
        int x1 = (int) Math.floor(fx + radius - 0.5);
        int y0 = (int) Math.ceil(fy - radius - 0.5);
        int y1 = (int) Math.floor(fy + radius - 0.5);
        int z0 = (int) Math.ceil(fz - radius - 0.5);
        int z1 = (int) Math.floor(fz + radius - 0.5);
        if (x0 > x1 || y0 > y1 || z0 > z1) {
            return List.of();
        }
        Search s = new Search(fx, fy, fz, r2, x0, x1, y0, y1, z0, z1, type, limit);
        int sx0 = x0 >> 5, sx1 = x1 >> 5, sy0 = y0 >> 5, sy1 = y1 >> 5, sz0 = z0 >> 5, sz1 = z1 >> 5;
        double span = (sx1 - (double) sx0 + 1) * (sy1 - (double) sy0 + 1) * (sz1 - (double) sz0 + 1);
        if (span <= sections.size()) {
            for (int sx = sx0; sx <= sx1; sx++) {
                for (int sz = sz0; sz <= sz1; sz++) {
                    for (int sy = sy0; sy <= sy1; sy++) {
                        SectionKey key = new SectionKey(sx, sy, sz);
                        int[] ids = sections.get(key);
                        if (ids != null) {
                            s.section(key, ids);
                        }
                    }
                }
            }
        } else {
            // A reach wider than what is mirrored: go over what is there instead.
            for (Map.Entry<SectionKey, int[]> e : sections.entrySet()) {
                SectionKey k = e.getKey();
                if (k.x() >= sx0 && k.x() <= sx1 && k.y() >= sy0 && k.y() <= sy1 && k.z() >= sz0 && k.z() <= sz1) {
                    s.section(k, e.getValue());
                }
            }
        }
        List<Found> out = new ArrayList<>(s.best);
        out.sort(NEAREST_FIRST);
        return out;
    }

    /** One search under way: its sphere, its cell bounds, and the nearest blocks so far. */
    private final class Search {
        final double fx, fy, fz, r2;
        final int x0, x1, y0, y1, z0, z1;
        final IntPredicate type;
        final int limit;
        /** The nearest found so far, the farthest of them on top - the one a nearer block replaces. */
        final PriorityQueue<Found> best;

        Search(double fx, double fy, double fz, double r2, int x0, int x1, int y0, int y1, int z0, int z1,
               IntPredicate type, int limit) {
            this.fx = fx;
            this.fy = fy;
            this.fz = fz;
            this.r2 = r2;
            this.x0 = x0;
            this.x1 = x1;
            this.y0 = y0;
            this.y1 = y1;
            this.z0 = z0;
            this.z1 = z1;
            this.type = type;
            this.limit = limit;
            this.best = new PriorityQueue<>(Math.min(limit, 256) + 1, NEAREST_FIRST.reversed());
        }

        void section(SectionKey key, int[] ids) {
            if (airOnly.contains(key)) {
                return;
            }
            int bx = key.x() << 5, by = key.y() << 5, bz = key.z() << 5;
            // The middle of this section's cells nearest the start: past reach, nothing in it is.
            double nx = clamp(fx, bx + 0.5, bx + 31.5) - fx;
            double ny = clamp(fy, by + 0.5, by + 31.5) - fy;
            double nz = clamp(fz, bz + 0.5, bz + 31.5) - fz;
            if (nx * nx + ny * ny + nz * nz > r2) {
                return;
            }
            int[] fill = fillers.get(key);
            int cx0 = Math.max(x0, bx), cx1 = Math.min(x1, bx + 31);
            int cy0 = Math.max(y0, by), cy1 = Math.min(y1, by + 31);
            int cz0 = Math.max(z0, bz), cz1 = Math.min(z1, bz + 31);
            for (int y = cy0; y <= cy1; y++) {
                double dy = y + 0.5 - fy;
                double leftY = r2 - dy * dy;
                if (leftY < 0) {
                    continue;
                }
                for (int z = cz0; z <= cz1; z++) {
                    double dz = z + 0.5 - fz;
                    double left = leftY - dz * dz;
                    if (left < 0) {
                        continue;
                    }
                    // The row's cells within reach, from the circle the sphere cuts at this y and z.
                    double reach = Math.sqrt(left);
                    int xa = Math.max(cx0, (int) Math.ceil(fx - 0.5 - reach));
                    int xb = Math.min(cx1, (int) Math.floor(fx - 0.5 + reach));
                    int row = (y & 31) << 10 | (z & 31) << 5;
                    for (int x = xa; x <= xb; x++) {
                        int i = row | (x & 31);
                        int id = ids[i];
                        if (id <= 0 || fill != null && fill[i] != 0) {
                            continue;
                        }
                        double dx = x + 0.5 - fx;
                        double d2 = dx * dx + dy * dy + dz * dz;
                        if (d2 > r2 || best.size() >= limit && d2 >= best.peek().distanceSq()) {
                            continue;
                        }
                        if (!type.test(id)) {
                            continue;
                        }
                        best.add(new Found(x, y, z, id, d2));
                        if (best.size() > limit) {
                            best.poll();
                        }
                    }
                }
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
