package meridian.core.impl.interaction;

import meridian.protocol.BlockFace;
import meridian.protocol.BlockPosition;
import meridian.protocol.BlockType;
import meridian.protocol.InteractionType;

/**
 * Inputs the {@link InteractionSimulator} reads while walking a flattened
 * interaction — the proxy-side, trimmed-down equivalent of the server's
 * {@code InteractionContext}.
 *
 * @param interactionType the chain's interaction type (Use / Secondary / …)
 * @param targetBlock     the block the interaction acts on
 * @param targetBlockType the resolved {@link BlockType} of {@code targetBlock},
 *                        or {@code null} if the chunk / catalog is not loaded
 * @param placeBlock      where a placed block lands ({@code targetBlock.y + 1}),
 *                        or {@code null} when nothing is placed
 * @param blockFace       the face used for placement / targeting
 * @param placedBlockId   block id to place, or {@code -1} to let the server
 *                        fall back to the held item's block
 */
record InteractionContext(InteractionType interactionType,
                          BlockPosition targetBlock,
                          BlockType targetBlockType,
                          BlockPosition placeBlock,
                          BlockFace blockFace,
                          int placedBlockId) {

    /** Context for a plain block interaction (harvest / use) — no placement. */
    static InteractionContext ofBlock(InteractionType type, BlockPosition block,
                                      BlockType blockType, BlockFace face) {
        return new InteractionContext(type, block, blockType, null, face, -1);
    }

    /** Context for a block-placing interaction (plant) — block lands at {@code y + 1}. */
    static InteractionContext ofPlacement(InteractionType type, BlockPosition soil,
                                          BlockType soilType, BlockFace face) {
        BlockPosition above = soil == null ? null
                : new BlockPosition(soil.x, soil.y + 1, soil.z);
        return new InteractionContext(type, soil, soilType, above, face, -1);
    }
}
