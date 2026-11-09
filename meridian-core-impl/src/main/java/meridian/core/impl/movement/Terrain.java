package meridian.core.impl.movement;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import meridian.core.api.FluidCell;
import meridian.core.impl.ChunkTracker;
import meridian.core.impl.WorldStateImpl;
import meridian.protocol.BlockMaterial;
import meridian.protocol.BlockMovementSettings;
import meridian.protocol.BlockType;

/**
 * The ground as the player's body meets it: the collision boxes of the blocks around a point, what
 * the block under the feet and the blocks about the body do to movement, fluids, and where the
 * body stands or fits - the server's collision rule, read off core's mirror of the world.
 *
 * <p>A block stops the body when its material is solid (the server's {@code MATERIAL_SOLID}); its
 * boxes are its type's shape turned by its rotation, placed at its own cell. A cell a tall block
 * fills (a door's upper half) takes the boxes of the block it belongs to. A box can reach out of
 * its cell - a fence's up into the one above - so the cell below a body is looked at too.
 *
 * <p>Where core has not been sent the world, nothing fits: a body never plans into the unknown.
 */
public final class Terrain {

    /** Half the player's default width and depth (the hitbox is 0.65 across), for planning. */
    static final double HALF = 0.325;
    /** The player's default height, for planning. */
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

    /** A block where it stands: its id, its type and the cell holding its shape (its base, for a filler). */
    record Placed(int id, BlockType type, int x, int y, int z) {}

