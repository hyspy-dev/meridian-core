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

    /**
     * This block's current state name — e.g. {@code "on"}/{@code "off"} for a
     * toggleable lamp, a crop's growth stage, or {@code "default"} when the type
     * declares no states. Hytale encodes state as a distinct block id within a
     * family, so two blocks sharing a {@link #name()} can report different
     * {@code state()}; the id reverse-resolves the name. Read-only — there is no
     * {@code withState}; a forged interaction is how state is changed.
     */
    String state();

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
