package meridian.core.api;

/**
 * A block face / cardinal direction — the neutral, protocol-free equivalent of
 * Hytale's {@code BlockFace}. Used to pick which side of a block to place
 * against: the placed block lands at {@code target + normal}.
 *
 * <p>Normals match the server's convention: {@code NORTH = -Z}, {@code SOUTH = +Z},
 * {@code EAST = +X}, {@code WEST = -X}, {@code UP = +Y}, {@code DOWN = -Y}.
 */
public enum Face {
    UP(0, 1, 0),
    DOWN(0, -1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    EAST(1, 0, 0),
    WEST(-1, 0, 0);

    /** Unit normal of this face. */
    public final int dx;
    public final int dy;
    public final int dz;

    Face(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    /** The cell adjacent to {@code p} across this face. */
    public BlockPos from(BlockPos p) {
        return new BlockPos(p.x() + dx, p.y() + dy, p.z() + dz);
    }

    /**
     * The face whose normal is exactly {@code (dx,dy,dz)}, or {@code null} if it
     * is not a unit cardinal step. Handy for "I have a solid block at {@code n}
     * and want to fill the adjacent cell {@code c}": {@code ofDelta(c - n)}.
     */
    public static Face ofDelta(int dx, int dy, int dz) {
        for (Face f : values()) {
            if (f.dx == dx && f.dy == dy && f.dz == dz) {
                return f;
            }
        }
        return null;
    }
}
