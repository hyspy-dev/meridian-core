package meridian.core.api;

/**
 * Which way a block faces, as the server keeps it, and what turning or mirroring does to that.
 *
 * <p>The server stores a block's orientation as one number, 0..63: {@code roll * 16 + pitch * 4 +
 * yaw}, each a quarter turn ({@code 0} none, {@code 1} 90°, {@code 2} 180°, {@code 3} 270°) - its
 * {@code RotationTuple.index}. Under the number is the rotation matrix {@code Ry(yaw) * Rx(pitch) *
 * Rz(roll)}, all of whose entries are 0 or ±1, so everything here is exact integer arithmetic. The
 * maths is the server's own ({@code RotationTuple}, {@code BlockRotationUtil}), carried over step
 * for step: a block turned here has to come out the way the server would turn it.
 *
 * <p>The 64 numbers describe only 24 orientations - several mean the same facing - so two blocks
 * are compared by {@link #canonical}, or by {@link #same}, never by the raw number. Every result
 * this class hands back is already canonical, as the server's are.
 *
 * <p>Pure: no state, no session; the same answers on any thread.
 */
public final class Rotation {

    /** The axes, numbered as everywhere in core: {@code x}, {@code y} (up), {@code z}. */
    public static final int X = 0, Y = 1, Z = 2;

    /** The unturned orientation ({@code RotationTuple.NONE_INDEX}). */
    public static final int NONE = 0;

    private Rotation() {
    }

    // ------------------------------------------------------------------
    // The number
    // ------------------------------------------------------------------

    /**
     * The number of an orientation from its three quarter turns, each taken modulo 4 - the
     * server's {@code RotationTuple.index(yaw, pitch, roll)}.
     */
    public static int of(int yaw, int pitch, int roll) {
        return (roll & 3) * 16 + (pitch & 3) * 4 + (yaw & 3);
    }

    /** The quarter turns about {@code y} in an orientation number, 0..3. */
    public static int yaw(int index) {
        return index & 3;
    }

    /** The quarter turns about {@code x} in an orientation number, 0..3. */
    public static int pitch(int index) {
        return (index >> 2) & 3;
    }

    /** The quarter turns about {@code z} in an orientation number, 0..3. */
    public static int roll(int index) {
        return (index >> 4) & 3;
    }

    // ------------------------------------------------------------------
    // Comparing
    // ------------------------------------------------------------------

    /**
     * The one number for this orientation that every number meaning the same facing maps to - what
     * the server's own decomposition of the matrix gives back.
     */
    public static int canonical(int index) {
        return toIndex(toMatrix(index));
    }

    /** Whether two orientation numbers describe the same facing. */
    public static boolean same(int a, int b) {
        return a == b || canonical(a) == canonical(b);
    }

    // ------------------------------------------------------------------
    // Turning and mirroring
    // ------------------------------------------------------------------

    /**
     * A block's orientation after whatever it sits in is turned a quarter turn about {@code axis},
     * the way positions turn ({@code Axis.rotate} on the server): the server's
     * {@code RotationTuple.composeOnAxis(axis, Ninety)}, which its selection rotate applies to
     * every block.
     */
    public static int rotate(int index, int axis) {
        return toIndex(multiply(quarterTurn(axis), toMatrix(index)));
    }

    /** {@link #rotate(int, int)} {@code quarters} times; negative quarters turn the other way. */
    public static int rotate(int index, int axis, int quarters) {
        int turns = Math.floorMod(quarters, 4);
        int r = canonical(index);
        for (int i = 0; i < turns; i++) {
            r = rotate(r, axis);
        }
        return r;
    }

    /**
     * How a block folds a mirror back into a rotation - the server's {@code BlockFlipType}, which
     * a block's asset declares ({@code "FlipType"}) and which does not reach the client.
     */
    public enum Flip {
        /** Corner pieces and the like, left-handed: a mirror is a quarter turn of yaw. */
        ORTHOGONAL(new int[][] {{0, 0, 1}, {0, 1, 0}, {1, 0, 0}}),
        /** Their right-handed twins: the quarter turn the other way. */
        ORTHOGONAL_INVERSE(new int[][] {{0, 0, -1}, {0, 1, 0}, {-1, 0, 0}}),
        /** Everything else, and what a block is unless its asset says otherwise. */
        SYMMETRIC(new int[][] {{-1, 0, 0}, {0, 1, 0}, {0, 0, 1}});

