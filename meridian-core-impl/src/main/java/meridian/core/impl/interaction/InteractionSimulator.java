package meridian.core.impl.interaction;

import java.util.ArrayList;
import java.util.List;
import meridian.protocol.BlockRotation;
import meridian.protocol.BlockType;
import meridian.protocol.ChargingInteraction;
import meridian.protocol.Interaction;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InteractionType;
import meridian.protocol.PlaceBlockInteraction;
import meridian.protocol.UseBlockInteraction;
import meridian.protocol.UseEntityInteraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks a {@link CompiledInteraction} and produces the {@code InteractionSyncData[]}
 * a forged {@code SyncInteractionChain} carries — the proxy-side port of the
 * server's interaction tick loop.
 *
 * <p>Per {@code Interaction.simulateTick}, each operation resolves to a state:
 * a {@code Finished} operation falls through to the next; a {@code Failed}
 * operation with a branch label jumps to it (the server's
 * {@code SimpleInteraction.tick0}: {@code if Failed && hasLabels jump(label0)}).
 * Each visited operation is recorded as one {@code InteractionSyncData} whose
 * {@code operationCounter} is its index in the flattened list.
 *
 * <p><b>Ported node logic.</b>
 * <ul>
 *   <li>{@code UseEntityInteraction} — fails (no entity target).</li>
 *   <li>{@code UseBlockInteraction} — fails unless the target block type has an
 *       interaction for the chain's type.</li>
 *   <li>{@code ChargingInteraction} — finishes, carrying a {@code chargeValue}.</li>
 *   <li>{@code PlaceBlockInteraction} — finishes, carrying the placed block.</li>
 *   <li>everything else — finishes.</li>
 * </ul>
 */
final class InteractionSimulator {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");
    private static final int MAX_STEPS = 256;

    /** {@code chargeValue} that tells the server's charge {@code tick0} to finish now. */
    private static final float CHARGE_FINISH_NOW = -2.0f;

    private InteractionSimulator() {}

    /** Simulates {@code compiled} under {@code ctx} into the executed operations. */
    static List<InteractionSyncData> simulate(CompiledInteraction compiled, InteractionContext ctx) {
        List<InteractionSyncData> executed = new ArrayList<>();
        int counter = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            FlatOp op = compiled.op(counter);
            if (op == null) {
                break; // walked off the end — chain complete
            }
            if (op instanceof FlatOp.Jump jump) {
                counter = jump.target().index;
                if (counter == OpLabel.UNRESOLVED) {
                    log.warn("meridian-core: interaction sim hit an unresolved jump (root {})",
                            compiled.rootId());
                    break;
                }
                continue;
            }
            FlatOp.Node node = (FlatOp.Node) op;
            InteractionState state = evaluateState(node.interaction(), ctx);
            executed.add(syncDataFor(node, counter, compiled.rootId(), state, ctx));

            if (state == InteractionState.Failed && node.labels().length > 0) {
                counter = node.labels()[0].index; // failed branch
            } else {
                counter++; // fall through to the primary child
            }
        }
        if (executed.size() >= MAX_STEPS) {
            log.warn("meridian-core: interaction sim hit the step cap (root {})", compiled.rootId());
        }
        return executed;
    }

    /** Ported {@code simulateTick0} outcome for one node. */
    private static InteractionState evaluateState(Interaction node, InteractionContext ctx) {
        if (node instanceof UseEntityInteraction) {
            // Forged chains never target an entity (data.entityId = -1).
            return InteractionState.Failed;
        }
        if (node instanceof UseBlockInteraction) {
            // doInteraction: fails unless the target block has an interaction
            // mapped for this interaction type.
            return hasBlockInteraction(ctx.targetBlockType(), ctx.interactionType())
                    ? InteractionState.Finished : InteractionState.Failed;
        }
        return InteractionState.Finished;
    }

    private static boolean hasBlockInteraction(BlockType blockType, InteractionType type) {
        return blockType != null && blockType.interactions != null
                && blockType.interactions.containsKey(type);
    }

    /** Builds the {@code InteractionSyncData} for one executed operation. */
    private static InteractionSyncData syncDataFor(FlatOp.Node node, int counter, int rootId,
                                                   InteractionState state, InteractionContext ctx) {
        InteractionSyncData data = new InteractionSyncData();
        data.operationCounter = counter;
        data.rootInteraction = rootId;
        data.state = state;

        if (node.interaction() instanceof ChargingInteraction) {
            // ChargingInteraction.tick0 keys off chargeValue; -2.0 = finish now.
            data.chargeValue = CHARGE_FINISH_NOW;
        } else if (node.interaction() instanceof PlaceBlockInteraction && ctx.placeBlock() != null) {
            // PlaceBlockInteraction.tick0 requires a non-null blockPosition +
            // blockRotation and reads blockFace.
            data.blockPosition = ctx.placeBlock();
            data.blockRotation = new BlockRotation();
            data.blockFace = ctx.blockFace();
            data.placedBlockId = ctx.placedBlockId();
        }
        return data;
    }
}
