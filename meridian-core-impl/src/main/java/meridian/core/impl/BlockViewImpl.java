package meridian.core.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import meridian.core.api.BlockView;
import meridian.protocol.BlockTextures;
import meridian.protocol.BlockType;
import meridian.protocol.ColorLight;
import meridian.protocol.DrawType;

/**
 * Immutable {@link BlockView} backed by a raw protocol {@link BlockType}.
 *
 * <p>This is the single place where Hytale's {@code BlockType} shape is mapped
 * onto the neutral API. A renamed protocol field is fixed here and nowhere else.
 */
final class BlockViewImpl implements BlockView {
    private final int id;
    private final BlockType blockType;

    BlockViewImpl(int id, BlockType blockType) {
        this.id = id;
        this.blockType = blockType;
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
    public boolean isVisible() {
        return blockType.drawType != DrawType.Empty;
    }

    @Override
    public boolean isSolid() {
        return blockType.hitbox != 0;
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
        return new BlockViewImpl(id, copy);
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
        return new BlockViewImpl(id, copy);
    }

    @Override
    public BlockView withVisible(boolean visible) {
        BlockType copy = blockType.clone();
        if (!visible) {
            copy.drawType = DrawType.Empty;
        }
        return new BlockViewImpl(id, copy);
    }

    @Override
    public BlockView withSolid(boolean solid) {
        BlockType copy = blockType.clone();
        if (!solid) {
            copy.hitbox = 0;
        }
        return new BlockViewImpl(id, copy);
    }

    @Override
    public BlockView withLight(int radius, int red, int green, int blue) {
        BlockType copy = blockType.clone();
        copy.light = new ColorLight((byte) radius, (byte) red, (byte) green, (byte) blue);
        return new BlockViewImpl(id, copy);
    }

    @Override
    public BlockView withoutLight() {
        BlockType copy = blockType.clone();
        copy.light = null;
        return new BlockViewImpl(id, copy);
    }

    /** The underlying (possibly transformed) protocol block type. */
    BlockType toBlockType() {
        return blockType;
    }
}
