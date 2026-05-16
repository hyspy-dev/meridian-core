package meridian.core.impl.interaction;

import java.util.List;

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
                }
                case FlatOp.Jump j -> sb.append("jump -> ").append(j.target().index);
            }
        }
        return sb.toString();
    }
}
