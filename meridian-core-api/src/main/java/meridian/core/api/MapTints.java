package meridian.core.api;

import java.util.UUID;

/**
 * Where a module hands the map its opinion about the ground.
 *
 * <p>Provided by whoever draws a map of the world; asked for with {@code get} rather than
 * {@code require}, because a module that colours the map has to work without one - it simply has
 * nothing to colour.
 *
 * <p>The map holds no copy of what a layer knows. It asks the layer while it builds a tile and
 * forgets the answer with the tile, which is why a layer has to say when an answer changes.
 */
public interface MapTints {

    /**
     * Adds a layer. Closing what comes back takes it away again - a module that is being
     * unloaded, or a setting that turns its colouring off.
     */
    AutoCloseable add(MapTint tint);

    /**
     * Says that one column's state has changed, so that what is drawn there is drawn again.
     *
     * <p>The right size of news. Columns are gathered up and redrawn together, so a layer that
     * works through a thousand of them may say so a thousand times; it costs one rebuild each and
     * nothing at all for a column nobody is looking at.
     */
    void changed(UUID world, int chunkX, int chunkZ);

    /**
     * Says that everything this layer had to say has changed - a setting turned on or off, a
     * whole world reclassified.
     *
     * <p>Expensive on purpose: it drops what the map has built and paints the wide view again
     * from the file, which on a large map is seconds of work. For a switch somebody flipped, not
     * for news arriving.
     */
    void changedAll(UUID world);
}
