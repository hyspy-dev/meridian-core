package meridian.core.impl.interaction;

import meridian.protocol.BlockFace;
import meridian.protocol.BlockPosition;
import meridian.protocol.BlockType;
import meridian.protocol.InteractionType;
import meridian.protocol.MovementStates;

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
 * @param movementStates  the player's last observed {@code MovementStates} —
 *                        what {@code ConditionInteraction} tests — or
 *                        {@code null} if none has been seen yet
 * @param replacementRoot the root a {@code ReplaceInteraction} switches to
 *                        ({@code context.execute(nextRoot)}), or
 *                        {@link #NO_REPLACEMENT} when unknown
 */
record InteractionContext(InteractionType interactionType,
                          BlockPosition targetBlock,
                          BlockType targetBlockType,
                          BlockPosition placeBlock,
                          BlockFace blockFace,
                          int placedBlockId,
                          MovementStates movementStates,
                          int replacementRoot) {

    /** {@code replacementRoot} sentinel: no {@code ReplaceInteraction} target known. */
    static final int NO_REPLACEMENT = Integer.MIN_VALUE;

    /** Context for a plain block interaction (harvest / use) — no placement. */
    static InteractionContext ofBlock(InteractionType type, BlockPosition block,
                                      BlockType blockType, BlockFace face,
                                      MovementStates movement, int replacementRoot) {
        return new InteractionContext(type, block, blockType, null, face, -1,
                movement, replacementRoot);
    }

    /** Context for a block-placing interaction (plant) — block lands at {@code y + 1}. */
    static InteractionContext ofPlacement(InteractionType type, BlockPosition soil,
                                          BlockType soilType, BlockFace face,
                                          MovementStates movement, int replacementRoot) {
        BlockPosition above = soil == null ? null
                : new BlockPosition(soil.x, soil.y + 1, soil.z);
        return new InteractionContext(type, soil, soilType, above, face, -1,
                movement, replacementRoot);
    }
}
