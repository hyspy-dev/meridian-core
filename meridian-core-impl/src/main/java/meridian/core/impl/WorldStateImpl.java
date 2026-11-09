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
 * That one carries only the ids whose look actually changed: each dirty id's new view is
 * measured against what the client holds ({@link #shown}), so a rule set again, or replaced by
 * one that touches the same few types, sends those few - not the whole catalog.
 * The emit goes through {@link ProxySession#sendToClient}, which bypasses the
 * handler chain so the MONITOR observer never re-ingests it.
 *
 * <p>v0.1.0 covers the block-type catalog only. {@link #blockTypeAt} and
 * {@link #ghostBlock} need chunk tracking and are not implemented yet.
 */
public final class WorldStateImpl implements WorldState {
    /** An item id's categories, from the server's item catalog - what a block view lists. */
    private volatile java.util.function.Function<String, java.util.List<String>> itemCategories =
            BlockViewImpl.NO_ITEMS;

    /** Where a block's item categories come from: the item catalog. */
    void itemCategories(java.util.function.Function<String, java.util.List<String>> lookup) {
        itemCategories = lookup == null ? BlockViewImpl.NO_ITEMS : lookup;
    }

    java.util.function.Function<String, java.util.List<String>> itemCategories() {
        return itemCategories;
    }

    /**
     * The item catalog has arrived or grown: a block's {@link BlockView#categories()} can read
     * otherwise now, so every rule runs again. The server declares the block types before the
     * items, so a rule that tells blocks apart by kind sees none at the load packet's rewrite,
     * and would stay blind without this. What it changes goes out as one emit, of what changed.
     */
    void itemsArrived() {
        if (!rules.isEmpty()) {
            markAllDirty();
        }
    }

    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** How long override changes are gathered before a single emit. */
    private static final Duration COALESCE_DELAY = Duration.ofMillis(60);

    private final Scheduler scheduler;
    private final Map<Integer, BlockType> serverTruth = new ConcurrentHashMap<>();
    private final Map<Integer, UnaryOperator<BlockView>> overrides = new ConcurrentHashMap<>();
    /** Rules over every type, in registration order; read under its own lock. */
    private final Map<String, UnaryOperator<BlockView>> rules =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private final Set<Integer> dirty = ConcurrentHashMap.newKeySet();
    /**
     * What the client holds for an id where that is not the server's truth: the view it was last
     * sent, by the load packet's rewrite or by an emit. An id absent here shows the truth. What a
     * change is measured against, so only the ids that look different afterwards go out.
     */
    private final Map<Integer, BlockType> shown = new ConcurrentHashMap<>();
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
        // The packet no longer carries maxId (build 272); the highest id seen stands in for the log.
        if (update.blockTypes != null) {
            for (Integer id : update.blockTypes.keySet()) {
                maxId = Math.max(maxId, id);
            }
        }
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
        return BlockView.DEFAULT_STATE;
    }

    /**
     * How many block types the server has sent so far - a cheap way for a caller that caches
     * {@link #allBlockTypes} to notice that there are now more of them.
     */
    int blockTypeCount() {
        return serverTruth.size();
    }

    @Override
    public Collection<BlockView> allBlockTypes() {
        List<BlockView> out = new ArrayList<>(serverTruth.size());
        for (Map.Entry<Integer, BlockType> e : serverTruth.entrySet()) {
            // Read-only view — BlockViewImpl.with* clones before mutating, so
            // wrapping server-truth directly here is safe and avoids a deep clone
            // per block on every call.
            out.add(new BlockViewImpl(e.getKey(), e.getValue(), itemCategories));
        }
        return List.copyOf(out);
    }

    /** Name &rarr; id, with the count of types it was built from: rebuilt when more types arrive. */
    private record NameIndex(int from, Map<String, Integer> ids) {}

    private volatile NameIndex idsByName = new NameIndex(-1, Map.of());

    @Override
    public java.util.OptionalInt blockId(String name) {
        if (name == null) {
            return java.util.OptionalInt.empty();
        }
        if (BlockView.EMPTY.equals(name)) {
            return java.util.OptionalInt.of(0);
        }
        NameIndex index = idsByName;
        int count = serverTruth.size();
        if (index.from() != count) {
            Map<String, Integer> built = new HashMap<>(count * 2);
            for (Map.Entry<Integer, BlockType> e : serverTruth.entrySet()) {
                String n = e.getValue().name;
                // Two types of one name: the lower id, as the api's own answer has it.
                if (n != null) {
                    built.merge(n, e.getKey(), Math::min);
                }
            }
            index = new NameIndex(count, Map.copyOf(built));
            idsByName = index;
        }
        Integer id = index.ids().get(name);
        return id == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(id);
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
    public void overrideAllBlockTypes(String key, UnaryOperator<BlockView> rule) {
        rules.put(key, rule);
        markAllDirty();
    }

    @Override
    public void clearOverrideAll(String key) {
        if (rules.remove(key) != null) {
            markAllDirty();
        }
    }

    /**
     * The client's view of one server-truth type: every rule, then the id's own override.
     * Returns {@code truth} itself when nothing applies, so a caller can tell. Never touches
     * {@code truth}: the view clones before it changes anything.
     */
    BlockType clientView(int id, BlockType truth) {
        BlockView view = new BlockViewImpl(id, truth, itemCategories);
        List<UnaryOperator<BlockView>> active;
        synchronized (rules) {
            active = new ArrayList<>(rules.values());
        }
        for (UnaryOperator<BlockView> rule : active) {
            view = rule.apply(view);
        }
        UnaryOperator<BlockView> override = overrides.get(id);
        if (override != null) {
            view = override.apply(view);
        }
        BlockType seen = ((BlockViewImpl) view).toBlockType();
        // A rule that rebuilt the type without changing how it looks has changed nothing.
        return BlockViewImpl.sameLook(seen, truth) ? truth : seen;
    }

    /**
     * The client has been given {@code seen} for {@code id} straight from the server's packet - the
     * load rewrite - so that is what later changes are measured against.
     */
    void shownByServerPacket(int id, BlockType seen, BlockType truth) {
        if (seen == null || seen == truth) {
            shown.remove(id);
        } else {
            shown.put(id, seen);
        }
    }

    /** The server took a type away; the client holds nothing of ours for it any more. */
    void forgetShown(int id) {
        shown.remove(id);
    }

    private void markAllDirty() {
        for (Integer id : serverTruth.keySet()) {
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
            // Rules + the id's override; with neither this is server truth again.
            BlockType view = clientView(id, truth);
            BlockType held = shown.get(id);
            if (view == truth) {
                if (held == null) continue;           // shows the truth, and stays so
                shown.remove(id);
            } else {
                if (held != null && BlockViewImpl.sameLook(held, view)) continue;   // already so
                shown.put(id, view);
            }
            changed.put(id, view);
        }
        if (changed.isEmpty()) return;

        UpdateBlockTypes packet = new UpdateBlockTypes(
                UpdateType.AddOrUpdate, changed, true, true, true, true);
        s.sendToClient(packet);
        log.info("meridian-core: emitted one UpdateBlockTypes(AddOrUpdate) for {} block id(s)", changed.size());
    }
}
