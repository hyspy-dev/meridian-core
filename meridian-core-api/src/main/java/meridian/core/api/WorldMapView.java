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
    void setEnabled(boolean enabled);

    boolean isEnabled();

    /** Radius in chunk columns around the player that the client is kept filled with. */
    void setRadiusChunks(int radius);

    int radiusChunks();

    /**
     * Pixels per side of a replayed tile (the server's own are 32). Smaller tiles cost the
     * client proportionally less, letting the budget span more world.
     */
    void setTileSize(int pixels);

    int tileSize();

    /** Hard cap on how many tiles the client is allowed to hold at once. */
    void setBudget(int maxTiles);

    int budget();

    /** How many tiles the client currently holds, as far as the proxy has seen. */
    int clientTileCount();
}
