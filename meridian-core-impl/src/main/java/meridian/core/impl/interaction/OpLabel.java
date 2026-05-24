package meridian.core.impl.interaction;

/**
 * A jump target inside a flattened interaction — mirrors the server's
 * {@code OperationsBuilder.Label}.
 *
 * <p>{@link #index} is the position in the flat operation list. It is
 * {@link Integer#MIN_VALUE} until the builder resolves the label to a concrete
 * slot (a forward reference compiled before its target exists).
 */
final class OpLabel {
    static final int UNRESOLVED = Integer.MIN_VALUE;

    int index = UNRESOLVED;

    boolean resolved() {
        return index != UNRESOLVED;
    }
}
