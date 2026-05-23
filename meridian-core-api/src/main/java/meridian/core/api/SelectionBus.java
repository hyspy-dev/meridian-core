package meridian.core.api;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Cross-module pub/sub for "the player picked this block / entity" — a
 * meridian-core Layer-1 service.
 *
 * <p>Lets one module (e.g. ESP's "Nearest blocks" list) point another module
 * (e.g. interaction-test's X/Y/Z fields) at a target without either side
 * importing the other. Publishers call {@link #publishBlock} /
 * {@link #publishEntity}; subscribers receive on the calling thread and are
 * responsible for any UI thread hop they need.
 *
 * <p>Also remembers the last published value so a module that subscribes after
 * a publish can still recover the current selection via {@link #lastBlock} /
 * {@link #lastEntity}.
 */
public interface SelectionBus {

    /** Publishes a block selection — fans out to every block subscriber. */
    void publishBlock(BlockPos pos);

    /** Publishes an entity selection by network id. */
    void publishEntity(int entityId);

    /**
     * Subscribes to block selections. The returned handle removes the
     * subscription when closed; callers that never unsubscribe (typical for
     * a module that lives for the proxy's lifetime) may ignore it.
     */
    AutoCloseable onBlockSelected(Consumer<BlockPos> listener);

    /** Subscribes to entity selections. */
    AutoCloseable onEntitySelected(IntConsumer listener);

    /** Most recently published block, if any. */
    Optional<BlockPos> lastBlock();

    /** Network id of the most recently published entity, if any. */
    OptionalInt lastEntity();
}
