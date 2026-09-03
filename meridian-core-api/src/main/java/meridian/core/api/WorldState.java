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

    /**
     * Applies a client-view transform to <em>every</em> block type, the ones the server has
     * declared and the ones it declares later - including while a world loads, which is the
     * one moment the client binds a type to its textures. A per-id {@link #overrideBlockType}
     * runs after the rules. Rules run in registration order; returning the input unchanged
     * leaves a type alone. {@code key} names the rule so its owner can replace or remove it.
     */
    void overrideAllBlockTypes(String key, UnaryOperator<BlockView> rule);

    /** Removes the rule registered under {@code key}; a no-op for an unknown key. */
    void clearOverrideAll(String key);

    /** Shows a client-only block at {@code pos} for {@code ttl} (builder rulers). */
    void ghostBlock(BlockPos pos, BlockView view, Duration ttl);
}
