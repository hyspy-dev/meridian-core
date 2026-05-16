package meridian.core.impl.interaction;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.BlockPos;
import meridian.core.api.InteractionControl;
import meridian.core.impl.ChunkTracker;
import meridian.core.impl.InteractionRegistry;
import meridian.core.impl.InventoryTracker;
import meridian.core.impl.WorldStateImpl;
import meridian.protocol.BlockType;
import meridian.protocol.BlockFace;
import meridian.protocol.BlockPosition;
import meridian.protocol.InteractionChainData;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InteractionType;
import meridian.protocol.MovementStates;
import meridian.protocol.RootInteraction;
import meridian.protocol.packets.interaction.SyncInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChains;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live {@link InteractionControl} — builds and sends forged
 * {@code SyncInteractionChains}.
 *
 * <p>A {@code Use} chain is sent bare ({@code interactionData = null}): the
 * server self-runs it, so harvesting needs nothing more. A {@code Secondary}
 * chain (water / plant) carries a populated {@code interactionData} — its
 * charge / place operations require client data — produced by flattening the
 * root interaction ({@link InteractionFlattener}) and simulating it
 * ({@link InteractionSimulator}).
 *
 * <p><b>Capture-and-replay.</b> The interaction VM cannot yet derive which
 * branches a chain takes without world state, so the primary path is to replay
 * a real {@code initial=true} chain the player performed — observed via
 * {@link InteractionObserver}, re-sent with a fresh {@code chainId} and the
 * block retargeted. The VM ({@link InteractionFlattener} +
 * {@link InteractionSimulator}) is the fallback when no capture exists yet.
 */
public final class InteractionControlImpl implements InteractionControl {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** What the simulator should build for the chain's {@code interactionData}. */
    private enum DataKind { NONE, BLOCK, PLACEMENT }

    private final InteractionRegistry registry;
    private final InventoryTracker inventory;
    private final ChunkTracker chunks;
    private final WorldStateImpl worldState;

    private volatile ProxySession session;
    private volatile BlockPosition targetBlock;
    /** The player's last observed movement state — what {@code ConditionInteraction} tests. */
    private volatile MovementStates movementStates;
    private final AtomicInteger chainId = new AtomicInteger(0);
    /** interaction type &rarr; root id, learned from the player's own chains. */
    private final Map<InteractionType, Integer> observedRoot = new ConcurrentHashMap<>();
    /** interaction type &rarr; the root a {@code ReplaceInteraction} switched into. */
    private final Map<InteractionType, Integer> observedReplacement = new ConcurrentHashMap<>();
    /** interaction type &rarr; last real {@code initial=true} chain, for replay. */
    private final Map<InteractionType, SyncInteractionChain> capturedChain = new ConcurrentHashMap<>();
    /** Root ids already dumped to the log (VM-development diagnostics). */
    private final Set<Integer> dumpedRoots = ConcurrentHashMap.newKeySet();

    public InteractionControlImpl(InteractionRegistry registry, InventoryTracker inventory,
                                  ChunkTracker chunks, WorldStateImpl worldState) {
        this.registry = registry;
        this.inventory = inventory;
        this.chunks = chunks;
        this.worldState = worldState;
    }

    /** Creates the C2S observer that feeds this control — register at MONITOR. */
    public PacketHandler newObserver() {
        return new InteractionObserver(this);
    }

    // ------------------------------------------------------------------
    // Fed by InteractionObserver
    // ------------------------------------------------------------------

    void bind(ProxySession session) {
        this.session = session;
    }

    void onTargetBlock(BlockPosition block) {
        if (block != null) {
            this.targetBlock = block;
        }
    }

    /** Tracks the player's movement state — {@code ConditionInteraction}'s input. */
    void onMovementStates(MovementStates states) {
        if (states != null) {
            this.movementStates = states;
        }
    }

