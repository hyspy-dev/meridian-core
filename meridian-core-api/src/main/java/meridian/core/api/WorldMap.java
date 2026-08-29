package meridian.core.api;

import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * The world map as the server draws it — collected, remembered, and readable by colour.
 *
 * <p>The server renders the map itself and streams it as tiles, one per chunk column, for the
 * area around the player; walking away makes it drop them again. Core keeps every tile it sees,
 * on disk per server and world, so "where you have already been" outlives the session — the
 * data behind a minimap, a persistent explored map, or an exported image.
 *
 * <p>A Layer-1 service and a building block, not a feature: nothing here draws anything or
 * decides what the player is shown. Feeding tiles back to the client (which ones, at what
 * resolution, how many at a time) is a separate concern — see {@code WorldMapView}.
 *
 * <pre>{@code
 * WorldMap map = ctx.services().require(WorldMap.class);
 * int rgb = map.colourAtBlock(x, z);          // -1 when that column was never seen
 * boolean seen = map.isExplored(x >> 5, z >> 5);
 * }</pre>
 */
public interface WorldMap {

    /** Side of a map tile in world blocks — one tile covers one chunk column. */
    int TILE_BLOCKS = 32;

    /**
     * The map colour at world block {@code (blockX, blockZ)} as {@code 0xRRGGBB}, or
     * {@code -1} when that column has never been mapped.
     */
    int colourAtBlock(int blockX, int blockZ);

    /**
     * The world the client is in, as the server identifies it. Everything else on this service
     * answers for this world alone.
     *
     * <p>Worth watching if a module holds anything tied to the client's state — pushed images,
     * a HUD it built. Entering a world gives the client a clean slate, so whatever was put
     * there before is gone and has to be sent again.
     */
    UUID currentWorld();

    /** Whether a tile for this chunk column is known (from this session or a previous one). */
    boolean isExplored(int chunkX, int chunkZ);

    /** How many tiles are known for the current world. */
    int exploredCount();

    /**
     * The tile for a chunk column, or empty if it was never mapped. The returned tile is a
     * snapshot: later updates to that column do not change it.
     */
    Optional<MapTile> tile(int chunkX, int chunkZ);

    /**
     * Registers a listener called with the packed key of every tile that arrives or changes.
     * Keys decompose via {@link #chunkX(long)} / {@link #chunkZ(long)}. Called on the network
     * thread — do the work elsewhere.
     */
    void onTileChanged(LongConsumer listener);

    /** Stops delivering tile changes to a listener registered earlier. */
    void removeTileListener(LongConsumer listener);

    /** The chunk-column extent of what is known, or empty when nothing is. */
    Optional<Bounds> exploredBounds();

    /** Inclusive chunk-column extent of the known map. */
    record Bounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

        /** Width in chunk columns. */
        public int width() {
            return maxChunkX - minChunkX + 1;
        }

        /** Height in chunk columns. */
        public int height() {
            return maxChunkZ - minChunkZ + 1;
        }
    }

    /** Packs a chunk column into the key used by listeners and lookups. */
    static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** The chunk X of a packed key. */
    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    /** The chunk Z of a packed key. */
    static int chunkZ(long key) {
        return (int) (long) key;
    }
}
