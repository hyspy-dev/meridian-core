package meridian.core.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import meridian.api.session.ProxySession;
import meridian.core.api.MapTile;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.WorldMap;
import meridian.core.api.WorldMapView;
import meridian.protocol.packets.worldmap.MapChunk;
import meridian.protocol.packets.worldmap.MapImage;
import meridian.protocol.packets.worldmap.UpdateWorldMap;

/**
 * Keeps a bounded window of remembered tiles in the client's map.
 *
 * <p>Three rules, each answering a way the naive version broke:
 *
 * <ul>
 *   <li><b>Never push the store.</b> Only tiles within {@code radius} of the player go out, at
 *       most {@code budget} of them, at most {@code BATCH} per tick.</li>
 *   <li><b>Take back what leaves.</b> Tiles outside the window are unloaded, so the working set
 *       stays bounded however far the player walks.</li>
 *   <li><b>Recentre on a jump.</b> A move farther than the window covers is a teleport: the old
 *       window is dropped before the new one is filled, so the client never holds two windows
 *       at once — the exact shape of the crash this replaces.</li>
 * </ul>
 *
 * <p>Bookkeeping is exact because the proxy sees both directions: the server's own loads and
 * unloads pass through {@link #filterServerUpdate}, which also drops an unload for a tile
 * inside the window — that is what stops explored ground from fading behind the player.
 */
final class WorldMapViewImpl implements WorldMapView {

    /** Tiles per tick. Small enough that a burst never lands as one huge packet. */
    private static final int BATCH = 64;
    /** Default budget — comfortably under what a client holds at full tile size. */
    private static final int DEFAULT_BUDGET = 6000;
    private static final int DEFAULT_RADIUS = 24;

    private final WorldMapImpl map;
    private final Supplier<Optional<Player>> player;
    private final Supplier<Optional<ProxySession>> session;

    private volatile boolean enabled;
    /** Installed by a module that repaints tiles - the coverage tint; usually absent. */
    private volatile TileFilter filter;
    private volatile int radius = DEFAULT_RADIUS;
    /** A cap on the replayed tile's side; 0 replays each tile at the size it came in. */
    private volatile int tileSize;
    private volatile int budget = DEFAULT_BUDGET;

    /** Tiles the client is believed to hold — the server's and ours together. */
    private final Set<Long> clientHas = new HashSet<>();
    private Integer centerX;
    private Integer centerZ;

    WorldMapViewImpl(WorldMapImpl map, Supplier<Optional<Player>> player,
                     Supplier<Optional<ProxySession>> session) {
        this.map = map;
        this.player = player;
        this.session = session;
    }

    // ------------------------------------------------------------------
    // Server traffic
    // ------------------------------------------------------------------

    /**
     * Rewrites a server {@code UpdateWorldMap} on its way to the client: loads are recorded,
     * and an unload of a tile we want kept is removed from the packet. Returns the tiles to
     * send on, or null when nothing is left to send.
     */
    synchronized MapChunk[] filterServerUpdate(MapChunk[] chunks) {
        if (chunks == null) {
            return null;
        }
        List<MapChunk> keep = new ArrayList<>(chunks.length);
        boolean changed = false;
        for (MapChunk chunk : chunks) {
            if (chunk == null) {
                changed = true;
                continue;
            }
            long key = WorldMap.key(chunk.chunkX, chunk.chunkZ);
            if (chunk.image != null) {
                clientHas.add(key);
                MapChunk painted = repaint(chunk);
                changed |= painted != chunk;
                keep.add(painted);
                continue;
            }
            // An unload. Inside the window, with a tile to show, we do not pass it on: the
            // client keeps what it has and the ground behind the player stays drawn.
            boolean remembered = map.tile(WorldMap.chunkX(key), WorldMap.chunkZ(key)).isPresent();
            if (enabled && inWindow(key) && remembered) {
                changed = true;
                continue;
            }
            clientHas.remove(key);
            keep.add(chunk);
        }
        // Handed back unchanged, the array itself says "nothing was done" - and the router then
        // forwards the server's own bytes instead of re-serialising a packet nobody touched.
        if (!changed) {
            return chunks;
        }
        return keep.isEmpty() ? null : keep.toArray(new MapChunk[0]);
    }

