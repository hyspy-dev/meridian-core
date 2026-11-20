package meridian.core.api;

/**
 * A short text over an entity that the game draws over everything - walls included.
 *
 * <p>The game draws the text that pops off a hit in its interface, at the entity's place on the
 * screen, so nothing in the world hides it. Core hangs a text of its own there and keeps it up for
 * as long as it is asked to: shown again twice a second, each one fading in over the one before.
 *
 * <p>The game shows about {@link #MAX_CHARS} characters of a text; what is longer is cut. While a
 * label is up, the entity's own pop-up text (a hit's damage) is not shown, and comes back with
 * {@link #hide}.
 */
public interface EntityLabels {

    /** About as many characters as the game shows of one text. */
    int MAX_CHARS = 12;

    /** Whether there is a game to draw on now. */
    boolean available();

    /** Keeps {@code text} over the entity in {@code rgb} (0xRRGGBB) until hidden or the entity is gone. */
    void show(int entityId, String text, int rgb);

    /** Takes the entity's label down, its own pop-up text back. */
    void hide(int entityId);

    /** Takes every label down. */
    void clear();

    /** The size of the labels' letters (the game's hit text is 48). */
    void fontSize(float size);
}
