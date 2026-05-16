package meridian.core.api;

/**
 * Neutral projection of a Hytale block type.
 *
 * <p>Immutable: {@code with*} methods return a new view. The Layer-1
 * implementation maps this onto the raw protocol {@code BlockType}, so a
 * renamed protocol field never reaches a Layer-2 module.
 */
public interface BlockView {
    int id();

    String name();

    boolean isSolid();

    boolean isVisible();

    /** Whether this block type emits light. */
    boolean isLit();

    BlockView withSolid(boolean solid);

    BlockView withVisible(boolean visible);

    /**
     * Makes the block emit light of the given radius and colour
     * (each channel 0–255). Used by, e.g., a night-vision module.
     */
    BlockView withLight(int radius, int red, int green, int blue);

    /** Removes any light emission from the block. */
    BlockView withoutLight();
}
