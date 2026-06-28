package meridian.core.impl;

import meridian.core.api.BlockView;
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
