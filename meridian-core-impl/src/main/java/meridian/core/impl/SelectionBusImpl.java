package meridian.core.impl;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import meridian.core.api.BlockPos;
import meridian.core.api.SelectionBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live {@link SelectionBus} — copy-on-write listener lists, publish on the
 * calling thread. Subscribers that touch Swing must hop to the EDT themselves.
 */
final class SelectionBusImpl implements SelectionBus {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    private final CopyOnWriteArrayList<Consumer<BlockPos>> blockListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<IntConsumer> entityListeners = new CopyOnWriteArrayList<>();
    private volatile BlockPos lastBlock;
    private volatile int lastEntity = Integer.MIN_VALUE;

    @Override
    public void publishBlock(BlockPos pos) {
        if (pos == null) return;
        lastBlock = pos;
        for (Consumer<BlockPos> l : blockListeners) {
            try {
                l.accept(pos);
            } catch (Throwable t) {
                // One bad subscriber must not break the fan-out.
                log.warn("meridian-core: block-selection listener threw", t);
            }
        }
    }

    @Override
    public void publishEntity(int entityId) {
        lastEntity = entityId;
        for (IntConsumer l : entityListeners) {
            try {
                l.accept(entityId);
            } catch (Throwable t) {
                log.warn("meridian-core: entity-selection listener threw", t);
            }
        }
    }

    @Override
    public AutoCloseable onBlockSelected(Consumer<BlockPos> listener) {
        blockListeners.add(listener);
        return () -> blockListeners.remove(listener);
    }

    @Override
    public AutoCloseable onEntitySelected(IntConsumer listener) {
        entityListeners.add(listener);
        return () -> entityListeners.remove(listener);
    }

    @Override
    public Optional<BlockPos> lastBlock() {
        return Optional.ofNullable(lastBlock);
    }

    @Override
    public OptionalInt lastEntity() {
        int v = lastEntity;
        return v == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(v);
    }
}
