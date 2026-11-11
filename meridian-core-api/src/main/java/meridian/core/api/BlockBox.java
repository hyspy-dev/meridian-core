package meridian.core.api;

/**
 * A box of whole blocks, both corners in it, the lesser corner first - a selection, the cells a
 * prefab covers, an area to work in.
 */
public record BlockBox(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax) {

    /** The box between two corners, given in any order. */
    public static BlockBox of(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new BlockBox(Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1), Math.max(x0, x1),
                Math.max(y0, y1), Math.max(z0, z1));
    }

    /** The box between two blocks. */
    public static BlockBox of(BlockPos a, BlockPos b) {
        return of(a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
    }

    public boolean contains(int x, int y, int z) {
        return x >= xMin && x <= xMax && y >= yMin && y <= yMax && z >= zMin && z <= zMax;
    }

    public boolean contains(BlockPos p) {
        return contains(p.x(), p.y(), p.z());
    }

    /** How many blocks it holds. */
    public long volume() {
        return (long) (xMax - xMin + 1) * (yMax - yMin + 1) * (zMax - zMin + 1);
    }

    /** The box grown by {@code by} blocks each way. */
    public BlockBox grow(int by) {
        return new BlockBox(xMin - by, yMin - by, zMin - by, xMax + by, yMax + by, zMax + by);
    }
}
