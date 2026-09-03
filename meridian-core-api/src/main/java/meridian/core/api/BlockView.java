package meridian.core.api;

import java.util.List;
import java.util.function.UnaryOperator;

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

    /**
     * The texture files this block's cube faces draw, distinct, in the form the game names
     * them ({@code BlockTextures/Soil_Dirt.png}) — the same form {@link ClientAssets#push}
     * hands back. Every face of every weighted variant is included. Empty for a block drawn
     * as a model or not at all.
     */
    List<String> textures();

    /**
     * Re-points every cube face at {@code remap.apply(path)}. A {@code null} result keeps
     * that face as it is, so a partial map only touches what it names. Faces of every
     * weighted variant go through the function; the block's draw mode is untouched.
     */
    BlockView withTextures(UnaryOperator<String> remap);

    /**
     * What the client makes of a texture's alpha channel. {@link #SOLID} ignores it.
     * {@link #CUTOUT} alpha-tests it, the way leaves are drawn: a pixel is there or it is
     * not. {@link #TRANSPARENT} blends it and drops the faces between two blocks of the same
     * type, the way glass and the see-through mushroom blocks are drawn.
     * {@link #SEMITRANSPARENT} blends it too; the game uses it for benches and bone piles.
     */
    enum Opacity { SOLID, SEMITRANSPARENT, CUTOUT, TRANSPARENT }

    /**
     * Sets how the client treats the alpha of this block's textures. Nothing changes for a
     * texture without alpha; pair it with {@link #withTextures} to draw one that has some.
     */
    BlockView withOpacity(Opacity opacity);

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