    /**
     * Learns from the player's own chains: the chainId high-water mark, each
     * interaction type's root id, and — for replay — the last complete
     * {@code initial=true} chain of each type.
     */
    void onClientChain(SyncInteractionChain chain) {
        if (chain.chainId > chainId.get()) {
            chainId.set(chain.chainId);
        }
        if (chain.initial && chain.interactionData != null && chain.interactionData.length > 0) {
            Integer initialRoot = null;
            int replacement = InteractionContext.NO_REPLACEMENT;
            for (InteractionSyncData d : chain.interactionData) {
                if (d != null) {
                    if (initialRoot == null) {
                        initialRoot = d.rootInteraction;
                        observedRoot.put(chain.interactionType, initialRoot);
                    } else if (d.rootInteraction != initialRoot
                            && replacement == InteractionContext.NO_REPLACEMENT) {
                        // A ReplaceInteraction switched the chain into a second
                        // root (e.g. Seed_Condition -> 3385).
                        replacement = d.rootInteraction;
                    }
                    dumpRootTree(d.rootInteraction);
                }
            }
            if (replacement != InteractionContext.NO_REPLACEMENT) {
                observedReplacement.put(chain.interactionType, replacement);
            }
            capturedChain.put(chain.interactionType, chain);
            log.info("meridian-core: captured {} chain template ({} ops)",
                    chain.interactionType, chain.interactionData.length);
            validateVm(chain);
        }
    }

    /**
     * Runs the VM against an observed chain and logs whether it reproduces the
     * real operation sequence — the capture is the simulator's test oracle.
     */
    private void validateVm(SyncInteractionChain captured) {
        if (captured.data == null || captured.data.blockPosition == null) {
            return;
        }
        Integer rootId = observedRoot.get(captured.interactionType);
        if (rootId == null) {
            return;
        }
        CompiledInteraction compiled = compileRoot(rootId);
        if (compiled == null) {
            return;
        }
        // The captured chain itself reveals the ReplaceInteraction target —
        // the first operation whose root differs from the initial one.
        InteractionContext ctx = contextFor(captured.interactionType,
                captured.data.blockPosition, DataKind.BLOCK, replacementRootOf(captured));
        List<InteractionSyncData> vm = InteractionSimulator.simulate(
                compiled, ctx, this::compileRoot);

        StringBuilder real = new StringBuilder();
        StringBuilder sim = new StringBuilder();
        boolean match = true;
        for (int i = 0; i < captured.interactionData.length; i++) {
            InteractionSyncData d = captured.interactionData[i];
            if (d == null) {
                continue;
            }
            real.append(d.operationCounter).append(' ');
            InteractionSyncData s = i < vm.size() ? vm.get(i) : null;
            sim.append(s == null ? "_" : s.operationCounter).append(' ');
            if (s == null || s.operationCounter != d.operationCounter) {
                match = false;
            }
        }
        log.info("meridian-core: VM check {} root {} — real=[{}] vm=[{}] {}",
                captured.interactionType, rootId, real.toString().trim(),
                sim.toString().trim(), match ? "MATCH" : "MISMATCH");
    }

    /** Builds a simulator context: resolves the target block type via the trackers. */
    private InteractionContext contextFor(InteractionType type, BlockPosition target,
                                          DataKind dataKind, int replacementRoot) {
        BlockType blockType = null;
        if (target != null) {
            int id = chunks.blockIdAt(target.x, target.y, target.z);
            if (id > 0) {
                blockType = worldState.blockTypeById(id);
            }
        }
        MovementStates movement = movementStates;
        return dataKind == DataKind.PLACEMENT
                ? InteractionContext.ofPlacement(type, target, blockType, BlockFace.Up,
                        movement, replacementRoot)
                : InteractionContext.ofBlock(type, target, blockType, BlockFace.Up,
                        movement, replacementRoot);
    }

    /** Flattens a registry root into its operation list, or {@code null} if absent. */
    private CompiledInteraction compileRoot(int rootId) {
        RootInteraction root = registry.root(rootId).orElse(null);
        if (root == null) {
            return null;
        }
        return InteractionFlattener.compile(
                rootId, root.interactions, id -> registry.interaction(id).orElse(null));
    }

