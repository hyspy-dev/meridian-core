package meridian.core.api;

/**
 * One rendered map tile — the server's picture of a single chunk column.
 *
 * <p>Tiles arrive palette-indexed and packed; a {@code MapTile} is the decoded form, so a
 * consumer reads colours without knowing anything about the wire format. Immutable: a tile
 * handed out stays as it was, even when that column is redrawn later.
 */
public interface MapTile {

    /** Chunk column X this tile covers. */
    int chunkX();

    /** Chunk column Z this tile covers. */
    int chunkZ();

    /** Side of the tile in pixels — the resolution the server rendered it at. */
    int size();

    /**
     * The colour at a pixel inside the tile as {@code 0xRRGGBB}, or {@code -1} outside it.
     * Pixel {@code (0, 0)} is the tile's north-west corner.
     */
    int colourAt(int pixelX, int pixelZ);

    /** The colour at a world block inside this tile's column, or {@code -1} if it is elsewhere. */
    int colourAtBlock(int blockX, int blockZ);
}