    // ------------------------------------------------------------------
    // Window maintenance
    // ------------------------------------------------------------------

    /** One step of window maintenance: recentre, top up, trim. Cheap when nothing moved. */
    synchronized void tick() {
        if (!enabled) {
            return;
        }
        ProxySession live = session.get().orElse(null);
        Vec3 position = player.get().map(Player::position).orElse(null);
        if (live == null || position == null) {
            return;
        }

        int cx = Math.floorDiv((int) position.x(), WorldMap.TILE_BLOCKS);
        int cz = Math.floorDiv((int) position.z(), WorldMap.TILE_BLOCKS);
        if (centerX != null && isJump(cx, cz)) {
            dropEverything(live);   // a teleport: never let two windows coexist
        }
        centerX = cx;
        centerZ = cz;

        // Trim first, then fill what is left of the budget. The other order oscillates: the
        // window would add a batch and immediately trim it again, every tick, forever.
        List<Long> remove = outsideWindow(cx, cz);
        if (!remove.isEmpty()) {
            send(live, remove, null);
        }

        int room = Math.min(BATCH, budget - clientHas.size());
        if (room <= 0) {
            return;
        }
        List<Long> add = new ArrayList<>();
        for (long key : window(cx, cz)) {
            if (clientHas.contains(key)) {
                continue;
            }
            if (map.tile(WorldMap.chunkX(key), WorldMap.chunkZ(key)).isEmpty()) {
                continue;
            }
            add.add(key);
            if (add.size() >= room) {
                break;
            }
        }
        if (!add.isEmpty()) {
            send(live, null, add);
        }
    }

    /** True when the player moved farther than the window covers — i.e. was teleported. */
    private boolean isJump(int cx, int cz) {
        return Math.max(Math.abs(cx - centerX), Math.abs(cz - centerZ)) > radius * 2L;
    }

    /** Takes the whole window back at once, so a new one can be built from nothing. */
    private void dropEverything(ProxySession live) {
        if (clientHas.isEmpty()) {
            return;
        }
        send(live, new ArrayList<>(clientHas), null);
        clientHas.clear();
    }

