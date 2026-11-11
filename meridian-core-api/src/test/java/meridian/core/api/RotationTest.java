package meridian.core.api;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link Rotation} against a second derivation of the same facings: the matrix of an orientation
 * rebuilt by turning the unit vectors the way the server's {@code Rotation.applyRotationTo} does
 * (roll about z, then pitch about x, then yaw about y), rather than from the closed form.
 *
 * <p>A plain test class (no framework on hand offline): every {@code test*} method runs, and an
 * {@link AssertionError} fails it.
 */
public class RotationTest {

    public void testNumberRoundTrips() {
        for (int i = 0; i < 64; i++) {
            check(Rotation.of(Rotation.yaw(i), Rotation.pitch(i), Rotation.roll(i)) == i, "of(yaw, pitch, roll) of " + i);
        }
        check(Rotation.of(1, 1, 0) == 5, "a log lying east-west is 5");
        check(Rotation.of(4, 5, 6) == Rotation.of(0, 1, 2), "quarters are taken modulo 4");
    }

    public void testCanonicalKeepsTheFacing() {
        Set<Integer> forms = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            int c = Rotation.canonical(i);
            check(same(facing(c), facing(i)), "canonical(" + i + ") = " + c + " faces otherwise");
            check(Rotation.canonical(c) == c, "canonical of canonical " + c);
            check(Rotation.same(i, c), "same(" + i + ", canonical)");
            forms.add(c);
        }
        check(forms.size() == 24, "64 numbers are 24 facings, found " + forms.size());
        for (int a = 0; a < 64; a++) {
            for (int b = 0; b < 64; b++) {
                check(Rotation.same(a, b) == same(facing(a), facing(b)), "same(" + a + ", " + b + ")");
            }
        }
    }

    public void testRotateTurnsTheFacing() {
        for (int axis = Rotation.X; axis <= Rotation.Z; axis++) {
            for (int i = 0; i < 64; i++) {
                int r = Rotation.rotate(i, axis);
                check(same(facing(r), multiply(quarter(axis), facing(i))), "rotate(" + i + ", " + axis + ")");
                check(Rotation.canonical(r) == r, "rotate gives a canonical number");
                check(Rotation.rotate(i, axis, 4) == Rotation.canonical(i), "four quarter turns");
                check(Rotation.rotate(Rotation.rotate(i, axis), axis, -1) == Rotation.canonical(i), "a turn back");
            }
        }
    }

    public void testMirrorFoldsLikeTheServer() {
        int[][] symmetric = {{-1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int axis = Rotation.X; axis <= Rotation.Z; axis++) {
            int[][] flip = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
            flip[axis][axis] = -1;
            for (int i = 0; i < 64; i++) {
                int m = Rotation.mirror(i, axis);
                check(same(facing(m), multiply(multiply(flip, facing(i)), symmetric)), "mirror(" + i + ", " + axis + ")");
                check(Rotation.mirror(m, axis) == Rotation.canonical(i), "mirrored twice");
            }
        }
    }

    public void testEveryFlipTypeUndoesItself() {
        for (Rotation.Flip flip : Rotation.Flip.values()) {
            for (int axis = Rotation.X; axis <= Rotation.Z; axis++) {
                for (int i = 0; i < 64; i++) {
                    int m = Rotation.mirror(i, axis, flip);
                    check(Rotation.mirror(m, axis, flip) == Rotation.canonical(i), flip + " mirrored twice, " + i);
                }
            }
        }
        // A corner is the one a symmetric fold gets wrong: a quarter turn of yaw apart.
        check(Rotation.mirror(0, Rotation.X, Rotation.Flip.ORTHOGONAL)
                != Rotation.mirror(0, Rotation.X), "a corner mirrors otherwise than a wall");
    }

    // ------------------------------------------------------------------

    /** The orientation's matrix, its columns the unit vectors turned roll, then pitch, then yaw. */
    private static int[][] facing(int index) {
        int[][] m = new int[3][3];
        for (int col = 0; col < 3; col++) {
            int[] v = new int[3];
            v[col] = 1;
            for (int k = 0; k < Rotation.roll(index); k++) v = new int[] {-v[1], v[0], v[2]};   // rotateZ(Ninety)
            for (int k = 0; k < Rotation.pitch(index); k++) v = new int[] {v[0], -v[2], v[1]};  // rotateX(Ninety)
            for (int k = 0; k < Rotation.yaw(index); k++) v = new int[] {v[2], v[1], -v[0]};    // rotateY(Ninety)
            for (int row = 0; row < 3; row++) m[row][col] = v[row];
        }
        return m;
    }

    /** {@code Axis.rotate} by a quarter turn, as a matrix: the turned unit vectors. */
    private static int[][] quarter(int axis) {
        int[][] m = new int[3][3];
        for (int col = 0; col < 3; col++) {
            int[] v = new int[3];
            v[col] = 1;
            v = switch (axis) {
                case Rotation.X -> new int[] {v[0], -v[2], v[1]};
                case Rotation.Y -> new int[] {v[2], v[1], -v[0]};
                default -> new int[] {-v[1], v[0], v[2]};
            };
            for (int row = 0; row < 3; row++) m[row][col] = v[row];
        }
        return m;
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

    private static boolean same(int[][] a, int[][] b) {
        return java.util.Arrays.deepEquals(a, b);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