    /** The block occupying a cell, or {@code null} for air or the unknown. */
    Placed blockAt(int x, int y, int z) {
        int id = chunks.blockIdAt(x, y, z);
        if (id <= 0) {
            return null;
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
        return type == null ? null : new Placed(id, type, bx, by, bz);
    }

    /** A block's boxes in world coordinates, whatever its material. */
    void boxesOf(Placed b, List<double[]> out) {
        for (double[] h : hitboxes.boxes(b.type().hitbox, chunks.rotationAt(b.x(), b.y(), b.z()))) {
            out.add(new double[] {b.x() + h[0], b.y() + h[1], b.z() + h[2], b.x() + h[3], b.y() + h[4], b.z() + h[5]});
        }
    }

    /** The solid boxes of the block occupying a cell, in world coordinates, added to {@code out}. */
    void solids(int x, int y, int z, List<double[]> out) {
        Placed b = blockAt(x, y, z);
        if (b == null || b.type().material != BlockMaterial.Solid) {
            return;
        }
        boxesOf(b, out);
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
        return fits(cx, feet, cz, HALF, HALF, height);
    }

    boolean fits(double cx, double feet, double cz, double hx, double hz, double height) {
        double x0 = cx - hx + EPS;
        double x1 = cx + hx - EPS;
        double z0 = cz - hz + EPS;
        double z1 = cz + hz - EPS;
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
     * under it, a slab in it), with room for the whole body above and no fluid at the head.
     * Empty when there is no such floor, no room, or core does not know the place.
     */
    public OptionalDouble standAt(int x, int y, int z) {
        double cx = x + 0.5;
        double cz = z + 0.5;
        if (!known(x, y - 1, z) || !known(x, y, z)) {
            return OptionalDouble.empty();
        }
        double floor = floorTop(cx, cz, HALF, HALF, y - EPS, y + 1 - EPS);
        if (Double.isNaN(floor)) {
            return OptionalDouble.empty();
        }
        int feetCell = floor(floor + EPS);
        // Wading is standing: water at the feet only. A fluid at the head is swimming; anything
        // that burns is never stood in.
        if (burns(x, feetCell, z) || chunks.fluidCellAt(x, feetCell + 1, z) != null) {
            return OptionalDouble.empty();
        }
        return fits(cx, floor, cz, HEIGHT) ? OptionalDouble.of(floor) : OptionalDouble.empty();
    }

    /** The highest solid top under a footprint within [lo, hi), or NaN. */
    double floorTop(double cx, double cz, double hx, double hz, double lo, double hi) {
        List<double[]> boxes = new ArrayList<>();
        for (int bx = floor(cx - hx); bx <= floor(cx + hx); bx++) {
            for (int bz = floor(cz - hz); bz <= floor(cz + hz); bz++) {
                for (int by = floor(lo) - 1; by <= floor(hi); by++) {
                    solids(bx, by, bz, boxes);
                }
            }
        }
        double floor = Double.NaN;
        for (double[] b : boxes) {
            boolean under = b[0] < cx + hx && b[3] > cx - hx && b[2] < cz + hz && b[5] > cz - hz;
            if (under && b[4] >= lo && b[4] < hi && (Double.isNaN(floor) || b[4] > floor)) {
                floor = b[4];
            }
        }
        return floor;
    }

    /** Whether a cell holds a fluid that hurts: lava and its kin. */
    boolean burns(int x, int y, int z) {
        String fluid = chunks.fluidNameAt(x, y, z);
        return fluid != null && (fluid.contains("Lava") || fluid.contains("Fire") || fluid.contains("Tar")
                || fluid.contains("Poison") || fluid.contains("Slime"));
    }

    /** The fluid in a cell, or {@code null}. */
    FluidCell fluidAt(int x, int y, int z) {
        return chunks.fluidCellAt(x, y, z);
    }

    // ------------------------------------------------------------------
    // What the place does to the body
    // ------------------------------------------------------------------

    /** The movement settings of the block the feet stand on, or {@code null} when it has none. */
    BlockMovementSettings groundSettings(double x, double feet, double z, double hx, double hz) {
        BlockMovementSettings best = null;
        double bestDist = Double.MAX_VALUE;
        List<double[]> boxes = new ArrayList<>();
        for (int bx = floor(x - hx); bx <= floor(x + hx); bx++) {
            for (int bz = floor(z - hz); bz <= floor(z + hz); bz++) {
                for (int by = floor(feet - 0.05) - 1; by <= floor(feet); by++) {
                    Placed b = blockAt(bx, by, bz);
                    if (b == null || b.type().material != BlockMaterial.Solid) {
                        continue;
                    }
                    boxes.clear();
                    boxesOf(b, boxes);
                    for (double[] o : boxes) {
                        boolean under = o[0] < x + hx && o[3] > x - hx && o[2] < z + hz && o[5] > z - hz;
                        if (under && Math.abs(o[4] - feet) < 0.05) {
                            double d = Math.hypot((o[0] + o[3]) / 2 - x, (o[2] + o[5]) / 2 - z);
                            if (d < bestDist) {
                                bestDist = d;
                                best = b.type().movementSettings;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    /** What the blocks the body is in or holds on to do: webs slow it, ladders and vines are climbed. */
    /**
     * @param climbNx the way into the climbable, on the ground plane (one axis), and z
     * @param climbTop the top of the climbable the body holds
     */
    record Inside(double speedMul, double jumpMul, double terminalMul, boolean climbable, double climbUp,
                  double climbDown, double climbLateral, double climbNx, double climbNz, double climbTop) {}

    Inside inside(double x, double feet, double z, double hx, double hz, double height, double reach) {
        double speed = 1;
        double jump = 1;
        double terminal = 1;
        boolean climb = false;
        double up = 1;
        double down = 1;
        double lateral = 1;
        double nx = 0;
        double nz = 0;
        double top = Double.NEGATIVE_INFINITY;
        double[] body = {x - hx, feet, z - hz, x + hx, feet + height, z + hz};
        double[] near = {x - hx - reach, feet, z - hz - reach, x + hx + reach, feet + height, z + hz + reach};
        List<double[]> boxes = new ArrayList<>();
        for (int bx = floor(near[0]); bx <= floor(near[3]); bx++) {
            for (int bz = floor(near[2]); bz <= floor(near[5]); bz++) {
                for (int by = floor(near[1]); by <= floor(near[4]); by++) {
                    Placed b = blockAt(bx, by, bz);
                    if (b == null) {
                        continue;
                    }
                    BlockMovementSettings m = b.type().movementSettings;
                    if (m == null) {
                        continue;
                    }
                    boolean solid = b.type().material == BlockMaterial.Solid;
                    if (!m.isClimbable && solid) {
                        continue;
                    }
                    boxes.clear();
                    boxesOf(b, boxes);
                    if (boxes.isEmpty()) {
                        boxes.add(new double[] {b.x(), b.y(), b.z(), b.x() + 1, b.y() + 1, b.z() + 1});
                    }
                    boolean in = false;
                    boolean touching = false;
                    for (double[] o : boxes) {
                        if (overlap(o, body)) {
                            in = true;
                        }
                        if (overlap(o, near)) {
                            touching = true;
                        }
                    }
                    // Held only with the body's middle over the climbable's own column (recorded: a
                    // gap of 0.077 with the centre just outside did not climb).
                    boolean column = (int) Math.floor(x) == b.x() && (int) Math.floor(z) == b.z();
                    if (m.isClimbable && touching && column) {
                        climb = true;
                        up = positive(m.climbUpSpeedMultiplier);
                        down = positive(m.climbDownSpeedMultiplier);
                        lateral = positive(m.climbLateralSpeedMultiplier);
                        double[] o = boxes.get(0);
                        double ox = (o[0] + o[3]) / 2 - x;
                        double oz = (o[2] + o[5]) / 2 - z;
                        if (Math.abs(ox) >= Math.abs(oz)) {
                            nx = Math.signum(ox);
                            nz = 0;
                        } else {
                            nx = 0;
                            nz = Math.signum(oz);
                        }
                        for (double[] q : boxes) {
                            top = Math.max(top, q[4]);
                        }
                    }
                    if (in && !solid) {
                        speed *= positive(m.horizontalSpeedMultiplier);
                        jump *= positive(m.jumpForceMultiplier);
                        terminal *= positive(m.terminalVelocityModifier);
                    }
                }
            }
        }
        return new Inside(speed, jump, terminal, climb, up, down, lateral, nx, nz, top);
    }

    private static double positive(float v) {
        return v > 0 ? v : 1;
    }

    private static boolean overlap(double[] a, double[] b) {
        return a[0] < b[3] && a[3] > b[0] && a[1] < b[4] && a[4] > b[1] && a[2] < b[5] && a[5] > b[2];
    }

    /** The fluid a body stands in and how far up it the fluid reaches. */
    record Wet(Surroundings.FluidMotion motion, double depth) {}

    Wet wet(double x, double feet, double z, double hx, double hz, double height, Surroundings around) {
        int cx = floor(x);
        int cz = floor(z);
        Surroundings.FluidMotion motion = null;
        double surface = Double.NaN;
        for (int y = floor(feet); y <= floor(feet + height); y++) {
            FluidCell f = fluidAt(cx, y, cz);
            if (f == null) {
                if (!Double.isNaN(surface)) {
                    break;
                }
                continue;
            }
            Surroundings.FluidMotion m = around.fluid(f.id());
            if (m == null) {
                continue;
            }
            motion = m;
            int max = m.maxLevel();
            double fill = f.level() <= 0 || max <= 1 ? 1 : Math.min(1.0, (double) f.level() / max);
            // A cell with fluid above it is full.
            FluidCell above = fluidAt(cx, y + 1, cz);
            surface = above != null ? y + 1 : y + fill;
        }
        if (motion == null || Double.isNaN(surface) || surface <= feet) {
            return null;
        }
        return new Wet(motion, Math.min(height, surface - feet));
    }

    /** Whether the block the body would step onto lets it step up by itself. */
    boolean autoStepAllowed(double x, double feet, double z, double hx, double hz) {
        BlockMovementSettings m = groundSettings(x, feet, z, hx, hz);
        return m == null || !m.disableAutoStep;
    }

    /**
     * The top of the wall straight ahead (direction (dx, dz), one of the axes) when it is between
     * {@code lo} and {@code hi}, close enough to hold, and the body has room on it: the ledge.
     */
    Double ledgeTop(double x, double z, double dx, double dz, double hx, double hz, double lo, double hi,
                    double reach, double height) {
        double half = dx != 0 ? hx : hz;
        double px = x + dx * (half + reach);
        double pz = z + dz * (half + reach);
        double top = floorTop(px, pz, dx != 0 ? reach : hx * 0.5, dz != 0 ? reach : hz * 0.5, lo, hi);
        if (Double.isNaN(top)) {
            return null;
        }
        // Room above the ledge where the body ends up, and nothing over the hands.
        double sx = x + dx * (half * 2 + 0.05);
        double sz = z + dz * (half * 2 + 0.05);
        if (!fits(sx, top, sz, hx, hz, height)) {
            return null;
        }
        if (!fits(x, top - 0.1, z, hx, hz, 0.1 + 0.3)) {
            return null;
        }
        return top;
    }

    /** Whether the body stands at (x, feet, z): a floor right there, and room. */
    boolean standsOn(double x, double feet, double z, double hx, double hz, double height) {
        double top = floorTop(x, z, hx, hz, feet - 0.05, feet + 0.05);
        return !Double.isNaN(top) && fits(x, top, z, hx, hz, height);
    }

    /**
     * The door in a cell: a block whose states include opening ({@code OpenDoorIn},
     * {@code OpenDoorOut}), open when it is in one of those states.
     */
    meridian.core.api.Movement.Door door(Placed b) {
        if (b == null || b.type().states == null) {
            return meridian.core.api.Movement.Door.NONE;
        }
        Integer in = b.type().states.get("OpenDoorIn");
        Integer out = b.type().states.get("OpenDoorOut");
        if (in == null && out == null) {
            return meridian.core.api.Movement.Door.NONE;
        }
        boolean open = (in != null && in == b.id()) || (out != null && out == b.id());
        return open ? meridian.core.api.Movement.Door.OPEN : meridian.core.api.Movement.Door.CLOSED;
    }

    /** Whether a block at a cell can be climbed (a ladder, a vine, a rope). */
    public boolean climbable(int x, int y, int z) {
        Placed b = blockAt(x, y, z);
        return b != null && b.type().movementSettings != null && b.type().movementSettings.isClimbable;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
