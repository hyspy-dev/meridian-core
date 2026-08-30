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
    private volatile int radius = DEFAULT_RADIUS;
    private volatile int tileSize = WorldMap.TILE_BLOCKS;
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
        for (MapChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            long key = WorldMap.key(chunk.chunkX, chunk.chunkZ);
            if (chunk.image != null) {
                clientHas.add(key);
                keep.add(chunk);
                continue;
            }
            // An unload. Inside the window, with a tile to show, we do not pass it on: the
            // client keeps what it has and the ground behind the player stays drawn.
            boolean remembered = map.tile(WorldMap.chunkX(key), WorldMap.chunkZ(key)).isPresent();
            if (enabled && inWindow(key) && remembered) {
                continue;
            }
            clientHas.remove(key);
            keep.add(chunk);
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
                MapImage image = MapImageEncoder.encode(decoded, tileSize);
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
        this.tileSize = Math.max(1, Math.min(WorldMap.TILE_BLOCKS, pixels));
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
