package meridian.core.impl.interaction;

import java.util.List;
import meridian.protocol.BlockConditionInteraction;
import meridian.protocol.ConditionInteraction;
import meridian.protocol.Interaction;
import meridian.protocol.ReplaceInteraction;

/**
 * A root interaction flattened into an indexed operation list — the proxy-side
 * equivalent of the server's {@code RootInteraction.operations}.
 *
 * <p>The index of a {@link FlatOp} in {@link #ops} is its {@code operationCounter}
 * — the value the server validates in {@code InteractionEntry.setClientState}.
 *
 * @param rootId the root interaction's id in the {@code UpdateRootInteractions}
 *               registry
 * @param ops    the flattened operations, in execution-list order
 */
record CompiledInteraction(int rootId, List<FlatOp> ops) {

    FlatOp op(int counter) {
        return counter >= 0 && counter < ops.size() ? ops.get(counter) : null;
    }

    int size() {
        return ops.size();
    }

    /** Human-readable dump — for diagnostics. */
    String describe() {
        StringBuilder sb = new StringBuilder("CompiledInteraction(root=").append(rootId)
                .append(", ").append(ops.size()).append(" ops)");
        for (int i = 0; i < ops.size(); i++) {
            sb.append("\n  ").append(i).append(": ");
            switch (ops.get(i)) {
                case FlatOp.Node n -> {
                    sb.append(n.interaction().getClass().getSimpleName())
                            .append(" #").append(n.interactionId());
                    if (n.labels().length > 0) {
                        sb.append(" labels=[");
                        for (int l = 0; l < n.labels().length; l++) {
                            if (l > 0) sb.append(',');
                            sb.append(n.labels()[l].index);
                        }
                        sb.append(']');
                    }
                    details(sb, n.interaction());
                }
                case FlatOp.Jump j -> sb.append("jump -> ").append(j.target().index);
            }
        }
        return sb.toString();
    }

    /** Appends the decision-relevant fields of a node — what the simulator branches on. */
    private static void details(StringBuilder sb, Interaction node) {
        sb.append(" wait=").append(node.waitForDataFrom).append(" rt=").append(node.runTime);
        if (node instanceof ConditionInteraction c) {
            sb.append(" {");
            if (c.requiredGameMode != null) sb.append(" gameMode=").append(c.requiredGameMode);
            if (c.jumping != null) sb.append(" jumping=").append(c.jumping);
            if (c.swimming != null) sb.append(" swimming=").append(c.swimming);
            if (c.crouching != null) sb.append(" crouching=").append(c.crouching);
            if (c.running != null) sb.append(" running=").append(c.running);
            if (c.flying != null) sb.append(" flying=").append(c.flying);
            sb.append(" }");
        } else if (node instanceof ReplaceInteraction r) {
            sb.append(" {var='").append(r.variable).append("' default=#")
                    .append(r.defaultValue).append('}');
        } else if (node instanceof BlockConditionInteraction b) {
            sb.append(" {matchers=").append(b.matchers == null ? 0 : b.matchers.length).append('}');
        }
    }
}
