package meridian.core.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import meridian.core.api.BlockView;
import meridian.core.api.Rotation;
import meridian.protocol.BlockMaterial;
import meridian.protocol.BlockTextures;
import meridian.protocol.BlockType;
import meridian.protocol.ColorLight;
import meridian.protocol.DrawType;
import meridian.protocol.RandomRotation;
import meridian.protocol.VariantRotation;

/**
 * Immutable {@link BlockView} backed by a raw protocol {@link BlockType}.
 *
 * <p>This is the single place where Hytale's {@code BlockType} shape is mapped
 * onto the neutral API. A renamed protocol field is fixed here and nowhere else.
 */
final class BlockViewImpl implements BlockView {
    /** An item id's categories, from the server's item catalog - nothing until one is known. */
    static final java.util.function.Function<String, List<String>> NO_ITEMS = item -> List.of();

    private final int id;
    private final BlockType blockType;
    private final java.util.function.Function<String, List<String>> categoriesOf;
    /** A block type's flip type by its name, from the game's files - SYMMETRIC until known. */
    private final java.util.function.Function<String, Rotation.Flip> flipOf;

    /** No flip types known: every block the server's default. */
    static final java.util.function.Function<String, Rotation.Flip> NO_FLIPS = name -> Rotation.Flip.SYMMETRIC;

    BlockViewImpl(int id, BlockType blockType) {
        this(id, blockType, NO_ITEMS);
    }

    BlockViewImpl(int id, BlockType blockType, java.util.function.Function<String, List<String>> categoriesOf) {
        this(id, blockType, categoriesOf, NO_FLIPS);
    }

    BlockViewImpl(int id, BlockType blockType, java.util.function.Function<String, List<String>> categoriesOf,
                  java.util.function.Function<String, Rotation.Flip> flipOf) {
        this.id = id;
        this.blockType = blockType;
        this.categoriesOf = categoriesOf == null ? NO_ITEMS : categoriesOf;
        this.flipOf = flipOf == null ? NO_FLIPS : flipOf;
    }

    @Override
    public Rotation.Flip flip() {
        Rotation.Flip f = blockType.name == null ? null : flipOf.apply(blockType.name);
        return f == null ? Rotation.Flip.SYMMETRIC : f;
    }

