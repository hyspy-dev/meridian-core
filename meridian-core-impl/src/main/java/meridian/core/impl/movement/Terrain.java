package meridian.core.impl.movement;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import meridian.core.impl.ChunkTracker;
import meridian.core.impl.WorldStateImpl;
import meridian.protocol.BlockMaterial;
import meridian.protocol.BlockType;

/**
 * The ground as the player's body meets it: the collision boxes of the blocks around a point, and
 * where the body stands or fits - the server's collision rule, read off core's mirror of the world.
 *
 * <p>A block stops the body when its material is solid (the server's {@code MATERIAL_SOLID}); its
 * boxes are its type's shape turned by its rotation, placed at its own cell. A cell a tall block
 * fills (a door's upper half) takes the boxes of the block it belongs to. A box can reach out of
 * its cell - a fence's up into the one above - so the cell below a body is looked at too.
 *
 * <p>Where core has not been sent the world, nothing fits: a body never plans into the unknown.
 */
public final class Terrain {

    /** Half the body's width and depth (the player's hitbox is 0.65 across). */
    static final double HALF = 0.325;
    /** The body's height. */
    static final double HEIGHT = 1.85;
    private static final double EPS = 1e-3;

    private final ChunkTracker chunks;
    private final WorldStateImpl world;
    private final BlockHitboxes hitboxes;

    public Terrain(ChunkTracker chunks, WorldStateImpl world, BlockHitboxes hitboxes) {
        this.chunks = chunks;
        this.world = world;
        this.hitboxes = hitboxes;
    }

    /** Whether core knows the blocks at a cell: its section, or its column with that section left out as air. */
    public boolean known(int x, int y, int z) {
        return chunks.blockIdAt(x, y, z) >= 0 || chunks.columnKnown(x, z);
    }

    /** The solid boxes of the block occupying a cell, in world coordinates, added to {@code out}. */
    void solids(int x, int y, int z, List<double[]> out) {
        int id = chunks.blockIdAt(x, y, z);
        if (id <= 0) {
            return;
        }
        int bx = x;
        int by = y;
        int bz = z;
        int filler = chunks.fillerAt(x, y, z);
        if (filler != 0) {
            // A cell a bigger block fills: the block's own cell holds the shape.
            bx = x - axis(filler);
            by = y - axis(filler >> 10);
            bz = z - axis(filler >> 5);
            int baseId = chunks.blockIdAt(bx, by, bz);
            if (baseId > 0) {
                id = baseId;
            }
        }
        BlockType type = world.blockTypeById(id);
        if (type == null || type.material != BlockMaterial.Solid) {
            return;
        }
        for (double[] b : hitboxes.boxes(type.hitbox, chunks.rotationAt(bx, by, bz))) {
            out.add(new double[] {bx + b[0], by + b[1], bz + b[2], bx + b[3], by + b[4], bz + b[5]});
        }
    }

    /** One axis of a packed filler offset: the low five bits, signed (as {@code Block.base()}). */
    private static int axis(int packed) {
        int v = packed & 0x1F;
        return (v & 0x10) != 0 ? v - 0x20 : v;
    }

    /**
     * Whether the body, centred at {@code (cx, cz)} with its feet at {@code feet} and {@code height}
     * tall, hits no solid box - and stands only where core knows the world.
     */
    public boolean fits(double cx, double feet, double cz, double height) {
        double x0 = cx - HALF + EPS;
        double x1 = cx + HALF - EPS;
        double z0 = cz - HALF + EPS;
        double z1 = cz + HALF - EPS;
        double y0 = feet + EPS;
        double y1 = feet + height - EPS;
        List<double[]> boxes = new ArrayList<>();
        for (int x = floor(x0); x <= floor(x1); x++) {
            for (int z = floor(z0); z <= floor(z1); z++) {
                for (int y = floor(y0) - 1; y <= floor(y1); y++) {
                    if (!known(x, y, z)) {
                        return false;
                    }
                    solids(x, y, z, boxes);
                }
            }
        }
        for (double[] b : boxes) {
            if (b[0] < x1 && b[3] > x0 && b[1] < y1 && b[4] > y0 && b[2] < z1 && b[5] > z0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Where the feet are when the body stands centred in cell {@code (x, y, z)}: on the highest
     * floor under the body whose top is within the cell's height ({@code [y, y+1)}: a full block
     * under it, a slab in it), with room for the whole body above and no fluid at the feet or the
     * head. Empty when there is no such floor, no room, or core does not know the place.
     */
    public OptionalDouble standAt(int x, int y, int z) {
        double cx = x + 0.5;
        double cz = z + 0.5;
        if (!known(x, y - 1, z) || !known(x, y, z)) {
            return OptionalDouble.empty();
        }
        List<double[]> boxes = new ArrayList<>();
        for (int bx = floor(cx - HALF); bx <= floor(cx + HALF); bx++) {
            for (int bz = floor(cz - HALF); bz <= floor(cz + HALF); bz++) {
                solids(bx, y - 1, bz, boxes);
                solids(bx, y, bz, boxes);
            }
        }
        double floor = Double.NaN;
        for (double[] b : boxes) {
            boolean under = b[0] < cx + HALF && b[3] > cx - HALF && b[2] < cz + HALF && b[5] > cz - HALF;
            if (under && b[4] >= y - EPS && b[4] < y + 1 - EPS && (Double.isNaN(floor) || b[4] > floor)) {
                floor = b[4];
            }
        }
        if (Double.isNaN(floor)) {
            return OptionalDouble.empty();
        }
        int feetCell = floor(floor + EPS);
        // Wading is standing: water at the feet only. A fluid at the head is swimming, which the
        // body does not do; anything that burns is never stood in.
        if (burns(x, feetCell, z) || chunks.fluidCellAt(x, feetCell + 1, z) != null) {
            return OptionalDouble.empty();
        }
        return fits(cx, floor, cz, HEIGHT) ? OptionalDouble.of(floor) : OptionalDouble.empty();
    }

    /** Whether a cell holds a fluid that hurts: lava and its kin. */
    private boolean burns(int x, int y, int z) {
        String fluid = chunks.fluidNameAt(x, y, z);
        return fluid != null && (fluid.contains("Lava") || fluid.contains("Fire") || fluid.contains("Tar")
                || fluid.contains("Poison") || fluid.contains("Slime"));
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
