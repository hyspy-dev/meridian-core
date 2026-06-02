package meridian.core.impl.interaction;

import java.util.Map;
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
 * @param interactionVars the held item's interaction variables — what
 *                        {@code ReplaceInteraction} resolves its replacement
 *                        root against (the server's {@code getInteractionVars})
 */
record InteractionContext(InteractionType interactionType,
                          BlockPosition targetBlock,
                          BlockType targetBlockType,
                          BlockPosition placeBlock,
                          BlockFace blockFace,
                          int placedBlockId,
                          MovementStates movementStates,
                          Map<String, Integer> interactionVars) {

    /** Context for a plain block interaction (harvest / use) — no placement. */
    static InteractionContext ofBlock(InteractionType type, BlockPosition block,
                                      BlockType blockType, BlockFace face,
                                      MovementStates movement, Map<String, Integer> vars) {
        return new InteractionContext(type, block, blockType, null, face, -1, movement, vars);
    }

    /**
     * Context for a block-placing interaction — the placed block lands in the
     * cell adjacent to {@code target} across {@code face} (server convention:
     * {@code placed = target + face.normal}). {@code BlockFace.Up} reproduces
     * plant's "lands at {@code y + 1}".
     */
    static InteractionContext ofPlacement(InteractionType type, BlockPosition target,
                                          BlockType targetType, BlockFace face,
                                          MovementStates movement, Map<String, Integer> vars) {
        BlockPosition placed = target == null ? null
                : new BlockPosition(target.x + normalX(face),
                                    target.y + normalY(face),
                                    target.z + normalZ(face));
        return new InteractionContext(type, target, targetType, placed, face, -1, movement, vars);
    }

    // Face normals, matching the server (Vector3iUtil / BlockFace.getDirection):
    // EAST = +X, WEST = -X, UP = +Y, DOWN = -Y, SOUTH = +Z, NORTH = -Z.
    private static int normalX(BlockFace f) {
        return f == BlockFace.East ? 1 : f == BlockFace.West ? -1 : 0;
    }

    private static int normalY(BlockFace f) {
        return f == BlockFace.Up ? 1 : f == BlockFace.Down ? -1 : 0;
    }

    private static int normalZ(BlockFace f) {
        return f == BlockFace.South ? 1 : f == BlockFace.North ? -1 : 0;
    }
}
