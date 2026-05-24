package meridian.core.impl.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import meridian.protocol.ApplyForceInteraction;
import meridian.protocol.ChainingInteraction;
import meridian.protocol.ChargingInteraction;
import meridian.protocol.FirstClickInteraction;
import meridian.protocol.Interaction;
import meridian.protocol.MovementConditionInteraction;
import meridian.protocol.SerialInteraction;
import meridian.protocol.SimpleInteraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flattens a root interaction's node tree into an indexed operation list —
 * the proxy-side port of the server's {@code Interaction.compile} +
 * {@code OperationsBuilder}.
 *
 * <p>Each node type emits operations into the list; control-flow nodes also
 * emit branch labels and {@code jump}s. The list index is the
 * {@code operationCounter} the server validates in
 * {@code InteractionEntry.setClientState}.
 *
 * <p><b>Coverage.</b> Every {@code compile()} override the client can receive
 * is ported: leaf nodes, the {@code SimpleInteraction} family,
 * {@code FirstClickInteraction}, {@code ChainingInteraction},
 * {@code ChargingInteraction} ({@code WieldingInteraction} inherits it),
 * {@code SerialInteraction}, {@code ApplyForceInteraction},
 * {@code MovementConditionInteraction}. {@code DamageEntityInteraction}
 * (combat-only) is flattened as a leaf and logged.
 */
final class InteractionFlattener {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");
    private static final int MAX_DEPTH = 64;
    private static final OpLabel[] NO_LABELS = new OpLabel[0];

    private final IntFunction<Interaction> lookup;
    private final List<FlatOp> ops = new ArrayList<>();

    private InteractionFlattener(IntFunction<Interaction> lookup) {
        this.lookup = lookup;
    }

    /**
     * Flattens the root whose top-level node ids are {@code rootNodes}.
     *
     * @param rootId    the root interaction id (for the result + diagnostics)
     * @param rootNodes {@code RootInteraction.interactions}
     * @param lookup    interaction-node id &rarr; node ({@code null} if absent)
     */
    static CompiledInteraction compile(int rootId, int[] rootNodes,
                                       IntFunction<Interaction> lookup) {
        InteractionFlattener f = new InteractionFlattener(lookup);
        if (rootNodes != null) {
            for (int nodeId : rootNodes) {
                f.compileNode(nodeId, 0);
            }
        }
        return new CompiledInteraction(rootId, List.copyOf(f.ops));
    }

    // ------------------------------------------------------------------
    // compile dispatch — specific compile() overrides before the generic ones
    // ------------------------------------------------------------------

    private void compileNode(int nodeId, int depth) {
        if (nodeId == Integer.MIN_VALUE) {
            return; // absent child reference
        }
        if (depth > MAX_DEPTH) {
            log.warn("meridian-core: interaction flatten exceeded depth {} at node {}",
                    MAX_DEPTH, nodeId);
            return;
        }
        Interaction node = lookup.apply(nodeId);
        if (node == null) {
            log.warn("meridian-core: interaction node {} missing from registry", nodeId);
            return;
        }

        // ChainingInteraction / ChargingInteraction / SerialInteraction extend
        // Interaction directly. ApplyForce / MovementCondition extend
        // SimpleInteraction but override compile() — they must be matched first.
        if (node instanceof ChainingInteraction chaining) {
            compileChaining(chaining, nodeId, depth);
        } else if (node instanceof ChargingInteraction charging) {
            compileCharging(charging, nodeId, depth);
        } else if (node instanceof SerialInteraction serial) {
            compileSerial(serial, depth);
        } else if (node instanceof ApplyForceInteraction force) {
            compileApplyForce(force, nodeId, depth);
        } else if (node instanceof MovementConditionInteraction move) {
            compileMovementCondition(move, nodeId, depth);
        } else if (node instanceof FirstClickInteraction fc) {
            compileBranching(fc, nodeId, fc.click, fc.held, depth);
        } else if (node instanceof SimpleInteraction si) {
            // SimpleBlockInteraction and the Condition/Place/Change/... family.
            compileBranching(si, nodeId, si.next, si.failed, depth);
        } else {
            // Base compile() = a single leaf operation.
            addOperation(node, nodeId);
            if ("DamageEntityInteraction".equals(node.getClass().getSimpleName())) {
                log.warn("meridian-core: DamageEntityInteraction node {} flattened as leaf "
                        + "(combat compile not ported)", nodeId);
            }
        }
    }

    /**
     * The {@code SimpleInteraction} / {@code FirstClickInteraction} pattern: one
     * operation with a {@code failed} branch label, the primary child inline,
     * then the failed child.
     */
    private void compileBranching(Interaction node, int nodeId,
                                  int primaryRef, int failedRef, int depth) {
        if (primaryRef == Integer.MIN_VALUE && failedRef == Integer.MIN_VALUE) {
            addOperation(node, nodeId);
            return;
        }
        OpLabel failedLabel = new OpLabel();
        OpLabel endLabel = new OpLabel();
        addOperation(node, nodeId, failedLabel);
        if (primaryRef != Integer.MIN_VALUE) {
            compileNode(primaryRef, depth + 1);
        }
        if (failedRef != Integer.MIN_VALUE) {
            jump(endLabel);
        }
        resolveLabel(failedLabel);
        if (failedRef != Integer.MIN_VALUE) {
            compileNode(failedRef, depth + 1);
        }
        resolveLabel(endLabel);
    }

