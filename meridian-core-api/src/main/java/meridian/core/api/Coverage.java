package meridian.core.api;

import java.util.UUID;

/**
 * Which ground has actually been downloaded.
 *
 * <p>Provided by whoever is collecting the world - the world downloader - and read by whoever
 * wants to show it. A map that knows this can say what has been collected as well as what is out
 * there; a map without it simply shows everything the server drew.
 *
 * <p>Optional by nature: ask the registry with {@code get} rather than {@code require}, and draw
 * the map plainly when nobody is collecting.
 */
public interface Coverage {

    /** Whether this column's blocks have been downloaded during this session. */
    boolean hasNow(UUID world, int chunkX, int chunkZ);

    /** Whether they were downloaded at any point, this session or an earlier one. */
    boolean hasEver(UUID world, int chunkX, int chunkZ);
}
