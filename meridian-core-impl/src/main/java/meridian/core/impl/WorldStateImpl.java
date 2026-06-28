package meridian.core.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import meridian.api.module.Scheduler;
import meridian.api.session.ProxySession;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.core.api.WorldState;
import meridian.protocol.BlockPlacementRotationMode;
import meridian.protocol.BlockType;
import meridian.protocol.UpdateType;
import meridian.protocol.VariantRotation;
import meridian.protocol.packets.assets.UpdateBlockTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live {@link WorldState} — the first real meridian-core service.
 *
 * <p>Two layers: <em>server-truth</em> (block types as the server declared them,
 * built from observed S2C {@code UpdateBlockTypes}) and an <em>override</em> map
 * supplied by modules.
 *
 * <p><b>Coalesced emit.</b> A consumer like xray calls {@link #overrideBlockType}
 * once per block — hundreds of calls in a burst. Emitting one
 * {@code UpdateBlockTypes} per call floods the client with hundreds of
 * full-rebuild packets (frozen UI, flickering world). Instead, every override
 * change marks its id dirty and schedules a single coalesced flush, so a whole
 * {@code refresh()} collapses into <em>one</em> {@code UpdateBlockTypes(AddOrUpdate)}.
 * The emit goes through {@link ProxySession#sendToClient}, which bypasses the
 * handler chain so the MONITOR observer never re-ingests it.
 *
 * <p>v0.1.0 covers the block-type catalog only. {@link #blockTypeAt} and
 * {@link #ghostBlock} need chunk tracking and are not implemented yet.
 */
public final class WorldStateImpl implements WorldState {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** How long override changes are gathered before a single emit. */
    private static final Duration COALESCE_DELAY = Duration.ofMillis(60);

    private final Scheduler scheduler;
    private final Map<Integer, BlockType> serverTruth = new ConcurrentHashMap<>();
    private final Map<Integer, UnaryOperator<BlockView>> overrides = new ConcurrentHashMap<>();
    private final Set<Integer> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private volatile ProxySession session;
    private volatile int maxId;

    WorldStateImpl(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Invoked by the MONITOR observer for every S2C {@code UpdateBlockTypes}. */
    void onServerBlockTypes(UpdateBlockTypes update, ProxySession session) {
        this.session = session;
        if (update.blockTypes != null) {
            // Freshly-deserialised POJOs — store directly, nobody else mutates them.
            serverTruth.putAll(update.blockTypes);
        }
        maxId = Math.max(maxId, update.maxId);
        log.info("meridian-core: observed {} block type(s), maxId={}",
                update.blockTypes != null ? update.blockTypes.size() : 0, maxId);
        // Re-apply standing overrides to freshly-arrived ids (coalesced).
        for (Integer id : overrides.keySet()) {
            markDirty(id);
        }
    }

    /** Raw server-truth block type by id, or {@code null} if unknown — for Layer-1 use. */
    public BlockType blockTypeById(int id) {
        return serverTruth.get(id);
    }

    /**
     * The {@link BlockPlacementRotationMode} of the block whose item key is
     * {@code itemKey} — i.e. how the block this item places is oriented. Found by
     * matching {@link BlockType#item} in the server catalog; {@code Default} if no
     * block carries that item or the catalog hasn't arrived. Layer-1 use, for
     * reproducing the client's placement rotation.
     */
    public BlockPlacementRotationMode placementRotationModeForItem(String itemKey) {
        if (itemKey != null) {
            for (BlockType bt : serverTruth.values()) {
                if (itemKey.equals(bt.item) && bt.placementSettings != null) {
                    return bt.placementSettings.rotationMode;
                }
            }
        }
        return BlockPlacementRotationMode.Default;
    }

    /**
     * The {@link BlockPlacementRotationMode} of block id {@code blockId} — a direct
     * catalog lookup (more reliable than matching by item key). {@code Default} if
     * the id is unknown or carries no placement settings.
     */
    public BlockPlacementRotationMode placementRotationModeForBlock(int blockId) {
        BlockType bt = serverTruth.get(blockId);
        return bt != null && bt.placementSettings != null
                ? bt.placementSettings.rotationMode : BlockPlacementRotationMode.Default;
    }

    /**
     * The {@link VariantRotation} of block id {@code blockId} — how the block can
     * be oriented (None / Pipe / UpDown / NESW / …). A non-nullable fixed field of
     * {@code BlockType}, so it parses reliably (unlike {@code placementSettings}).
     * This is what actually drives a log/pillar's per-axis orientation.
     * {@code None} if the id is unknown.
     */
    public VariantRotation variantRotationForBlock(int blockId) {
        BlockType bt = serverTruth.get(blockId);
        return bt != null && bt.variantRotation != null ? bt.variantRotation : VariantRotation.None;
    }

    /**
     * The current state name of block id {@code blockId} — the {@link BlockType#states}
     * key whose value (a sibling block index) equals {@code blockId}, or
     * {@code "default"} (the server's {@code StateData.NULL_STATE_ID}) when the type
     * declares no states. Mirrors the server's {@code BlockType.getStateForBlock}.
     *
     * <p>State is encoded as a distinct block id within a family — a lamp's
     * {@code on}/{@code off}, a crop's growth stage — so the same {@code blockType}
     * resolves a different name per id. A pure function of its inputs; shared by the
     * interaction {@code BlockCondition} matchers and the {@code BlockView#state()}
     * projection so both read the state identically. Layer-1 use.
     */
    public static String stateNameForBlock(BlockType blockType, int blockId) {
        if (blockType != null && blockType.states != null) {
            for (Map.Entry<String, Integer> e : blockType.states.entrySet()) {
                Integer idx = e.getValue();
                if (idx != null && idx == blockId) {
                    return e.getKey();
                }
            }
        }
        return "default";
    }

    @Override
    public Collection<BlockView> allBlockTypes() {
        List<BlockView> out = new ArrayList<>(serverTruth.size());
        for (Map.Entry<Integer, BlockType> e : serverTruth.entrySet()) {
            // Read-only view — BlockViewImpl.with* clones before mutating, so
            // wrapping server-truth directly here is safe and avoids a deep clone
            // per block on every call.
            out.add(new BlockViewImpl(e.getKey(), e.getValue()));
        }
        return List.copyOf(out);
    }

    @Override
    public BlockView blockTypeAt(BlockPos pos) {
        throw new UnsupportedOperationException(
                "blockTypeAt requires chunk tracking — not implemented in meridian-core v0.1.0 "
                        + "(block-type catalog only)");
    }

    @Override
    public void overrideBlockType(int id, UnaryOperator<BlockView> transform) {
        overrides.put(id, transform);
        markDirty(id);
    }

    @Override
    public void clearOverride(int id) {
        if (overrides.remove(id) != null) {
            markDirty(id);
        }
    }

    @Override
    public void ghostBlock(BlockPos pos, BlockView view, Duration ttl) {
        throw new UnsupportedOperationException(
                "ghostBlock — not implemented in meridian-core v0.1.0");
    }

    // ------------------------------------------------------------------
    // Coalesced emit
    // ------------------------------------------------------------------

    /** Marks an id for re-emit and ensures exactly one flush is scheduled. */
    private void markDirty(int id) {
        dirty.add(id);
        if (flushScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::flush, COALESCE_DELAY);
        }
    }

    /** Emits ONE {@code UpdateBlockTypes} covering every id touched since the last flush. */
    private void flush() {
        flushScheduled.set(false);
        Set<Integer> ids = new HashSet<>(dirty);
        dirty.removeAll(ids);
        emit(ids);
    }

    private void emit(Collection<Integer> ids) {
        ProxySession s = session;
        if (s == null || ids.isEmpty() || serverTruth.isEmpty()) return;

        Map<Integer, BlockType> changed = new HashMap<>();
        for (int id : ids) {
            BlockType truth = serverTruth.get(id);
            if (truth == null) continue;
            UnaryOperator<BlockView> override = overrides.get(id);
            if (override != null) {
                BlockView result = override.apply(new BlockViewImpl(id, truth.clone()));
                changed.put(id, ((BlockViewImpl) result).toBlockType());
            } else {
                // Override cleared — restore server truth.
                changed.put(id, truth.clone());
            }
        }
        if (changed.isEmpty()) return;

        UpdateBlockTypes packet = new UpdateBlockTypes(
                UpdateType.AddOrUpdate, maxId, changed, true, true, true, true);
        s.sendToClient(packet);
        log.info("meridian-core: emitted one UpdateBlockTypes(AddOrUpdate) for {} block id(s)", changed.size());
    }
}
