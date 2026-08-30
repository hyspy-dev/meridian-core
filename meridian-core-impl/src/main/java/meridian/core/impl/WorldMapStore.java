package meridian.core.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * On-disk memory of the map, so exploration outlives the session.
 *
 * <p>One file per module data directory — which the proxy already scopes per server, so two
 * servers can never share a map. Inside, tiles are grouped by world id: worlds have unrelated
 * coordinate spaces and merging them would draw one world's terrain onto another's.
 *
 * <p>Tiles are stored <b>decoded</b> (plain RGB pixels), not in wire form. The wire format
 * belongs to one protocol version and changes with the game; a map that survives an update is
 * worth more than the bytes saved by keeping the packed original.
 *
 * <pre>
 *   "MMAP" magic, int version
 *   int worldCount
 *     per world: long msb, long lsb, int tileCount
 *       per tile: int chunkX, int chunkZ, short size, byte[size*size*3]   (RGB)
 * </pre>
 *
 * The whole file is gzipped; RGB pixels of terrain compress well.
 */
final class WorldMapStore {

    private static final int MAGIC = 0x4D4D4150;   // "MMAP"
    private static final int VERSION = 1;
    /** A tile larger than this is refused as corrupt rather than allocated. */
    private static final int MAX_TILE_SIZE = 512;

    private final Path file;

    WorldMapStore(Path file) {
        this.file = file;
    }

    /** Everything on disk, or an empty map when there is no file yet (or it is unreadable). */
    Map<UUID, Map<Long, MapTileImpl>> load() {
        Map<UUID, Map<Long, MapTileImpl>> worlds = new HashMap<>();
        if (!Files.isRegularFile(file)) return worlds;
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return worlds;
            int worldCount = in.readInt();
            for (int w = 0; w < worldCount; w++) {
                UUID world = new UUID(in.readLong(), in.readLong());
                int tileCount = in.readInt();
                Map<Long, MapTileImpl> tiles = new HashMap<>(Math.max(16, tileCount));
                for (int t = 0; t < tileCount; t++) {
                    int chunkX = in.readInt();
                    int chunkZ = in.readInt();
                    int size = in.readShort();
                    if (size <= 0 || size > MAX_TILE_SIZE) return worlds;   // corrupt: stop, keep what we have
                    byte[] rgb = in.readNBytes(size * size * 3);
                    if (rgb.length != size * size * 3) return worlds;
                    tiles.put(meridian.core.api.WorldMap.key(chunkX, chunkZ),
                            MapTileImpl.fromRgb(chunkX, chunkZ, size, rgb));
                }
                worlds.put(world, tiles);
            }
        } catch (IOException | RuntimeException e) {
            return worlds;   // a damaged map is not worth failing a session over
        }
        return worlds;
    }

    /** Writes every world's tiles, replacing the file only once it is complete. */
    void save(Map<UUID, Map<Long, MapTileImpl>> worlds) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(worlds.size());
            for (Map.Entry<UUID, Map<Long, MapTileImpl>> world : worlds.entrySet()) {
                out.writeLong(world.getKey().getMostSignificantBits());
                out.writeLong(world.getKey().getLeastSignificantBits());
                out.writeInt(world.getValue().size());
                for (MapTileImpl tile : world.getValue().values()) {
                    out.writeInt(tile.chunkX());
                    out.writeInt(tile.chunkZ());
                    out.writeShort(tile.size());
                    out.write(tile.toRgb());
                }
            }
        }
        // Move into place only when the whole map is written: an interrupted save must not
        // leave a truncated file where a good map used to be.
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