    @Override
    public List<String> categories() {
        return blockType.item == null ? List.of() : categoriesOf.apply(blockType.item);
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public String name() {
        return blockType.name;
    }

    @Override
    public String state() {
        return WorldStateImpl.stateNameForBlock(blockType, id);
    }

    @Override
    public Map<String, Integer> states() {
        if (blockType.states == null || blockType.states.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        blockType.states.forEach((name, stateId) -> {
            if (name != null && stateId != null) {
                out.put(name, stateId);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    @Override
    public int family() {
        // The server names the type a state was declared on under "default" (StateData.NULL_STATE_ID)
        // in every state type's map; the declaring type itself carries no such entry.
        Integer declared = blockType.states == null ? null : blockType.states.get(DEFAULT_STATE);
        return declared != null && declared >= 0 ? declared : id;
    }

    @Override
    public boolean sameBlock(int other) {
        return other == id || other == family()
                || blockType.states != null && blockType.states.containsValue(other);
    }

    @Override
    public boolean rotationMatters() {
        // A block that cannot be turned stays at zero; one the game turns at random (rocks,
        // grass) is the same block whichever way it faces.
        return blockType.variantRotation != VariantRotation.None
                && blockType.randomRotation == RandomRotation.None;
    }

    @Override
    public boolean isVisible() {
        return blockType.drawType != DrawType.Empty;
    }

    @Override
    public boolean isSolid() {
        // The material, as the server collides by it (and as core's movement does). The hitbox is
        // only the shape, and its index 0 is the server's full cube, not "none".
        return blockType.material == BlockMaterial.Solid;
    }

    @Override
    public boolean isLit() {
        return blockType.light != null;
    }

    @Override
    public List<String> textures() {
        if (blockType.cubeTextures == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (BlockTextures faces : blockType.cubeTextures) {
            if (faces == null) {
                continue;
            }
            for (String t : new String[]{faces.top, faces.bottom, faces.front, faces.back,
                    faces.left, faces.right}) {
                if (t != null && !t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public BlockView withTextures(UnaryOperator<String> remap) {
        BlockType copy = blockType.clone();
        if (copy.cubeTextures != null) {
            for (BlockTextures faces : copy.cubeTextures) {
                if (faces == null) {
                    continue;
                }
                faces.top = remapped(remap, faces.top);
                faces.bottom = remapped(remap, faces.bottom);
                faces.front = remapped(remap, faces.front);
                faces.back = remapped(remap, faces.back);
                faces.left = remapped(remap, faces.left);
                faces.right = remapped(remap, faces.right);
            }
        }
        return new BlockViewImpl(id, copy, categoriesOf, flipOf);
    }

    private static String remapped(UnaryOperator<String> remap, String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String to = remap.apply(path);
        return to != null ? to : path;
    }

    @Override
    public BlockView withOpacity(BlockView.Opacity opacity) {
        BlockType copy = blockType.clone();
        copy.opacity = switch (opacity) {
            case SOLID -> meridian.protocol.Opacity.Solid;
            case SEMITRANSPARENT -> meridian.protocol.Opacity.Semitransparent;
            case CUTOUT -> meridian.protocol.Opacity.Cutout;
            case TRANSPARENT -> meridian.protocol.Opacity.Transparent;
        };
        // Vanilla pairs the blended modes with the blending flag and the others without it.
        copy.requiresAlphaBlending = opacity == BlockView.Opacity.TRANSPARENT
                || opacity == BlockView.Opacity.SEMITRANSPARENT;
        return new BlockViewImpl(id, copy, categoriesOf, flipOf);
    }

    @Override
    public BlockView withVisible(boolean visible) {
        BlockType copy = blockType.clone();
        if (!visible) {
            copy.drawType = DrawType.Empty;
        }
        return new BlockViewImpl(id, copy, categoriesOf, flipOf);
    }

    @Override
    public BlockView withSolid(boolean solid) {
        BlockType copy = blockType.clone();
        copy.material = solid ? BlockMaterial.Solid : BlockMaterial.Empty;
        return new BlockViewImpl(id, copy, categoriesOf, flipOf);
    }

    @Override
    public BlockView withLight(int radius, int red, int green, int blue) {
        BlockType copy = blockType.clone();
        copy.light = new ColorLight((byte) radius, channel(red), channel(green), channel(blue));
        return new BlockViewImpl(id, copy, categoriesOf, flipOf);
    }

    /** A light channel as the server keeps it: four bits, 0..15. */
    private static byte channel(int value) {
        return (byte) Math.clamp(value, 0, 15);
    }

    @Override
    public BlockView withoutLight() {
        BlockType copy = blockType.clone();
        copy.light = null;
        return new BlockViewImpl(id, copy, categoriesOf, flipOf);
    }

    /** The underlying (possibly transformed) protocol block type. */
    @Override
    public String gatherType() {
        return blockType.gathering == null || blockType.gathering.breaking == null ? null
                : blockType.gathering.breaking.gatherType;
    }

    BlockType toBlockType() {
        return blockType;
    }

    /**
     * Whether two views of one server type look the same to the client: equal in every field a
     * {@code with*} here changes. Both must come from the same truth - a view is the truth cloned
     * and then changed through these methods only, so the fields below are the only ones that can
     * differ. A {@code with*} that touches another field has to be compared here too.
     */
    static boolean sameLook(BlockType a, BlockType b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.drawType == b.drawType
                && a.material == b.material
                && a.opacity == b.opacity
                && a.requiresAlphaBlending == b.requiresAlphaBlending
                && sameLight(a.light, b.light)
                && sameFaces(a.cubeTextures, b.cubeTextures);
    }

    private static boolean sameLight(ColorLight a, ColorLight b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.radius == b.radius && a.red == b.red && a.green == b.green && a.blue == b.blue;
    }

    private static boolean sameFaces(BlockTextures[] a, BlockTextures[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            BlockTextures x = a[i], y = b[i];
            if (x == null || y == null) {
                if (x != y) {
                    return false;
                }
                continue;
            }
            if (!java.util.Objects.equals(x.top, y.top) || !java.util.Objects.equals(x.bottom, y.bottom)
                    || !java.util.Objects.equals(x.front, y.front) || !java.util.Objects.equals(x.back, y.back)
                    || !java.util.Objects.equals(x.left, y.left) || !java.util.Objects.equals(x.right, y.right)) {
                return false;
            }
        }
        return true;
    }
}
