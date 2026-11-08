package meridian.core.impl.interaction;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import meridian.protocol.ChainingInteraction;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.packets.interaction.SyncInteractionChain;

/**
 * Where the player stands in each combo - the server's {@code ChainingInteraction.Data}, kept
 * on this side so a forged swing sends the step the server expects next.
 *
 * <p>A {@code ChainingInteraction} (sword swings, the block-attack swing) takes the branch the
 * client names in {@code chainingIndex}. The server remembers, per chain id, the last step it
 * was sent and when any chain last swung, and checks a new step against that: the next one in
 * turn, or step 0 once the chain's {@code chainingAllowance} has run out, or either near that
 * boundary. A swing that always says 0 is logged as "unexpected chainingIndex" today and is to
 * be refused later.
 *
 * <p>Every step that reaches the server is noted here - the player's own as well as forged
 * ones - since both move the server's count.
 */
final class ChainingMemory {

    /** chain key ({@code chainId}, else the interaction's own id) &rarr; last step sent. */
    private final Map<String, Integer> last = new HashMap<>();
    /** When the last step of any chain was sent - the server keeps one time for them all. */
    private long lastSwingNanos;

    /** The step to send now for {@code node} - nothing is changed until it is {@link #noteSent sent}. */
    synchronized int next(ChainingInteraction node, int interactionId) {
        int branches = node.chainingNext == null ? 0 : node.chainingNext.length;
        if (branches == 0 || lastSwingNanos == 0) {
            return 0; // the very first swing starts the chain
        }
        int step = last.getOrDefault(key(node, interactionId), 0) + 1;
        if (step >= branches) {
            step = 0;
        }
        float since = (System.nanoTime() - lastSwingNanos) / 1_000_000_000f;
        if (node.chainingAllowance > 0 && since > node.chainingAllowance) {
            step = 0; // too slow - the combo starts over
        }
        return step;
    }

    /**
     * Notes the steps a chain going to the server carries. {@code roots} gives an op's root in
     * flattened form, to tell which interaction - which chain - a step belongs to.
     */
    void noteSent(SyncInteractionChain chain, IntFunction<CompiledInteraction> roots) {
        if (chain == null) {
            return;
        }
        if (chain.interactionData != null) {
            for (InteractionSyncData d : chain.interactionData) {
                if (d != null && d.chainingIndex >= 0) {
                    note(d, roots);
                }
            }
        }
        if (chain.newForks != null) {
            for (SyncInteractionChain fork : chain.newForks) {
                noteSent(fork, roots);
            }
        }
    }

    private void note(InteractionSyncData d, IntFunction<CompiledInteraction> roots) {
        CompiledInteraction root = roots.apply(d.rootInteraction);
        FlatOp op = root == null ? null : root.op(d.operationCounter);
        if (op instanceof FlatOp.Node n && n.interaction() instanceof ChainingInteraction c) {
            synchronized (this) {
                last.put(key(c, n.interactionId()), d.chainingIndex);
                lastSwingNanos = System.nanoTime();
            }
        }
    }

    private static String key(ChainingInteraction node, int interactionId) {
        return node.chainId != null ? "chain:" + node.chainId : "id:" + interactionId;
    }
}
