package meridian.core.api;

/**
 * Draws debug shapes in the client's world — a meridian-core Layer-1 service.
 *
 * <p>Sends the {@code DisplayDebug} packets Hytale's own {@code /debug shape}
 * commands use, so a Layer-2 module can outline entities or blocks (ESP) without
 * touching the protocol. Shapes carry a lifetime and expire on their own; a
 * follow-the-target overlay re-draws every tick.
 *
 * <p>Calls are no-ops until a client session is live ({@link #available()}).
 */
public interface DebugRender {

    /** {@code true} once a client session has been captured. */
    boolean available();

    /**
     * Draws a wireframe box centred on {@code (x, y, z)} with the given full
     * dimensions, RGB colour (each channel 0..1) and lifetime in seconds.
     * Uses a default opacity that reads well over most terrain — modules
     * that need a different opacity should call the
     * {@linkplain #box(double, double, double, double, double, double, float, float, float, float, float) opacity overload}.
     */
    void box(double x, double y, double z,
             double sizeX, double sizeY, double sizeZ,
             float red, float green, float blue, float seconds);

    /**
     * Draws a depth-tested solid+wireframe box at {@code (x, y, z)} with the
     * given full dimensions, RGB colour (each channel 0..1), {@code opacity}
     * in {@code [0, 1]} and lifetime in seconds. The default-opacity overload
     * ({@link #box(double, double, double, double, double, double, float, float, float, float)})
     * delegates here with {@code opacity = 0.5}.
     */
    void box(double x, double y, double z,
             double sizeX, double sizeY, double sizeZ,
             float red, float green, float blue, float opacity, float seconds);

    /** Clears every debug shape currently shown. */
    void clear();

    /**
     * Adds or updates a named through-wall box, the editor's trigger-volume
     * display channel. The box is visible from anywhere — depth-test is bypassed
     * by design (so editors see their trigger zones from inside walls) — and
     * persists until {@link #clearWorldBox(String)} or a new call with the same
     * {@code id} replaces it. Use distinct ids for distinct boxes.
     */
    void worldBox(String id, double x, double y, double z,
                  double sizeX, double sizeY, double sizeZ,
                  float red, float green, float blue, float opacity);

    /** Removes a box previously placed by {@link #worldBox}. */
    void clearWorldBox(String id);

    /**
     * Draws a coloured line from {@code (x1,y1,z1)} to {@code (x2,y2,z2)} that
     * lasts {@code seconds} seconds. Uses the editor's laser-pointer channel —
     * visible through walls — so the colour reads even behind geometry.
     */
    void worldLine(double x1, double y1, double z1,
                   double x2, double y2, double z2,
                   float red, float green, float blue, float seconds);

    /**
     * Draws a wireframe box (12 edges via {@link #worldLine}) centred on
     * {@code (x, y, z)} with the given full dimensions. Lines fade out after
     * {@code seconds}; re-call each tick to keep the outline alive.
     */
    void wireBox(double x, double y, double z,
                 double sizeX, double sizeY, double sizeZ,
                 float red, float green, float blue, float seconds);
}
