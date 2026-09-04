package meridian.core.api;

/**
 * What the <b>client</b> is shown of the remembered map.
 *
 * <p>{@link WorldMap} remembers everything; this decides how much of it the game holds at once.
 * The two are deliberately separate, because the client's map is bounded and the memory is not:
 * an earlier attempt pushed a whole saved world at the player on join and killed the client the
 * moment the world was large — after a long-distance teleport it died every time.
 *
 * <p>So the client is given a <em>window</em>: the explored tiles around the player, up to a
 * budget, fed in small batches, with what falls outside taken back. Tile size is the other
 * lever — a tile replayed at half the side costs a quarter of the pixels, so the same budget
 * covers four times the world at lower detail. A long jump is handled explicitly: the old
 * window is taken back before the new one is filled, so both never sit in the client at once.
 *
 * <pre>{@code
 * WorldMapView view = ctx.services().require(WorldMapView.class);
 * view.setTileSize(16);      // half detail, four times the area for the same budget
 * view.setRadiusChunks(48);
 * view.setEnabled(true);     // explored tiles stop fading behind you
 * }</pre>
 */
public interface WorldMapView {

    /** Whether explored tiles are replayed to the client and kept from being unloaded. */
    /**
     * Repaints a tile on its way to the client.
     *
     * <p>For a module that knows something about the ground the server does not - the world
     * downloader tints a chunk it has not actually downloaded, so the in-game map shows what has
     * been collected as well as what is out there.
     */
    @FunctionalInterface
    interface TileFilter {
        /**
         * The colours to send for this tile, or {@code null} to send it exactly as the server
         * drew it - which is the answer for nearly every tile, and costs nothing.
         *
         * @param tile the tile as it arrived; read it with {@link MapTile#colourAt}
         * @return {@code size * size} colours, {@code 0xRRGGBB}, row by row
         */
        int[] filter(int chunkX, int chunkZ, MapTile tile);
    }

    /**
     * Installs the filter every outgoing tile passes through; {@code null} removes it.
     *
     * <p>Applies to the server's own tiles and to any this view replays. Unloads are never
     * filtered: taking a tile away has no picture to repaint.
     *
     * <p>One filter at a time; installing a second replaces the first. Called on the network
     * threads, once per tile, so it must be quick and must not block.
     */
    void setTileFilter(TileFilter filter);

    /**
     * Sends a tile again, as it looks now.
     *
     * <p>The server sends a tile once and considers it delivered, so a tile whose look has since
     * changed - the ground under it has been downloaded, say, and its tint should come off -
     * would otherwise stay as it was until the player logs out. This is how a module says "again".
     */
    void refreshTile(int chunkX, int chunkZ);

    void setEnabled(boolean enabled);

    boolean isEnabled();

    /** Radius in chunk columns around the player that the client is kept filled with. */
    void setRadiusChunks(int radius);

    int radiusChunks();

    /**
     * Caps how many pixels a side a replayed tile may have; {@code 0} - the default - replays
     * each tile at the size it arrived in.
     *
     * <p>A smaller tile costs the client proportionally less, letting the same budget span more
     * world. Do not guess the server's size: it is not fixed, and a build that draws 96 pixels a
     * side would have every replayed tile shrunk to a third of the rest of the map. A tile is
     * never enlarged - the cap only ever takes pixels away.
     */
    void setTileSize(int pixels);

    /** The cap, or {@code 0} when tiles are replayed at whatever size they arrived in. */
    int tileSize();

    /** Hard cap on how many tiles the client is allowed to hold at once. */
    void setBudget(int maxTiles);

    int budget();

    /** How many tiles the client currently holds, as far as the proxy has seen. */
    int clientTileCount();

    /**
     * Shows the client its world map even where the server has switched the map off.
     *
     * <p>Only what the client is told: the map it then draws is the ground it has, plus whatever
     * this view replays into it. Independent of the teleport control below - a server may allow
     * the map and forbid teleporting from it, or the other way about.
     */
    void setForcedOn(boolean on);

    boolean isForcedOn();

    /** Somebody who will carry out a teleport the player asked for from the map. */
    @FunctionalInterface
    interface TeleportRequest {
        /**
         * @param blockX where on the map the player pointed, in world blocks
         * @param blockZ likewise - the map is seen from above, so there is no height in it
         * @return whether it was taken care of. The request is then not passed on; a server that
         *         gates teleporting on a permission answers one with a disconnect.
         */
        boolean handle(int blockX, int blockZ);
    }

    /**
     * Takes on the map's "teleport to coordinates" requests, and shows the control that makes
     * them.
     *
     * <p>The two go together on purpose. The client draws that control only when the server says
     * it may, and sends the request to the server, which refuses it unless the player has the
     * permission - so showing the control without answering the requests hands the player a
     * button that disconnects them. Subscribing shows it; passing {@code null} takes both away.
     */
    void onTeleportRequest(TeleportRequest handler);
}