    /** The first operation root in {@code chain} that differs from the initial one. */
    private static int replacementRootOf(SyncInteractionChain chain) {
        Integer initial = null;
        for (InteractionSyncData d : chain.interactionData) {
            if (d == null) {
                continue;
            }
            if (initial == null) {
                initial = d.rootInteraction;
            } else if (d.rootInteraction != initial) {
                return d.rootInteraction;
            }
        }
        return InteractionContext.NO_REPLACEMENT;
    }

    /** Flattens a root once and logs its operation tree — VM-development diagnostics. */
    private void dumpRootTree(int rootId) {
        if (!dumpedRoots.add(rootId)) {
            return;
        }
        RootInteraction root = registry.root(rootId).orElse(null);
        CompiledInteraction compiled = compileRoot(rootId);
        if (root == null || compiled == null) {
            log.warn("meridian-core: cannot dump root {} — not in registry", rootId);
            return;
        }
        log.info("meridian-core: VM dump — root '{}' #{}\n{}",
                root.id, rootId, compiled.describe());
    }

    // ------------------------------------------------------------------
    // InteractionControl
    // ------------------------------------------------------------------

    @Override
    public boolean available() {
        return session != null;
    }

    @Override
    public Optional<BlockPos> targetedBlock() {
        BlockPosition b = targetBlock;
        return b == null ? Optional.empty() : Optional.of(new BlockPos(b.x, b.y, b.z));
    }

    @Override
    public void useOnBlock(BlockPos pos) {
        // Harvest interactions are requiresClient — the server waits for per-op
        // client data, so a bare chain is not enough. Build it via the VM.
        forge(pos, InteractionType.Use, DataKind.BLOCK);
    }

    @Override
    public void waterBlock(BlockPos pos) {
        forge(pos, InteractionType.Secondary, DataKind.BLOCK);
    }

    @Override
    public void plantOnBlock(BlockPos pos) {
        forge(pos, InteractionType.Secondary, DataKind.PLACEMENT);
    }

    // ------------------------------------------------------------------
    // Forge
    // ------------------------------------------------------------------

    private void forge(BlockPos pos, InteractionType type, DataKind dataKind) {
        ProxySession s = session;
        if (s == null) {
            log.warn("meridian-core: interaction {} requested but no session yet", type);
            return;
        }

        // Chunk-decode verification: block ids around the target.
        log.info("meridian-core: blocks at ({},{},{}) — target={}, below={}, above={}",
                pos.x(), pos.y(), pos.z(),
                chunks.blockIdAt(pos.x(), pos.y(), pos.z()),
                chunks.blockIdAt(pos.x(), pos.y() - 1, pos.z()),
                chunks.blockIdAt(pos.x(), pos.y() + 1, pos.z()));

        SyncInteractionChain template = capturedChain.get(type);
        SyncInteractionChain chain = template != null
                ? retargetCaptured(template, pos)
                : buildFromVm(pos, type, dataKind);
        if (chain == null) {
            return; // nothing to send yet — reason already logged
        }

        SyncInteractionChains packet = new SyncInteractionChains();
        packet.updates = new SyncInteractionChain[] {chain};
        s.sendToServer(packet);
        log.info("meridian-core: forged {} chain {} at ({},{},{}) — {}",
                type, chain.chainId, pos.x(), pos.y(), pos.z(),
                template != null ? "replay" : "vm");
    }

