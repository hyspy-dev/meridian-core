package meridian.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * Blocks and fluids relative to an anchor - what the paste tool holds, what a prefab resolves to -
 * and what a quarter turn or a mirror does to them.
 *
 * <p>The maths is the server's, as its selection turns and mirrors ({@code BlockSelection.rotate},
 * {@code BlockSelection.flip}): every position about the anchor by {@code Axis.rotate} / {@code
 * Axis.flip}, every block's facing by {@link Rotation#rotate} / {@link Rotation#mirror}. A mirror
 * needs each block's {@link BlockView#flip()}, which the caller hands in by id. Fluids move with
 * the blocks and have no facing.
 *
 * <p>Immutable: the lists are copies, and every method hands back a new clipboard. Axes are
 * {@link Rotation#X}, {@link Rotation#Y}, {@link Rotation#Z}.
 */
public record Clipboard(List<PastePreview.Change> blocks, List<PastePreview.FluidChange> fluids) {

    /** Nothing at all. */
    public static final Clipboard EMPTY = new Clipboard(List.of(), List.of());

    public Clipboard {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        fluids = fluids == null ? List.of() : List.copyOf(fluids);
    }

    /** Whether it holds no block - fluids alone do not make a clipboard. */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * Turned {@code quarters} quarter turns about {@code axis} through the anchor (negative turns
     * the other way), every block's facing with it - the server's {@code RotateBlock: All}.
     */
    public Clipboard turned(int axis, int quarters) {
        return turned(axis, quarters, id -> true);
    }

    /**
     * {@link #turned(int, int)}, turning the facing only of the blocks {@code turnsFacing} says - the
     * paste tool's other {@code RotateBlock} modes keep some blocks facing as they were while their
     * places turn.
     */
    public Clipboard turned(int axis, int quarters, IntPredicate turnsFacing) {
        int turns = Math.floorMod(quarters, 4);
        if (turns == 0) {
            return this;
        }
        List<PastePreview.Change> b = new ArrayList<>(blocks.size());
        for (PastePreview.Change c : blocks) {
            int[] p = turn(axis, turns, c.dx(), c.dy(), c.dz());
            int rotation = turnsFacing.test(c.blockId())
                    ? Rotation.rotate(c.rotation(), axis, turns) : c.rotation();
            b.add(new PastePreview.Change(p[0], p[1], p[2], c.blockId(), rotation));
        }
        List<PastePreview.FluidChange> f = new ArrayList<>(fluids.size());
        for (PastePreview.FluidChange c : fluids) {
            int[] p = turn(axis, turns, c.dx(), c.dy(), c.dz());
            f.add(new PastePreview.FluidChange(p[0], p[1], p[2], c.fluidId(), c.level()));
        }
        return new Clipboard(b, f);
    }

    /**
     * Mirrored across the plane through the anchor perpendicular to {@code axis}, each block's facing
     * folded back by its flip type ({@code flipOf} by block id - {@link BlockView#flip()}; {@code
     * null} for a block it does not know, taken as {@link Rotation.Flip#SYMMETRIC}).
     */
    public Clipboard flipped(int axis, IntFunction<Rotation.Flip> flipOf) {
        checkAxis(axis);
        List<PastePreview.Change> b = new ArrayList<>(blocks.size());
        for (PastePreview.Change c : blocks) {
            Rotation.Flip flip = flipOf == null ? null : flipOf.apply(c.blockId());
            b.add(new PastePreview.Change(axis == Rotation.X ? -c.dx() : c.dx(), axis == Rotation.Y ? -c.dy() : c.dy(),
                    axis == Rotation.Z ? -c.dz() : c.dz(), c.blockId(),
                    Rotation.mirror(c.rotation(), axis, flip == null ? Rotation.Flip.SYMMETRIC : flip)));
        }
        List<PastePreview.FluidChange> f = new ArrayList<>(fluids.size());
        for (PastePreview.FluidChange c : fluids) {
            f.add(new PastePreview.FluidChange(axis == Rotation.X ? -c.dx() : c.dx(),
                    axis == Rotation.Y ? -c.dy() : c.dy(), axis == Rotation.Z ? -c.dz() : c.dz(),
                    c.fluidId(), c.level()));
        }
        return new Clipboard(b, f);
    }

    /** The server's {@code Axis.rotate}, {@code turns} times. */
    private static int[] turn(int axis, int turns, int x, int y, int z) {
        checkAxis(axis);
        for (int i = 0; i < turns; i++) {
            int nx, ny, nz;
            switch (axis) {
                case Rotation.X -> { nx = x; ny = -z; nz = y; }
                case Rotation.Y -> { nx = z; ny = y; nz = -x; }
                default -> { nx = -y; ny = x; nz = z; }
            }
            x = nx;
            y = ny;
            z = nz;
        }
        return new int[] {x, y, z};
    }

    private static void checkAxis(int axis) {
        if (axis < Rotation.X || axis > Rotation.Z) {
            throw new IllegalArgumentException("axis " + axis);
        }
    }
}
