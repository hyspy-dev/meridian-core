package meridian.core.impl.interaction;

import meridian.protocol.Interaction;

/**
 * One slot in a flattened interaction. Its position in the
 * {@link CompiledInteraction} list is the operation's {@code operationCounter}.
 *
 * <p>Mirrors the server's {@code Operation[]}: a {@link Node} is an interaction
 * operation (optionally carrying branch labels — the server's
 * {@code LabelOperation}); a {@link Jump} is an unconditional jump
 * ({@code JumpOperation}).
 */
sealed interface FlatOp {

    /**
     * An interaction operation.
     *
     * @param interaction   the wire interaction node
     * @param interactionId its id in the {@code UpdateInteractions} registry
     * @param labels        branch targets the node selects between at runtime
     *                      (empty for a plain leaf operation)
     */
    record Node(Interaction interaction, int interactionId, OpLabel[] labels) implements FlatOp {}

    /** An unconditional jump to {@code target}. */
    record Jump(OpLabel target) implements FlatOp {}
}
