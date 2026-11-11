package meridian.core.impl.movement;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves a box through the blocks: a displacement clipped against every solid box in its way, an
 * axis at a time - up and down first, then across - so the body slides along walls and lands on
 * floors. A cell core has not been sent stops the box as a wall does, the way the server treats a
 * block in a chunk it has not loaded.
 */
final class Collider {

    /** How close to a face counts as touching it. */
    static final double SKIN = 1e-7;

    private final Terrain terrain;

    Collider(Terrain terrain) {
        this.terrain = terrain;
    }

    /** What a move came to: how far it went, and which ways it was stopped. */
    record Moved(double dx, double dy, double dz, boolean hitX, boolean hitY, boolean hitZ) {
        boolean landed(double wantedDy) {
            return hitY && wantedDy < 0;
        }

        boolean ceiling(double wantedDy) {
            return hitY && wantedDy > 0;
        }

        boolean horizontal() {
            return hitX || hitZ;
        }
    }

    /**
     * The solid boxes that touch the region a box sweeps, with a box for every unknown cell in it.
     * Indices: minX, minY, minZ, maxX, maxY, maxZ.
     */
    List<double[]> boxesAround(double[] box, double dx, double dy, double dz) {
        double x0 = Math.min(box[0], box[0] + dx) - 1e-3;
        double y0 = Math.min(box[1], box[1] + dy) - 1e-3;
        double z0 = Math.min(box[2], box[2] + dz) - 1e-3;
        double x1 = Math.max(box[3], box[3] + dx) + 1e-3;
        double y1 = Math.max(box[4], box[4] + dy) + 1e-3;
        double z1 = Math.max(box[5], box[5] + dz) + 1e-3;
        List<double[]> out = new ArrayList<>();
        // One cell lower: a box can reach up out of its own cell (a fence).
        for (int x = floor(x0); x <= floor(x1); x++) {
            for (int z = floor(z0); z <= floor(z1); z++) {
                for (int y = floor(y0) - 1; y <= floor(y1); y++) {
                    if (!terrain.known(x, y, z)) {
                        out.add(new double[] {x, y, z, x + 1, y + 1, z + 1});
                    } else {
                        terrain.solids(x, y, z, out);
                    }
                }
            }
        }
        return out;
    }

    /** Moves {@code box} (changed in place) by up to (dx, dy, dz) against {@code boxes}. */
    static Moved move(double[] box, double dx, double dy, double dz, List<double[]> boxes) {
        double wy = dy;
        double wx = dx;
        double wz = dz;
        for (double[] b : boxes) {
            dy = clipY(b, box, dy);
        }
        box[1] += dy;
        box[4] += dy;
        for (double[] b : boxes) {
            dx = clipX(b, box, dx);
        }
        box[0] += dx;
        box[3] += dx;
        for (double[] b : boxes) {
            dz = clipZ(b, box, dz);
        }
        box[2] += dz;
        box[5] += dz;
        return new Moved(dx, dy, dz, dx != wx, dy != wy, dz != wz);
    }

    /** How far {@code box} moves along y before {@code b} stops it. */
    static double clipY(double[] b, double[] box, double d) {
        if (b[3] <= box[0] + SKIN || b[0] >= box[3] - SKIN || b[5] <= box[2] + SKIN || b[2] >= box[5] - SKIN) {
            return d;
        }
        if (d > 0 && b[1] >= box[4] - SKIN) {
            return Math.min(d, Math.max(0, b[1] - box[4]));
        }
        if (d < 0 && b[4] <= box[1] + SKIN) {
            return Math.max(d, Math.min(0, b[4] - box[1]));
        }
        return d;
    }

    static double clipX(double[] b, double[] box, double d) {
        if (b[4] <= box[1] + SKIN || b[1] >= box[4] - SKIN || b[5] <= box[2] + SKIN || b[2] >= box[5] - SKIN) {
            return d;
        }
        if (d > 0 && b[0] >= box[3] - SKIN) {
            return Math.min(d, Math.max(0, b[0] - box[3]));
        }
        if (d < 0 && b[3] <= box[0] + SKIN) {
            return Math.max(d, Math.min(0, b[3] - box[0]));
        }
        return d;
    }

    static double clipZ(double[] b, double[] box, double d) {
        if (b[4] <= box[1] + SKIN || b[1] >= box[4] - SKIN || b[3] <= box[0] + SKIN || b[0] >= box[3] - SKIN) {
            return d;
        }
        if (d > 0 && b[2] >= box[5] - SKIN) {
            return Math.min(d, Math.max(0, b[2] - box[5]));
        }
        if (d < 0 && b[5] <= box[2] + SKIN) {
            return Math.max(d, Math.min(0, b[5] - box[2]));
        }
        return d;
    }

    /** Whether any of {@code boxes} overlaps {@code box}. */
    static boolean overlaps(double[] box, List<double[]> boxes) {
        for (double[] b : boxes) {
            if (b[0] < box[3] - SKIN && b[3] > box[0] + SKIN && b[1] < box[4] - SKIN && b[4] > box[1] + SKIN
                    && b[2] < box[5] - SKIN && b[5] > box[2] + SKIN) {
                return true;
            }
        }
        return false;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
