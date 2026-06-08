package meridian.core.impl;

import java.util.concurrent.CompletableFuture;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.core.api.Face;
import meridian.core.api.InteractionControl;

/**
 * A {@link Block} handle — a world position bound to the {@link InteractionControl}
 * that forges interactions on it. Produced by {@link WorldImpl#blockAt}.
 */
final class BlockImpl implements Block {
    private final int x;
    private final int y;
    private final int z;
    private final int blockId;
    private final BlockView type;
    private final InteractionControl interactions;

    BlockImpl(int x, int y, int z, int blockId, BlockView type, InteractionControl interactions) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockId = blockId;
        this.type = type;
        this.interactions = interactions;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return y;
    }

    @Override
    public int z() {
        return z;
    }

    @Override
    public BlockView type() {
        return type;
    }

    @Override
    public boolean isAir() {
        return blockId <= 0;
    }

    @Override
    public CompletableFuture<Void> use() {
        return interactions.useOnBlock(new BlockPos(x, y, z));
    }

    @Override
    public CompletableFuture<Void> hit() {
        return interactions.hitBlock(new BlockPos(x, y, z));
    }

    @Override
    public CompletableFuture<Void> plant() {
        return interactions.plantOnBlock(new BlockPos(x, y, z));
    }

    @Override
    public CompletableFuture<Void> place() {
        return interactions.placeOnBlock(new BlockPos(x, y, z));
    }

    @Override
    public CompletableFuture<Void> place(Face face) {
        return interactions.placeOnBlock(new BlockPos(x, y, z), face);
    }

    @Override
    public CompletableFuture<Void> place(Face against, Face orient) {
        return interactions.placeOnBlock(new BlockPos(x, y, z), against, orient);
    }

    @Override
    public CompletableFuture<Void> water() {
        return interactions.waterBlock(new BlockPos(x, y, z));
    }
}
