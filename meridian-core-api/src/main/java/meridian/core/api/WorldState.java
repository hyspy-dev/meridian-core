package meridian.core.api;

import java.time.Duration;
import java.util.Collection;
import java.util.function.UnaryOperator;

/**
 * Block-and-world state of the headless game.
 *
 * <p>Reads return server truth. Mutations ({@link #overrideBlockType},
 * {@link #ghostBlock}) change only the client-view; core diffs and emits the
 * synchronising packet.
 *
 * <p>v0 skeleton: every method throws {@code UnsupportedOperationException}.
 * A real implementation arrives in Phase 5 ({@code overrideBlockType}).
 */
public interface WorldState {

    /** All block types known to the server. */
    Collection<BlockView> allBlockTypes();

    /** Server-truth block type at {@code pos}. */
    BlockView blockTypeAt(BlockPos pos);

    /** Applies a client-view transform to the block type {@code id} (xray, jesus). */
    void overrideBlockType(int id, UnaryOperator<BlockView> transform);

    /** Removes a previously applied override. */
    void clearOverride(int id);

    /** Shows a client-only block at {@code pos} for {@code ttl} (builder rulers). */
    void ghostBlock(BlockPos pos, BlockView view, Duration ttl);
}
