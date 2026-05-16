package meridian.core.impl.interaction;

import java.util.ArrayList;
import java.util.List;
import meridian.protocol.BlockRotation;
import meridian.protocol.ChargingInteraction;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.PlaceBlockInteraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks a {@link CompiledInteraction} along its success path and produces the
 * {@code InteractionSyncData[]} a forged {@code SyncInteractionChain} carries —
 * the proxy-side port of the server's interaction tick loop.
 *
 * <p>The walk starts at operation 0 and, per {@code Interaction.simulateTick},
 * either falls through to the next operation or follows a {@link FlatOp.Jump}.
 * Each visited {@link FlatOp.Node} is recorded as one {@code InteractionSyncData}
 * whose {@code operationCounter} is its index in the flattened list — exactly
 * what {@code InteractionEntry.setClientState} validates.
 *
 * <p><b>v1 scope.</b> The success path: every operation is taken to
 * {@code Finished}, so branching nodes fall through to their primary child.
 * Two node types carry data the server's {@code tick0} requires —
 * {@code ChargingInteraction} (a {@code chargeValue}) and
 * {@code PlaceBlockInteraction} (the placed block). Charge / branch-level
 * selection beyond the success path is refined against live captures.
 */
final class InteractionSimulator {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");
    private static final int MAX_STEPS = 256;

    /** {@code chargeValue} that tells the server's charge {@code tick0} to finish now. */
    private static final float CHARGE_FINISH_NOW = -2.0f;

    private InteractionSimulator() {}

    /**
     * Simulates {@code compiled} under {@code ctx}.
     *
     * @return the executed operations as {@code InteractionSyncData}, in order;
     *         empty if the walk produced nothing
     */
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
            executed.add(syncDataFor(node, counter, compiled.rootId(), ctx));
            counter++; // success path — fall through to the primary child
        }
        if (executed.size() >= MAX_STEPS) {
            log.warn("meridian-core: interaction sim hit the step cap (root {})", compiled.rootId());
        }
        return executed;
    }

    /** Builds the {@code InteractionSyncData} for one executed operation. */
    private static InteractionSyncData syncDataFor(FlatOp.Node node, int counter,
                                                   int rootId, InteractionContext ctx) {
        InteractionSyncData data = new InteractionSyncData();
        data.operationCounter = counter;
        data.rootInteraction = rootId;
        data.state = InteractionState.Finished;

        if (node.interaction() instanceof ChargingInteraction) {
            // The server's ChargingInteraction.tick0 keys off chargeValue;
            // -2.0 means "finish the charge immediately".
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
