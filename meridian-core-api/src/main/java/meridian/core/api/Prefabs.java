package meridian.core.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The server's prefab files - {@code .prefab.json}, the format its {@code /prefab save} writes and
 * its paste tool loads ({@code SelectionPrefabSerializer}, version {@value #VERSION}) - read, written,
 * taken from the world and turned into this connection's blocks.
 *
 * <p>A prefab names its blocks, so it means the same on any server and in any session; a {@link
 * Clipboard} numbers them for one connection. {@link #capture} and {@link #write} go one way, {@link
 * #read} and {@link #resolve} the other.
 *
 * <p>The file keeps what the server keeps: every cell that is not air, the cells a bigger block only
 * fills included, with their offset back to the block's own cell ({@code filler}); a paste puts down
 * the own cells only, and the block brings its other cells back - so {@link #resolve} leaves the
 * filled ones out, as the server's paste does.
 */
public interface Prefabs {

    /** The file version written and read ({@code SelectionPrefabSerializer.VERSION}). */
    int VERSION = 8;

    /**
     * One block of a prefab, relative to its anchor: the block by name, which way it faces ({@link
     * Block#rotation()}), and on a cell a bigger block only fills, the offset back to its own cell
     * ({@link Block#filler()}, {@code 0} on the block's own cell).
     */
    record Cell(int x, int y, int z, String name, int rotation, int filler) {}

    /** One fluid cell of a prefab, relative to its anchor: the fluid by name, and its level. */
    record Fluid(int x, int y, int z, String name, int level) {}

    /** A prefab: its anchor, its blocks, its fluids. */
    record Prefab(int anchorX, int anchorY, int anchorZ, List<Cell> blocks, List<Fluid> fluids) {
        public Prefab {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            fluids = fluids == null ? List.of() : List.copyOf(fluids);
        }
    }

    /**
     * A prefab in this connection's numbers: what a paste of it would put down, and what could not
     * be put down.
     *
     * @param clipboard  the own cells and the fluids, relative to the prefab's anchor
     * @param empty      cells left out for being {@link BlockView#EMPTY}
     * @param unknown    names this connection's catalog does not have, and how many cells each
     */
    record Resolved(Clipboard clipboard, int empty, Map<String, Integer> unknown) {}

    /**
     * Reads a prefab file, whatever its {@code version} says (the server takes {@value #VERSION}
     * alone). Names are kept as written - a server reading the file migrates them by its {@code
     * blockIdVersion}, which this does not. Entities and block components are not read.
     *
     * @throws IOException when the file cannot be read, or has no {@code blocks}
     */
    Prefab read(Path file) throws IOException;

    /**
     * Writes a prefab file the way the server does: version {@value #VERSION}, {@code
     * blockIdVersion} {@link BlockView#BLOCK_ID_VERSION}, cells ordered by x, z, y, a rotation and a
     * filler only where they are not nought, fluids only when there are any. Through a temporary
     * file, so a half-written prefab never replaces a whole one.
     */
    void write(Path file, Prefab prefab) throws IOException;

    /**
     * The world's blocks and fluids in a box, as the server's {@code /prefab save} takes them:
     * every cell that is not air, filled cells with their {@code filler}, relative to the box's
     * lowest corner, which is also the anchor. A cell whose chunk is not loaded reads as air.
     */
    Prefab capture(BlockPos min, BlockPos max);

    /**
     * A prefab in this connection's numbers ({@link WorldState#blockId}, {@link World#fluidId}):
     * own cells only, {@link BlockView#EMPTY} left out and counted, names the catalog lacks left out
     * and counted. {@code Empty} is block 0 before the catalog has arrived too.
     */
    Resolved resolve(Prefab prefab);
}
