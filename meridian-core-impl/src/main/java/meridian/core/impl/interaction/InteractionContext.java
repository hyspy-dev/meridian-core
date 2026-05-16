package meridian.core.impl.interaction;

import meridian.protocol.BlockFace;
import meridian.protocol.BlockPosition;

/**
 * Inputs the {@link InteractionSimulator} reads while walking a flattened
 * interaction — the proxy-side, trimmed-down equivalent of the server's
 * {@code InteractionContext}.
 *
 * <p>v1 carries what the farming interactions need: the interacted block and,
 * for {@code PlaceBlockInteraction}, where the placed block lands. More fields
 * (entity target, charge timing, …) are added as nodes are ported.
 *
 * @param targetBlock   the block the interaction acts on (e.g. crop / soil)
 * @param placeBlock    where a placed block lands ({@code targetBlock.y + 1}),
 *                      or {@code null} when the interaction places nothing
 * @param blockFace     the face used for placement / targeting
 * @param placedBlockId block id to place, or {@code -1} to let the server fall
 *                      back to the held item's block
 */
record InteractionContext(BlockPosition targetBlock,
                          BlockPosition placeBlock,
                          BlockFace blockFace,
                          int placedBlockId) {

    /** Context for a plain block interaction (harvest / use) — no placement. */
    static InteractionContext ofBlock(BlockPosition block, BlockFace face) {
        return new InteractionContext(block, null, face, -1);
    }

    /** Context for a block-placing interaction (plant) — block lands at {@code y + 1}. */
    static InteractionContext ofPlacement(BlockPosition soil, BlockFace face) {
        BlockPosition above = new BlockPosition(soil.x, soil.y + 1, soil.z);
        return new InteractionContext(soil, above, face, -1);
    }
}
