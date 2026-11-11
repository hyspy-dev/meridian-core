package meridian.core.api;

/**
 * Where to go: a cell to stand in, or anywhere within {@code radius} of it (horizontally, and
 * within {@code radius} up or down) - near a block to work on, near an entity to reach.
 */
public record Goal(int x, int y, int z, double radius) {

    /** Standing in this very cell. */
    public static Goal at(BlockPos cell) {
        return new Goal(cell.x(), cell.y(), cell.z(), 0);
    }

    /** Standing within {@code radius} of this cell. */
    public static Goal near(BlockPos cell, double radius) {
        return new Goal(cell.x(), cell.y(), cell.z(), Math.max(0, radius));
    }

    /** Whether standing in cell {@code (cx, cy, cz)} is there. */
    public boolean reachedBy(int cx, int cy, int cz) {
        if (radius <= 0) {
            return cx == x && cy == y && cz == z;
        }
        double dx = cx - x;
        double dz = cz - z;
        return dx * dx + dz * dz <= radius * radius && Math.abs(cy - y) <= Math.max(1, radius);
    }
}
