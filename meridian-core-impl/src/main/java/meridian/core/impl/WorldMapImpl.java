package meridian.core.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;
import meridian.core.api.MapTile;
import meridian.core.api.WorldMap;
import meridian.protocol.packets.worldmap.MapChunk;

/**
 * Collects the tiles the server draws, per world, and answers colour queries about them.
 *
 * <p>Fed by {@link WorldMapObserver} from every {@code UpdateWorldMap}. A tile with no image is
 * the server <em>unloading</em> a column from the client's view — it says nothing about the
 * world, so what we remember is left alone; the client-facing side decides separately what the
 * player is shown.
 *
 * <p>Tiles are kept per world id, because worlds have independent coordinate spaces and a
 * portal must not smear one map over another.
 */
final class WorldMapImpl implements WorldMap {

    /** world id → chunk key → decoded tile. */
    private final Map<UUID, Map<Long, MapTileImpl>> worlds = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<LongConsumer> listeners = new CopyOnWriteArrayList<>();

    /** Where tiles go before the join packet says which world this is. */
    private static final UUID UNKNOWN_WORLD = new UUID(0, 0);

    /** The world tiles are attributed to; switched by the world observer. */
    private volatile UUID currentWorld = UNKNOWN_WORLD;

    // ------------------------------------------------------------------
    // Ingest (core-internal)
    // ------------------------------------------------------------------

    /**
     * Points ingest and queries at another world.
     *
     * <p>Tiles can arrive before the join packet names the world - they ride different channels -
     * and those land under a placeholder. Naming the world takes them with it, so the map the
     * player sees at the moment they arrive is the one that was already being drawn.
     */
    void setCurrentWorld(UUID world) {
        if (world == null || world.equals(currentWorld)) {
            return;
        }
        Map<Long, MapTileImpl> early = worlds.remove(UNKNOWN_WORLD);
        this.currentWorld = world;
        if (early != null && !early.isEmpty()) {
            tilesOf(world).putAll(early);
        }
    }

    @Override
    public UUID currentWorld() {
        return currentWorld;
    }

    /** Decodes and stores the tiles of one {@code UpdateWorldMap}. */
    void ingest(MapChunk[] chunks) {
        if (chunks == null) return;
        Map<Long, MapTileImpl> tiles = tilesOf(currentWorld);
        for (MapChunk chunk : chunks) {
            if (chunk == null || chunk.image == null) {
                continue;   // an unload — the client's view shrinks, our memory does not
            }
            MapTileImpl tile = MapTileImpl.decode(chunk.chunkX, chunk.chunkZ, chunk.image);
            if (tile == null) continue;
            long key = WorldMap.key(chunk.chunkX, chunk.chunkZ);
            tiles.put(key, tile);
            for (LongConsumer listener : listeners) {
                try {
                    listener.accept(key);
                } catch (RuntimeException ignored) {
                    // a listener must never break ingest
                }
            }
        }
    }

    private Map<Long, MapTileImpl> tilesOf(UUID world) {
        return worlds.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
    }

    // ------------------------------------------------------------------
    // WorldMap
    // ------------------------------------------------------------------

    @Override
    public int colourAtBlock(int blockX, int blockZ) {
        MapTile tile = tilesOf(currentWorld).get(
                WorldMap.key(Math.floorDiv(blockX, TILE_BLOCKS), Math.floorDiv(blockZ, TILE_BLOCKS)));
        return tile == null ? -1 : tile.colourAtBlock(blockX, blockZ);
    }

    @Override
    public boolean isExplored(int chunkX, int chunkZ) {
        return tilesOf(currentWorld).containsKey(WorldMap.key(chunkX, chunkZ));
    }

    @Override
    public int exploredCount() {
        return tilesOf(currentWorld).size();
    }

    @Override
    public Optional<MapTile> tile(int chunkX, int chunkZ) {
        return Optional.ofNullable(tilesOf(currentWorld).get(WorldMap.key(chunkX, chunkZ)));
    }

    @Override
    public void onTileChanged(LongConsumer listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void removeTileListener(LongConsumer listener) {
        listeners.remove(listener);
    }

    @Override
    public Optional<Bounds> exploredBounds() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : tilesOf(currentWorld).keySet()) {
            int x = WorldMap.chunkX(key);
            int z = WorldMap.chunkZ(key);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        return maxX == Integer.MIN_VALUE ? Optional.empty()
                : Optional.of(new Bounds(minX, minZ, maxX, maxZ));
    }
}
