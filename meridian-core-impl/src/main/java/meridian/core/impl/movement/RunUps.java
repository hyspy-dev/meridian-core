package meridian.core.impl.movement;

/**
 * How a sprinting jump off an edge flies after a run-up of so many blocks from a standstill - worked
 * out by the body's own laws on open flat ground (the ground's: the asked-for speed rising, the
 * velocity following it, the sprint easing in; the air's after the take-off), the way
 * {@link BodySettings} works out the rest of what the body can do.
 *
 * <p>For each run-up, the flight tick by tick: where the middle of the body is past the edge, and
 * the feet over the edge's top. A path is planned with it: whether a leap comes down on the far side,
 * or meets its wall low enough for the hands to take the ledge.
 */
public final class RunUps {

    /** The longest run-up worked out, blocks. */
    public static final int MOST = 4;

    private final double[][] xs;
    private final double[][] ys;
    private final double halfWidth;

    RunUps(double[][] xs, double[][] ys, double halfWidth) {
        this.xs = xs;
        this.ys = ys;
        this.halfWidth = halfWidth;
    }

    /** The longest run-up there is a flight for. */
    public int most() {
        return xs.length - 1;
    }

    /**
     * How far past the edge the front of the body is when the feet come down to {@code rise} over the
     * edge's top, after a run-up of {@code blocks} - {@code NaN} when the jump never gets that high.
     */
    public double frontDownTo(int blocks, double rise) {
        double[] x = xs[clamp(blocks)];
        double[] y = ys[clamp(blocks)];
        int top = 0;
        for (int i = 1; i < y.length; i++) {
            if (y[i] > y[top]) {
                top = i;
            }
        }
        if (y[top] < rise) {
            return Double.NaN;
        }
        for (int i = top + 1; i < y.length; i++) {
            if (y[i] <= rise) {
                double t = (y[i - 1] - rise) / Math.max(1e-9, y[i - 1] - y[i]);
                return x[i - 1] + (x[i] - x[i - 1]) * t + halfWidth;
            }
        }
        return x[x.length - 1] + halfWidth;
    }

    /**
     * Where the feet are, over the edge's top, when the front of the body is {@code front} past the
     * edge, after a run-up of {@code blocks} - {@code NaN} when the flight is down before it gets
     * there.
     */
    public double feetWhenFrontAt(int blocks, double front) {
        double[] x = xs[clamp(blocks)];
        double[] y = ys[clamp(blocks)];
        double mid = front - halfWidth;
        if (mid <= x[0]) {
            return y[0];
        }
        for (int i = 1; i < x.length; i++) {
            if (x[i] >= mid) {
                double t = (mid - x[i - 1]) / Math.max(1e-9, x[i] - x[i - 1]);
                return y[i - 1] + (y[i] - y[i - 1]) * t;
            }
        }
        return Double.NaN;
    }

    /** The top of the flight over the edge's top. */
    public double apex(int blocks) {
        double top = 0;
        for (double v : ys[clamp(blocks)]) {
            top = Math.max(top, v);
        }
        return top;
    }

    private int clamp(int blocks) {
        return Math.max(0, Math.min(xs.length - 1, blocks));
    }
}