    /**
     * Clones a captured chain, gives it a fresh {@code chainId} and current
     * inventory state, and retargets every block position onto {@code pos}.
     */
    private SyncInteractionChain retargetCaptured(SyncInteractionChain template, BlockPos pos) {
        SyncInteractionChain c = template.clone();
        c.chainId = chainId.incrementAndGet();
        c.activeHotbarSlot = inventory.activeHotbarSlot();
        c.activeUtilitySlot = inventory.activeUtilitySlot();
        c.activeToolsSlot = inventory.activeToolsSlot();
        c.itemInHandId = inventory.itemInHandId();
        c.utilityItemId = inventory.utilityItemId();
        c.toolsItemId = inventory.toolsItemId();
        c.equipSlot = inventory.activeHotbarSlot();

        if (c.data != null) {
            c.data = c.data.clone();
            if (c.data.blockPosition != null) {
                c.data.blockPosition = new BlockPosition(pos.x(), pos.y(), pos.z());
            }
        }
        if (c.interactionData != null) {
            InteractionSyncData[] ops = new InteractionSyncData[c.interactionData.length];
            for (int i = 0; i < ops.length; i++) {
                InteractionSyncData src = c.interactionData[i];
                if (src == null) {
                    continue;
                }
                ops[i] = src.clone();
                // Operations that carried a block position targeted the
                // interacted block — retarget them too. (A placed block sits
                // one above the soil; replay keeps the captured offset.)
                if (src.blockPosition != null) {
                    int dy = src.blockPosition.y - templateTargetY(template);
                    ops[i].blockPosition = new BlockPosition(pos.x(), pos.y() + dy, pos.z());
                }
            }
            c.interactionData = ops;
        }
        return c;
    }

    /** The y of the block the captured chain originally targeted. */
    private static int templateTargetY(SyncInteractionChain template) {
        return template.data != null && template.data.blockPosition != null
                ? template.data.blockPosition.y : 0;
    }

    /** Fallback: build the chain from the interaction VM (no capture available). */
    private SyncInteractionChain buildFromVm(BlockPos pos, InteractionType type, DataKind dataKind) {
        SyncInteractionChain chain = new SyncInteractionChain();
        chain.activeHotbarSlot = inventory.activeHotbarSlot();
        chain.activeUtilitySlot = inventory.activeUtilitySlot();
        chain.activeToolsSlot = inventory.activeToolsSlot();
        chain.itemInHandId = inventory.itemInHandId();
        chain.utilityItemId = inventory.utilityItemId();
        chain.toolsItemId = inventory.toolsItemId();
        chain.initial = true;
        chain.desync = false;
        chain.overrideRootInteraction = Integer.MIN_VALUE;
        chain.interactionType = type;
        chain.equipSlot = inventory.activeHotbarSlot();
        chain.chainId = chainId.incrementAndGet();
        chain.forkedId = null;
        chain.state = InteractionState.Finished;
        chain.newForks = null;
        chain.operationBaseIndex = 0;

        InteractionChainData data = new InteractionChainData();
        data.entityId = -1;
        data.proxyId = new UUID(0L, 0L);
        data.blockPosition = new BlockPosition(pos.x(), pos.y(), pos.z());
        data.targetSlot = Integer.MIN_VALUE;
        chain.data = data;

        if (dataKind == DataKind.NONE) {
            chain.interactionData = null;
        } else {
            chain.interactionData = buildInteractionData(type, dataKind);
            if (chain.interactionData == null) {
                return null; // root not learned yet — logged in buildInteractionData
            }
        }
        return chain;
    }

    /** Flattens + simulates the root for {@code type} into {@code InteractionSyncData[]}. */
    private InteractionSyncData[] buildInteractionData(InteractionType type, DataKind dataKind) {
        Integer rootId = observedRoot.get(type);
        if (rootId == null) {
            log.warn("meridian-core: no observed root for {} yet — perform the action "
                    + "once manually so core can learn it", type);
            return null;
        }
        CompiledInteraction compiled = compileRoot(rootId);
        if (compiled == null) {
            log.warn("meridian-core: root {} not in registry", rootId);
            return null;
        }

        int replacement = observedReplacement.getOrDefault(type, InteractionContext.NO_REPLACEMENT);
        InteractionContext ctx = contextFor(type, targetBlock, dataKind, replacement);
        List<InteractionSyncData> ops = InteractionSimulator.simulate(
                compiled, ctx, this::compileRoot);
        log.info("meridian-core: simulated root {} ({} ops) for {}", rootId, ops.size(), type);
        return ops.toArray(new InteractionSyncData[0]);
    }
}