    /** Keys inside the window, nearest first — the player's surroundings fill in first. */
    private List<Long> window(int cx, int cz) {
        List<Long> keys = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                keys.add(WorldMap.key(cx + dx, cz + dz));
            }
        }
        keys.sort(Comparator.comparingLong(k -> distanceSq(k, cx, cz)));
        return keys;
    }

    /** Held tiles outside the window, plus any budget overflow — farthest first. */
    private List<Long> outsideWindow(int cx, int cz) {
        List<Long> drop = new ArrayList<>();
        List<Long> inside = new ArrayList<>();
        for (long key : clientHas) {
            if (inWindow(key, cx, cz)) {
                inside.add(key);
            } else {
                drop.add(key);
            }
        }
        // Even inside the window the budget rules: trim the farthest until it fits.
        int overflow = inside.size() - budget;
        if (overflow > 0) {
            inside.sort(Comparator.comparingLong((Long k) -> distanceSq(k, cx, cz)).reversed());
            drop.addAll(inside.subList(0, overflow));
        }
        return drop.size() > BATCH ? new ArrayList<>(drop.subList(0, BATCH)) : drop;
    }

    private boolean inWindow(long key) {
        return centerX != null && inWindow(key, centerX, centerZ);
    }

    private boolean inWindow(long key, int cx, int cz) {
        return Math.abs(WorldMap.chunkX(key) - cx) <= radius
                && Math.abs(WorldMap.chunkZ(key) - cz) <= radius;
    }

    private static long distanceSq(long key, int cx, int cz) {
        long dx = WorldMap.chunkX(key) - (long) cx;
        long dz = WorldMap.chunkZ(key) - (long) cz;
        return dx * dx + dz * dz;
    }

    /** Sends one packet: {@code unload} as image-less tiles, {@code load} as encoded ones. */
    private void send(ProxySession live, List<Long> unload, List<Long> load) {
        List<MapChunk> chunks = new ArrayList<>();
        if (unload != null) {
            for (long key : unload) {
                chunks.add(new MapChunk(WorldMap.chunkX(key), WorldMap.chunkZ(key), null));
                clientHas.remove(key);
            }
        }
        if (load != null) {
            for (long key : load) {
                MapTile tile = map.tile(WorldMap.chunkX(key), WorldMap.chunkZ(key)).orElse(null);
                if (!(tile instanceof MapTileImpl decoded)) {
                    continue;
                }
                decoded = repaint(decoded);
                // At the size it arrived in unless a module asked for less. The server's own
                // size is not a constant - this build draws 96 pixels a side - so a fixed number
                // here would shrink every replayed tile and leave the map a patchwork.
                int cap = tileSize;
                int side = cap <= 0 ? decoded.size() : Math.min(cap, decoded.size());
                MapImage image = MapImageEncoder.encode(decoded, side);
                if (image == null) {
                    continue;
                }
                chunks.add(new MapChunk(decoded.chunkX(), decoded.chunkZ(), image));
                clientHas.add(key);
            }
        }
        if (!chunks.isEmpty()) {
            live.sendToClient(new UpdateWorldMap(chunks.toArray(new MapChunk[0]), null, null));
        }
    }

    @Override
    public void setTileFilter(TileFilter filter) {
        this.filter = filter;
    }

    @Override
    public void refreshTile(int chunkX, int chunkZ) {
        long key = WorldMap.key(chunkX, chunkZ);
        ProxySession live;
        synchronized (this) {
            // Sent even when the client already has this tile: the point is that it now looks
            // different - the tint has come off ground that has just been downloaded.
            live = session.get().orElse(null);
            if (live == null || map.tile(chunkX, chunkZ).isEmpty()) {
                return;
            }
            send(live, null, List.of(key));
        }
    }

    /** The server's own tile, repainted if a module wants it repainted. */
    private MapChunk repaint(MapChunk chunk) {
        MapTile remembered = map.tile(chunk.chunkX, chunk.chunkZ).orElse(null);
        if (!(remembered instanceof MapTileImpl decoded)) {
            return chunk;               // nothing remembered to repaint from
        }
        MapTileImpl painted = repaint(decoded);
        if (painted == decoded) {
            return chunk;               // untouched: send the server's bytes, not a re-encode
        }
        MapImage image = MapImageEncoder.encode(painted, painted.size());
        return image == null ? chunk : new MapChunk(chunk.chunkX, chunk.chunkZ, image);
    }

    /** A tile as the filter would have it, or the tile itself when there is nothing to change. */
    private MapTileImpl repaint(MapTileImpl tile) {
        TileFilter installed = filter;
        if (installed == null) {
            return tile;
        }
        int[] pixels;
        try {
            pixels = installed.filter(tile.chunkX(), tile.chunkZ(), tile);
        } catch (RuntimeException e) {
            return tile;                // a filter that throws does not get to break the map
        }
        int side = tile.size();
        return pixels == null || pixels.length != side * side
                ? tile
                : MapTileImpl.fromPixels(tile.chunkX(), tile.chunkZ(), side, pixels);
    }

    /** Forgets the client's state — a new world starts from an empty map. */
    synchronized void reset() {
        clientHas.clear();
        centerX = null;
        centerZ = null;
    }

    // ------------------------------------------------------------------
    // WorldMapView
    // ------------------------------------------------------------------

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setRadiusChunks(int radius) {
        this.radius = Math.max(1, radius);
    }

    @Override
    public int radiusChunks() {
        return radius;
    }

    @Override
    public void setTileSize(int pixels) {
        this.tileSize = Math.max(0, pixels);
    }

    @Override
    public int tileSize() {
        return tileSize;
    }

    @Override
    public void setBudget(int maxTiles) {
        this.budget = Math.max(1, maxTiles);
    }

    @Override
    public int budget() {
        return budget;
    }

    @Override
    public synchronized int clientTileCount() {
        return clientHas.size();
    }
}