    /** {@code ChainingInteraction}: one operation branching into each child + flag. */
    private void compileChaining(ChainingInteraction node, int nodeId, int depth) {
        int[] next = node.chainingNext != null ? node.chainingNext : new int[0];
        List<String> flagKeys = node.flags != null
                ? node.flags.keySet().stream().sorted().toList() : List.of();

        OpLabel[] labels = newLabels(next.length + flagKeys.size());
        OpLabel end = new OpLabel();
        addOperation(node, nodeId, labels);
        for (int i = 0; i < next.length; i++) {
            resolveLabel(labels[i]);
            compileNode(next[i], depth + 1);
            jump(end);
        }
        for (int i = 0; i < flagKeys.size(); i++) {
            resolveLabel(labels[next.length + i]);
            compileNode(node.flags.get(flagKeys.get(i)), depth + 1);
            jump(end);
        }
        resolveLabel(end);
    }

    /** {@code ChargingInteraction}: one operation branching by charge level, plus failed. */
    private void compileCharging(ChargingInteraction node, int nodeId, int depth) {
        Map<Float, Integer> charged = node.chargedNext;
        List<Float> keys = charged != null
                ? charged.keySet().stream().sorted().toList() : List.of();

        OpLabel end = new OpLabel();
        OpLabel[] labels = newLabels(keys.size() + 1);
        addOperation(node, nodeId, labels);
        jump(end);
        for (int i = 0; i < keys.size(); i++) {
            resolveLabel(labels[i]);
            compileNode(charged.get(keys.get(i)), depth + 1);
            jump(end);
        }
        resolveLabel(labels[keys.size()]);
        if (node.failed != Integer.MIN_VALUE) {
            compileNode(node.failed, depth + 1);
        }
        resolveLabel(end);
    }

    /** {@code SerialInteraction}: compiles its children back to back, no own operation. */
    private void compileSerial(SerialInteraction node, int depth) {
        if (node.serialInteractions != null) {
            for (int childId : node.serialInteractions) {
                compileNode(childId, depth + 1);
            }
        }
    }

    /** {@code ApplyForceInteraction}: one operation, 3 branches (next / ground / collision). */
    private void compileApplyForce(ApplyForceInteraction node, int nodeId, int depth) {
        OpLabel[] labels = newLabels(3);
        addOperation(node, nodeId, labels);
        OpLabel end = new OpLabel();
        resolveBranch(node.next, labels[0], end, depth);
        resolveBranch(node.groundNext != Integer.MIN_VALUE ? node.groundNext : node.next,
                labels[1], end, depth);
        resolveBranch(node.collisionNext != Integer.MIN_VALUE ? node.collisionNext : node.next,
                labels[2], end, depth);
        resolveLabel(end);
    }

    /** {@code MovementConditionInteraction}: one operation, 9 directional branches. */
    private void compileMovementCondition(MovementConditionInteraction node, int nodeId, int depth) {
        OpLabel[] labels = newLabels(9);
        addOperation(node, nodeId, labels);
        OpLabel end = new OpLabel();
        resolveBranch(node.failed, labels[0], end, depth);
        resolveBranch(node.forward, labels[1], end, depth);
        resolveBranch(node.back, labels[2], end, depth);
        resolveBranch(node.left, labels[3], end, depth);
        resolveBranch(node.right, labels[4], end, depth);
        resolveBranch(node.forwardLeft, labels[5], end, depth);
        resolveBranch(node.forwardRight, labels[6], end, depth);
        resolveBranch(node.backLeft, labels[7], end, depth);
        resolveBranch(node.backRight, labels[8], end, depth);
        resolveLabel(end);
    }

    /** The server's {@code resolve(builder, id, label, endLabel)} helper. */
    private void resolveBranch(int childId, OpLabel label, OpLabel end, int depth) {
        resolveLabel(label);
        if (childId != Integer.MIN_VALUE) {
            compileNode(childId, depth + 1);
        }
        jump(end);
    }

    // ------------------------------------------------------------------
    // OperationsBuilder-equivalent primitives
    // ------------------------------------------------------------------

    private static OpLabel[] newLabels(int n) {
        OpLabel[] labels = new OpLabel[n];
        for (int i = 0; i < n; i++) {
            labels[i] = new OpLabel();
        }
        return labels;
    }

    private void addOperation(Interaction node, int id, OpLabel... labels) {
        ops.add(new FlatOp.Node(node, id, labels.length == 0 ? NO_LABELS : labels));
    }

    private void jump(OpLabel target) {
        ops.add(new FlatOp.Jump(target));
    }

    private void resolveLabel(OpLabel label) {
        if (label.resolved()) {
            throw new IllegalStateException("label already resolved");
        }
        label.index = ops.size();
    }
}