        /** {@code BlockRotationUtil.LOCAL_FLIP_CORRECTIONS}: the fold, in the block's own frame. */
        private final int[][] correction;

        Flip(int[][] correction) {
            this.correction = correction;
        }
    }

    /**
     * A block's orientation after whatever it sits in is mirrored across the plane perpendicular to
     * {@code axis} ({@code Axis.flip} on the server), for a {@link Flip#SYMMETRIC} block - the
     * default, and the only answer without the block's asset at hand: the client is never told a
     * block's flip type, and many corner and roof pieces declare another.
     */
    public static int mirror(int index, int axis) {
        return mirror(index, axis, Flip.SYMMETRIC);
    }

    /**
     * A block's orientation after a mirror across the plane perpendicular to {@code axis}, folded
     * back into a rotation the way the server does for a block of this flip type
     * ({@code BlockRotationUtil.getFlipped}): the matrix's {@code axis} row negated, then the
     * block's own correction applied. Mirroring twice gives the orientation back.
     */
    public static int mirror(int index, int axis, Flip flip) {
        checkAxis(axis);
        int[][] m = toMatrix(index);
        for (int i = 0; i < 3; i++) {
            m[axis][i] = -m[axis][i];
        }
        return toIndex(multiply(m, flip.correction));
    }

    // ------------------------------------------------------------------

    private static void checkAxis(int axis) {
        if (axis < X || axis > Z) {
            throw new IllegalArgumentException("axis " + axis);
        }
    }

    /** The server's {@code axisRotationMatrix(axis, Ninety)}. */
    private static int[][] quarterTurn(int axis) {
        return switch (axis) {
            case X -> new int[][] {{1, 0, 0}, {0, 0, -1}, {0, 1, 0}};
            case Y -> new int[][] {{0, 0, 1}, {0, 1, 0}, {-1, 0, 0}};
            case Z -> new int[][] {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}};
            default -> throw new IllegalArgumentException("axis " + axis);
        };
    }

    /** The server's {@code eulerToMatrix}: {@code Ry(yaw) * Rx(pitch) * Rz(roll)}. */
    private static int[][] toMatrix(int index) {
        int yaw = yaw(index), pitch = pitch(index), roll = roll(index);
        int cy = cos(yaw), sy = sin(yaw);
        int cp = cos(pitch), sp = sin(pitch);
        int cr = cos(roll), sr = sin(roll);
        return new int[][] {
            { cy * cr + sy * sp * sr, -cy * sr + sy * sp * cr,  sy * cp},
            {                cp * sr,                 cp * cr,      -sp},
            {-sy * cr + cy * sp * sr,  sy * sr + cy * sp * cr,  cy * cp}
        };
    }

    /**
     * The server's {@code matrixToRotationTuple}: pitch from the sine alone (so never a half turn),
     * and at gimbal lock (pitch ±90°) the roll folded into the yaw.
     */
    private static int toIndex(int[][] m) {
        int sp = -m[1][2];
        int pitch = switch (sp) {
            case 0 -> 0;
            case 1 -> 1;
            case -1 -> 3;
            default -> throw new IllegalArgumentException("not a quarter turn: sin=" + sp);
        };
        int yaw;
        int roll;
        if (sp != 1 && sp != -1) {
            yaw = quarter(m[0][2], m[2][2]);
            roll = quarter(m[1][0], m[1][1]);
        } else {
            yaw = quarter(-m[2][0], m[0][0]);
            roll = 0;
        }
        return of(yaw, pitch, roll);
    }

    /** The quarter turn with this sine and cosine - the server's {@code atan2_90}. */
    private static int quarter(int sin, int cos) {
        if (sin == 0 && cos == 1) return 0;
        if (sin == 1 && cos == 0) return 1;
        if (sin == 0 && cos == -1) return 2;
        if (sin == -1 && cos == 0) return 3;
        throw new IllegalArgumentException("not a quarter turn: sin=" + sin + " cos=" + cos);
    }

    private static int cos(int q) {
        return q == 0 ? 1 : q == 2 ? -1 : 0;
    }

    private static int sin(int q) {
        return q == 1 ? 1 : q == 3 ? -1 : 0;
    }

    private static int[][] multiply(int[][] a, int[][] b) {
        int[][] r = new int[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }
}
