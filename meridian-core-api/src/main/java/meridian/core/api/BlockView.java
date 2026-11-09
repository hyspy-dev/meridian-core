package meridian.core.api;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Neutral projection of a Hytale block type.
 *
 * <p>Immutable: {@code with*} methods return a new view. The Layer-1
 * implementation maps this onto the raw protocol {@code BlockType}, so a
 * renamed protocol field never reaches a Layer-2 module.
 */
public interface BlockView {

    /**
     * The name of block id {@code 0}: no block. The catalog lists air under it, and the prefab
     * format writes it for a cell left empty.
     */
    String EMPTY = "Empty";

    /** The name of block id {@code 1}: the server's stand-in for a block it does not know. */
    String UNKNOWN = "Unknown";

    /**
     * The block-name generation this core's server writes - the stamp a save puts on block names
     * so that a later game knows which renames they have already been through.
     *
     * <p>The server keeps its renames as numbered {@code BlockMigration} assets ({@code
     * Server/Item/Block/Migrations/0.json ... 10.json} on 0.8.0-pre.4) and stamps what it writes with
     * how many there are ({@code BlockMigration.getAssetMap().getAssetCount()}): a chunk section's
     * first int, a prefab file's {@code blockIdVersion}. On load it applies every migration from the
     * stamp upward, so a name stamped {@code 0} is put through every rename there ever was - names
     * that are current today can come out as something else - while this stamp applies none on this
     * server and only the newer ones on a later game.
     */
    int BLOCK_ID_VERSION = 11;

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

    /**
     * Every state of this block's family, by name, as the block id each one is - {@code "on"} and
     * {@code "off"} for a lamp. Empty for a block without states. Two blocks are the same block in
     * different states when either one's id is among the other's values.
     */
    default Map<String, Integer> states() {
        return Map.of();
    }

    /**
     * The block this one is a state of, by id: the type its states were declared on. Its own id for
     * a block without states, and for the declaring type itself. Two block types are one block in
     * different states - an open door and a shut one, a lit lamp and a dark one - exactly when
     * their {@code family()} is the same.
     *
     * <p>The server's rule ({@code BlockType.getDefaultStateKey}: the type a state was declared in),
     * which it hands the client as the {@code "default"} entry of every state type's
     * {@link #states()}. Not the names: a state type is called {@code *<block>_State_Definitions_<state>},
     * but nothing stops another block's name from looking like that.
     */
    default int family() {
        Integer declared = states().get(DEFAULT_STATE);
        return declared != null && declared >= 0 ? declared : id();
    }

    /**
     * Whether block {@code id} is this block - this very type, or another state of its family. Needs
     * no catalog: a family's every member lists all the others in its {@link #states()}.
     */
    default boolean sameBlock(int id) {
        return id == id() || id == family() || states().containsValue(id);
    }

    /**
     * The state name the server gives a block that is no state of anything ({@code
     * StateData.NULL_STATE_ID}): what {@link #state()} reports then, and the key under which a state
     * type's {@link #states()} names the type it belongs to ({@link #family()}).
     */
    String DEFAULT_STATE = "default";

    /**
     * Whether which way this block faces is part of what it is. False for a block that cannot be
     * turned, and for one the game turns at random as it is placed - two rocks facing different
     * ways are still the same rock.
     */
    default boolean rotationMatters() {
        return false;
    }

    /**
     * How this block folds a mirror back into a rotation ({@link Rotation#mirror(int, int,
     * Rotation.Flip)}): the {@code FlipType} its asset declares, which the server never sends - so it
     * is read from the game's own files ({@link GameAssets}), inheritance and states included.
     * {@link Rotation.Flip#SYMMETRIC}, the server's default, for a block the installed game does not
     * define (a mod's), with no game at hand, or from a core too old to know.
     */
    default Rotation.Flip flip() {
        return Rotation.Flip.SYMMETRIC;
    }

    /**
     * Whether a body collides with this block: its material is solid, the server's rule for what
     * stops a player ({@code MATERIAL_SOLID}). Stone and a door are solid, and so is a fence, whose
     * shape fills only part of the cell; grass and torches are not.
     */
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
     * The categories of the item this block is, as the server's item catalog lists them
     * ({@code Blocks.Ores}, {@code Blocks.Rocks}, {@code Blocks.Soils}) - what a module tells kinds of
     * block apart by, a mod's blocks included, rather than by their names. Empty for a block that
     * is no item, or before the catalog came.
     */
    default List<String> categories() {
        return List.of();
    }

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

    /**
     * Makes the client collide with this block, or walk through it: its material set solid, or
     * empty. The server keeps colliding by its own copy, so this changes only what the client
     * predicts.
     */
    BlockView withSolid(boolean solid);

    BlockView withVisible(boolean visible);

    /**
     * Makes the block emit light of the given radius and colour. Each channel is 0..15, not
     * 0..255: the server keeps a block's light as three 4-bit values (its asset's {@code "#RGB"},
     * a {@code "#RRGGBB"} divided by 17) and floods it out one step a block, so the largest channel
     * is how far it reaches and how bright it is. A value outside 0..15 is taken as the nearest end.
     * Used by, e.g., a night-vision module.
     */
    BlockView withLight(int radius, int red, int green, int blue);

    /** Removes any light emission from the block. */
    BlockView withoutLight();
}
