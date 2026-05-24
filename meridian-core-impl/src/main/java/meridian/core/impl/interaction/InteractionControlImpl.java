package meridian.core.impl.interaction;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import meridian.api.module.Scheduler;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.BlockPos;
import meridian.core.api.InteractionControl;
import meridian.core.impl.ChunkTracker;
import meridian.core.impl.InteractionRegistry;
import meridian.core.impl.InventoryTracker;
import meridian.core.impl.ItemRegistry;
import meridian.core.impl.WorldStateImpl;
import meridian.protocol.BlockType;
import meridian.protocol.BlockFace;
import meridian.protocol.BlockPosition;
import meridian.protocol.ForkedChainId;
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
 * <p><b>Root resolution.</b> The VM entry point — which root interaction to
 * flatten — and the {@code ReplaceInteraction} variables come from the held
 * item's {@link ItemRegistry} asset data, exactly as the server resolves them.
 * Capture-and-replay re-sends a real {@code initial=true} chain the player
 * performed; {@code observedRoot} keeps it as a fallback when the item catalog
 * has not been received yet.
 */
public final class InteractionControlImpl implements InteractionControl {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** What the simulator should build for the chain's {@code interactionData}. */
    private enum DataKind { NONE, BLOCK, PLACEMENT }

    /** Identifies an interaction the player can perform: a type with a held item. */
    private record ActionKey(InteractionType type, String item) {
        static ActionKey of(InteractionType type, String item) {
            return new ActionKey(type, item == null ? "" : item);
        }
    }

    /** One captured packet of a chain, with the wall-clock time it was observed. */
    private record TimedChain(SyncInteractionChain chain, long nanos) {}

    /**
     * A unit of forging work on the serialized queue. {@link #run()} builds and
     * sends the chain, returning its tick-duration — the number of 60&nbsp;Hz
     * packets it spreads over — so the queue knows how long to wait before
     * starting the next forge.
     */
    @FunctionalInterface
    private interface QueuedForge {
        /** Runs the forge; returns its tick-duration (0 if nothing was sent). */
        int run();
    }

    /** A forge waiting on the queue, paired with the future its caller holds. */
    private record QueuedItem(QueuedForge forge, CompletableFuture<Void> done) {}

    private final InteractionRegistry registry;
    private final InventoryTracker inventory;
    private final ChunkTracker chunks;
    private final WorldStateImpl worldState;
    private final ItemRegistry items;
    private final Scheduler scheduler;

    private volatile ProxySession session;
    private volatile BlockPosition targetBlock;
    /** The player's last observed movement state — what {@code ConditionInteraction} tests. */
    private volatile MovementStates movementStates;
    /** Chain-id NAT: keeps forged chain ids out of the client's counter space. */
    private final ChainIdNat nat = new ChainIdNat();
    /** (type, held item) &rarr; root id — a fallback for the {@link ItemRegistry}. */
    private final Map<ActionKey, Integer> observedRoot = new ConcurrentHashMap<>();
    /**
     * (type, held item) &rarr; the last complete real chain <em>sequence</em>,
     * for replay. An interaction with a {@code WaitForDataFrom.Client} operation
     * (plant's {@code PlaceBlockInteraction}, water's charge) spans several
     * packets — the {@code initial=true} chain plus {@code initial=false}
     * continuations — so the whole sequence is captured, not just the opener.
     */
    private final Map<ActionKey, List<TimedChain>> capturedSeq = new ConcurrentHashMap<>();
    /** client chainId &rarr; the sequence being assembled (until it terminates). */
    private final Map<Integer, List<TimedChain>> pendingSeq = new ConcurrentHashMap<>();
    /** client chainId &rarr; the action key its {@code initial=true} chain belongs to. */
    private final Map<Integer, ActionKey> pendingKey = new ConcurrentHashMap<>();
    /** Root ids already dumped to the log (VM-development diagnostics). */
    private final Set<Integer> dumpedRoots = ConcurrentHashMap.newKeySet();

    /**
     * The forge queue. core owns the chain-start cadence: forges run strictly
     * one at a time, each to completion, so two plugins (or one plugin's loop)
     * can never interleave packets and clobber each other's chains. A plugin
     * just calls {@code block.water()} in a loop — the spacing is core's job.
     */
    private final ArrayDeque<QueuedItem> forgeQueue = new ArrayDeque<>();
    /** {@code true} while a forge is in flight — guarded by {@code this}. */
    private boolean forging;

    public InteractionControlImpl(InteractionRegistry registry, InventoryTracker inventory,
                                  ChunkTracker chunks, WorldStateImpl worldState,
                                  ItemRegistry items, Scheduler scheduler) {
        this.registry = registry;
        this.inventory = inventory;
        this.chunks = chunks;
        this.worldState = worldState;
        this.items = items;
        this.scheduler = scheduler;
    }

    /** Creates the observer + chain-id NAT handler — register at NORMAL, BOTH directions. */
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

    /** The chain-id NAT — used by {@link InteractionObserver} to translate traffic. */
    ChainIdNat nat() {
        return nat;
    }

    /**
     * Learns from the player's own chains: the root id for each (type, held
     * item) and the complete real chain sequence, for replay. A multi-packet
     * interaction is buffered by client chainId until its terminal chain
     * ({@code state != NotFinished}) arrives.
     */
    void onClientChain(SyncInteractionChain chain) {
        // The hotbar slot reaches the server only on interaction packets — the
        // client never sends a standalone SetActiveSlot for it — so the
        // player's own chains are the reliable mirror of the active slots.
        inventory.observeActiveSlots(chain.activeHotbarSlot, chain.activeUtilitySlot,
                chain.activeToolsSlot);

        // Diagnostic: log every chain's walk, including initial=false continuations.
        if (chain.interactionData != null) {
            StringBuilder ops = new StringBuilder();
            for (InteractionSyncData d : chain.interactionData) {
                if (d != null) {
                    ops.append(d.operationCounter).append('/').append(d.rootInteraction)
                            .append('=').append(d.state).append(' ');
                }
            }
            log.info("meridian-core: observed {} chain {} initial={} desync={} state={} ops=[{}]",
                    chain.interactionType, chain.chainId, chain.initial, chain.desync,
                    chain.state, ops.toString().trim());
        }

        if (chain.initial && chain.interactionData != null && chain.interactionData.length > 0) {
            ActionKey key = ActionKey.of(chain.interactionType, chain.itemInHandId);
            Integer initialRoot = null;
            for (InteractionSyncData d : chain.interactionData) {
                if (d != null) {
                    if (initialRoot == null) {
                        initialRoot = d.rootInteraction;
                        observedRoot.put(key, initialRoot);
                    }
                    // Dump every distinct root — a ReplaceInteraction chains
                    // into a second root (e.g. Seed_Condition -> 3385).
                    dumpRootTree(d.rootInteraction);
                }
            }
            pendingKey.put(chain.chainId, key);
            pendingSeq.put(chain.chainId, new ArrayList<>());
            validateVm(chain);
        }

        // Buffer every packet of the chain with its arrival time; finalise the
        // sequence on the terminal chain so replay re-sends the whole exchange
        // at the original cadence (a water charge plays out over many ticks).
        List<TimedChain> seq = pendingSeq.get(chain.chainId);
        ActionKey key = pendingKey.get(chain.chainId);
        if (seq != null && key != null) {
            seq.add(new TimedChain(chain, System.nanoTime()));
            if (chain.state != InteractionState.NotFinished) {
                capturedSeq.put(key, seq);
                pendingSeq.remove(chain.chainId);
                pendingKey.remove(chain.chainId);
                log.info("meridian-core: captured {} sequence ({} packet(s), item {})",
                        key.type(), seq.size(), key.item());
                for (TimedChain tc : seq) {
                    dumpChain("CAPTURED", tc.chain());
                }
                validateTickedVm(key, seq);
            }
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
        Integer rootId = observedRoot.get(ActionKey.of(captured.interactionType, captured.itemInHandId));
        if (rootId == null) {
            return;
        }
        CompiledInteraction compiled = compileRoot(rootId);
        if (compiled == null) {
            return;
        }
        InteractionContext ctx = contextFor(captured.interactionType,
                captured.data.blockPosition, DataKind.BLOCK, captured.itemInHandId);
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
            real.append(d.operationCounter).append('=').append(d.state).append(' ');
            InteractionSyncData s = i < vm.size() ? vm.get(i) : null;
            sim.append(s == null ? "_" : s.operationCounter + "=" + s.state).append(' ');
            if (s == null || s.operationCounter != d.operationCounter || s.state != d.state) {
                match = false;
            }
        }
        log.info("meridian-core: VM check {} root {} — real=[{}] vm=[{}] {}",
                captured.interactionType, rootId, real.toString().trim(),
                sim.toString().trim(), match ? "MATCH" : "MISMATCH");
    }

    /**
     * Runs the tick-accurate simulator against a captured sequence and logs
     * whether it reproduces the full multi-tick op stream — the oracle for the
     * Phase-1 tick-simulator. Diagnostic only; the forge path is unaffected.
     */
    private void validateTickedVm(ActionKey key, List<TimedChain> seq) {
        Integer rootId = observedRoot.get(key);
        if (rootId == null) {
            return;
        }
        CompiledInteraction compiled = compileRoot(rootId);
        SyncInteractionChain first = seq.get(0).chain();
        if (compiled == null || first.data == null || first.data.blockPosition == null) {
            return;
        }
        InteractionContext ctx = contextFor(key.type(), first.data.blockPosition,
                DataKind.BLOCK, key.item());
        List<InteractionSimulator.Packet> ticked = InteractionSimulator.simulateTicked(
                compiled, ctx, this::compileRoot).packets();

        // Reconstruct the server-authoritative walk from the capture: each op
        // lives at flat index operationBaseIndex + i; a desync packet rewinds
        // the list to its operationBaseIndex, discarding the client's
        // mispredicted ops. The final state at each index is what the server saw.
        List<InteractionSyncData> realWalk = new ArrayList<>();
        for (TimedChain tc : seq) {
            SyncInteractionChain c = tc.chain();
            if (c.interactionData == null) {
                continue;
            }
            if (c.desync) {
                while (realWalk.size() > c.operationBaseIndex) {
                    realWalk.remove(realWalk.size() - 1);
                }
            }
            placeOps(realWalk, c.operationBaseIndex, c.interactionData);
        }
        List<InteractionSyncData> simWalk = new ArrayList<>();
        for (InteractionSimulator.Packet p : ticked) {
            placeOps(simWalk, p.baseIndex(), p.ops().toArray(new InteractionSyncData[0]));
        }

        String realStr = walkString(realWalk);
        String simStr = walkString(simWalk);
        boolean match = realStr.equals(simStr);
        log.info("meridian-core: VM-tick check {} root {} — {}", key.type(), rootId,
                match ? "MATCH" : "MISMATCH");
        log.info("meridian-core: VM-tick real=[{}]", realStr);
        log.info("meridian-core: VM-tick  sim=[{}]", simStr);
    }

    /** Places {@code ops} into {@code walk} at flat indices {@code base + i}. */
    private static void placeOps(List<InteractionSyncData> walk, int base,
                                 InteractionSyncData[] ops) {
        for (int i = 0; i < ops.length; i++) {
            if (ops[i] == null) {
                continue;
            }
            int flat = base + i;
            while (walk.size() <= flat) {
                walk.add(null);
            }
            walk.set(flat, ops[i]);
        }
    }

    /** Renders a flat walk as {@code counter=state} entries. */
    private static String walkString(List<InteractionSyncData> walk) {
        StringBuilder sb = new StringBuilder();
        for (InteractionSyncData d : walk) {
            if (d != null) {
                sb.append(d.operationCounter).append('=').append(d.state).append(' ');
            }
        }
        return sb.toString().trim();
    }

    /**
     * Builds a simulator context: the target block type from the trackers and
     * the held item's {@code interactionVars} from the {@link ItemRegistry}.
     */
    private InteractionContext contextFor(InteractionType type, BlockPosition target,
                                          DataKind dataKind, String itemId) {
        BlockType blockType = null;
        if (target != null) {
            int id = chunks.blockIdAt(target.x, target.y, target.z);
            if (id > 0) {
                blockType = worldState.blockTypeById(id);
            }
        }
        MovementStates movement = movementStates;
        Map<String, Integer> vars = items.varsFor(itemId);
        return dataKind == DataKind.PLACEMENT
                ? InteractionContext.ofPlacement(type, target, blockType, BlockFace.Up,
                        movement, vars)
                : InteractionContext.ofBlock(type, target, blockType, BlockFace.Up,
                        movement, vars);
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
    public CompletableFuture<Void> useOnBlock(BlockPos pos) {
        // Harvest interactions are requiresClient — the server waits for per-op
        // client data, so a bare chain is not enough. Build it via the VM.
        return enqueueForge(() -> forge(pos, InteractionType.Use, DataKind.BLOCK));
    }

    @Override
    public CompletableFuture<Void> hitBlock(BlockPos pos) {
        // A hit is a Primary (left-click) interaction — the held item's Primary
        // root carries a BreakBlockInteraction the server resolves into block
        // damage. One forge = one hit; the server decides the HP removed, so a
        // block may need several hits to break.
        return enqueueForge(() -> forge(pos, InteractionType.Primary, DataKind.BLOCK));
    }

    @Override
    public CompletableFuture<Void> waterBlock(BlockPos pos) {
        return enqueueForge(() -> forge(pos, InteractionType.Secondary, DataKind.BLOCK));
    }

    @Override
    public CompletableFuture<Void> plantOnBlock(BlockPos pos) {
        return enqueueForge(() -> forge(pos, InteractionType.Secondary, DataKind.PLACEMENT));
    }

    @Override
    public CompletableFuture<Void> switchHotbarSlot(int slot) {
        return enqueueForge(() -> doSwitchHotbarSlot(slot));
    }

    // ------------------------------------------------------------------
    // Forge queue — core owns the chain-start cadence
    // ------------------------------------------------------------------

    /**
     * Adds a forge to the queue and returns the future that completes when it
     * has played out. If nothing is in flight it starts immediately; otherwise
     * it runs once the chains ahead of it have played out. The build happens
     * when the forge is <em>dequeued</em>, not here — so a queued slot-switch's
     * inventory mirror is in place before the forges behind it read it.
     */
    private synchronized CompletableFuture<Void> enqueueForge(QueuedForge forge) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        forgeQueue.addLast(new QueuedItem(forge, done));
        if (!forging) {
            runNextForge();
        }
        return done;
    }

    /**
     * Pulls the next forge off the queue, runs it, and schedules — once this
     * chain's tick-duration has elapsed — both the completion of its future and
     * the run of the one after it. The queue only serializes (overlapping
     * chains of one player are invalid); it adds no padding between forges, so
     * a burst of instant harvests runs back-to-back at the tick rate. Any
     * human-like spacing is the calling plugin's choice. Called only with the
     * {@code this} monitor held.
     */
    private synchronized void runNextForge() {
        QueuedItem next = forgeQueue.pollFirst();
        if (next == null) {
            forging = false;
            return;
        }
        forging = true;
        int ticks;
        try {
            ticks = next.forge().run();
        } catch (RuntimeException ex) {
            log.warn("meridian-core: queued forge failed", ex);
            ticks = 0;
        }
        // Exactly the chain's tick-duration — one tick minimum, since even an
        // instant chain occupies the tick its packet lands on.
        long delayMs = Math.max(ticks, 1) * 1000L / 60L;
        scheduler.schedule(() -> {
            // The chain has played out: complete the caller's future (which may
            // enqueue follow-up forges via .thenRun), then start the next one.
            next.done().complete(null);
            runNextForge();
        }, Duration.ofMillis(delayMs));
    }

    /** Body of {@link #switchHotbarSlot}; returns the chain's tick-duration. */
    private int doSwitchHotbarSlot(int slot) {
        ProxySession s = session;
        if (s == null) {
            log.warn("meridian-core: switchHotbarSlot requested but no session yet");
            return 0;
        }
        if (slot == inventory.activeHotbarSlot()) {
            return 0; // already on it
        }
        // Every item maps SwapFrom -> the ChangeActiveSlot root; resolve via the
        // held item (any item works).
        OptionalInt root = resolveRoot(InteractionType.SwapFrom, inventory.itemInHandId());
        if (root.isEmpty()) {
            log.warn("meridian-core: no SwapFrom root known — cannot switch hotbar slot");
            return 0;
        }

        SyncInteractionChain chain = new SyncInteractionChain();
        chain.activeHotbarSlot = inventory.activeHotbarSlot(); // server is still on this slot
        chain.activeUtilitySlot = inventory.activeUtilitySlot();
        chain.activeToolsSlot = inventory.activeToolsSlot();
        chain.itemInHandId = inventory.itemInHandId();
        chain.utilityItemId = inventory.utilityItemId();
        chain.toolsItemId = inventory.toolsItemId();
        chain.initial = true;
        chain.desync = false;
        chain.overrideRootInteraction = Integer.MIN_VALUE;
        chain.interactionType = InteractionType.SwapFrom;
        chain.equipSlot = inventory.activeHotbarSlot();
        chain.chainId = nat.allocateForged();
        chain.forkedId = null;
        chain.state = InteractionState.Finished;
        chain.newForks = null;
        chain.operationBaseIndex = 0;

        InteractionChainData data = new InteractionChainData();
        data.entityId = -1;
        data.proxyId = new UUID(0L, 0L);
        // ChangeActiveSlotInteraction reads the target slot from data.targetSlot.
        data.targetSlot = slot;
        chain.data = data;

        // Root 5 (*Change_Active_Slot) is a single ChangeActiveSlotInteraction op.
        InteractionSyncData op = new InteractionSyncData();
        op.operationCounter = 0;
        op.rootInteraction = root.getAsInt();
        op.state = InteractionState.Finished;
        chain.interactionData = new InteractionSyncData[] {op};

        SyncInteractionChains packet = new SyncInteractionChains();
        packet.updates = new SyncInteractionChain[] {chain};
        s.sendToServer(packet);
        log.info("meridian-core: forged SwapFrom chain {} — hotbar slot {} -> {}",
                chain.chainId, inventory.activeHotbarSlot(), slot);

        // The server's ChangeActiveSlotInteraction always sets the slot — mirror
        // it so a follow-up forge sends the matching activeHotbarSlot / item.
        inventory.observeActiveSlots(slot, inventory.activeUtilitySlot(),
                inventory.activeToolsSlot());
        return 1; // a single-packet, instantly-Finished chain
    }

    // ------------------------------------------------------------------
    // Forge
    // ------------------------------------------------------------------

    /** Builds and sends one forged chain; returns its tick-duration (0 if nothing sent). */
    private int forge(BlockPos pos, InteractionType type, DataKind dataKind) {
        ProxySession s = session;
        if (s == null) {
            log.warn("meridian-core: interaction {} requested but no session yet", type);
            return 0;
        }

        // Chunk-decode verification: block ids around the target.
        log.info("meridian-core: blocks at ({},{},{}) — target={}, below={}, above={}",
                pos.x(), pos.y(), pos.z(),
                chunks.blockIdAt(pos.x(), pos.y(), pos.z()),
                chunks.blockIdAt(pos.x(), pos.y() - 1, pos.z()),
                chunks.blockIdAt(pos.x(), pos.y() + 1, pos.z()));

        String itemId = inventory.itemInHandId();

        // VM is the primary path — it honestly simulates the server walk. The
        // captured-sequence replay is the fallback when a root cannot be resolved.
        SyncInteractionChains[] forged = buildFromVm(pos, type, dataKind, itemId);
        if (forged != null) {
            return sendPacedVm(forged, type, pos);
        }
        List<TimedChain> sequence = capturedSeq.get(ActionKey.of(type, itemId));
        if (sequence != null && !sequence.isEmpty()) {
            return sendPacedReplay(sequence, pos, type);
        }
        return 0;
    }

    /**
     * Sends a VM-built chain paced at the 60&nbsp;Hz tick rate — one packet per
     * tick, so the server's {@code runTime} / charge clock keeps step with the
     * {@code progress} the packets carry.
     */
    private int sendPacedVm(SyncInteractionChains[] forged, InteractionType type, BlockPos pos) {
        int chainId = forged[0].updates.length > 0 ? forged[0].updates[0].chainId : -1;
        log.info("meridian-core: forged {} chain {} ({} tick(s), ticked) at ({},{},{}) — vm",
                type, chainId, forged.length, pos.x(), pos.y(), pos.z());
        for (int i = 0; i < forged.length; i++) {
            SyncInteractionChains scs = forged[i];
            for (SyncInteractionChain c : scs.updates) {
                dumpChain("FORGED-vm", c);
            }
            long delayMs = i * 1000L / 60L;
            if (delayMs == 0L) {
                sendChains(scs);
            } else {
                scheduler.schedule(() -> sendChains(scs), Duration.ofMillis(delayMs));
            }
        }
        return forged.length; // one SyncInteractionChains per 60 Hz tick
    }

    /**
     * Replays a captured chain sequence at its original cadence. A water charge
     * plays out over many server ticks, and the server validates the charge
     * against its own clock — so the packets must be delivered spread over time
     * (one {@code SyncInteractionChains} each), not in one burst.
     */
    private int sendPacedReplay(List<TimedChain> sequence, BlockPos pos, InteractionType type) {
        int forgedId = nat.allocateForged();
        int templateY = templateTargetY(sequence.get(0).chain());
        long t0 = sequence.get(0).nanos();
        long spanMs = 0L;
        log.info("meridian-core: forged {} chain {} ({} packet(s), paced) at ({},{},{}) — replay",
                type, forgedId, sequence.size(), pos.x(), pos.y(), pos.z());
        for (TimedChain tc : sequence) {
            SyncInteractionChain c = retargetChain(tc.chain(), pos, forgedId, templateY);
            dumpChain("FORGED-replay", c);
            long delayMs = Math.max(0L, (tc.nanos() - t0) / 1_000_000L);
            spanMs = Math.max(spanMs, delayMs);
            if (delayMs == 0L) {
                sendChain(c);
            } else {
                scheduler.schedule(() -> sendChain(c), Duration.ofMillis(delayMs));
            }
        }
        return (int) (spanMs * 60L / 1000L); // wall-clock span expressed in 60 Hz ticks
    }

    /** Sends one forged chain to the server as its own {@code SyncInteractionChains}. */
    private void sendChain(SyncInteractionChain chain) {
        ProxySession s = session;
        if (s == null) {
            return;
        }
        SyncInteractionChains packet = new SyncInteractionChains();
        packet.updates = new SyncInteractionChain[] {chain};
        s.sendToServer(packet);
    }

    /** Sends a pre-batched {@code SyncInteractionChains} (main chain + forks). */
    private void sendChains(SyncInteractionChains packet) {
        ProxySession s = session;
        if (s != null) {
            s.sendToServer(packet);
        }
    }

    /**
     * Clones one captured chain, stamps it with the shared forged {@code chainId}
     * and current inventory state, and retargets every block position onto
     * {@code pos} (keeping each op's captured y offset from {@code templateY}).
     */
    private SyncInteractionChain retargetChain(SyncInteractionChain template, BlockPos pos,
                                               int forgedId, int templateY) {
        SyncInteractionChain c = template.clone();
        c.chainId = forgedId;
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
                    int dy = src.blockPosition.y - templateY;
                    ops[i].blockPosition = new BlockPosition(pos.x(), pos.y() + dy, pos.z());
                }
            }
            c.interactionData = ops;
        }
        return c;
    }

    /** Dumps every decision-relevant field of a chain — for VM/replay diffing. */
    private static void dumpChain(String label, SyncInteractionChain c) {
        StringBuilder sb = new StringBuilder();
        sb.append("meridian-core: ").append(label)
                .append(" chainId=").append(c.chainId)
                .append(" initial=").append(c.initial)
                .append(" desync=").append(c.desync)
                .append(" state=").append(c.state)
                .append(" type=").append(c.interactionType)
                .append(" overrideRoot=").append(c.overrideRootInteraction)
                .append(" opBaseIdx=").append(c.operationBaseIndex)
                .append(" equipSlot=").append(c.equipSlot)
                .append(" hotbar/util/tools=").append(c.activeHotbarSlot).append('/')
                .append(c.activeUtilitySlot).append('/').append(c.activeToolsSlot)
                .append(" items=[").append(c.itemInHandId).append(',')
                .append(c.utilityItemId).append(',').append(c.toolsItemId).append(']')
                .append(" forkedId=").append(c.forkedId)
                .append(" newForks=").append(c.newForks == null ? "null" : c.newForks.length);
        if (c.data != null) {
            sb.append("\n  data: entityId=").append(c.data.entityId)
                    .append(" proxyId=").append(c.data.proxyId)
                    .append(" block=").append(fmt(c.data.blockPosition))
                    .append(" targetSlot=").append(c.data.targetSlot)
                    .append(" hitDetail=").append(c.data.hitDetail);
        }
        if (c.interactionData != null) {
            for (int i = 0; i < c.interactionData.length; i++) {
                InteractionSyncData o = c.interactionData[i];
                if (o == null) {
                    sb.append("\n  op[").append(i).append("]=null");
                    continue;
                }
                sb.append("\n  op[").append(i).append("] counter=").append(o.operationCounter)
                        .append(" root=").append(o.rootInteraction)
                        .append(" enteredRoot=").append(o.enteredRootInteraction)
                        .append(" state=").append(o.state)
                        .append(" progress=").append(o.progress)
                        .append(" charge=").append(o.chargeValue)
                        .append(" block=").append(fmt(o.blockPosition))
                        .append(" face=").append(o.blockFace)
                        .append(" rot=").append(o.blockRotation)
                        .append(" placedBlockId=").append(o.placedBlockId)
                        .append(" entityId=").append(o.entityId)
                        .append(" chainingIdx=").append(o.chainingIndex)
                        .append(" flagIdx=").append(o.flagIndex)
                        .append(" nextLabel=").append(o.nextLabel)
                        .append(" totalForks=").append(o.totalForks);
            }
        }
        log.info(sb.toString());
    }

    private static String fmt(BlockPosition b) {
        return b == null ? "null" : "(" + b.x + "," + b.y + "," + b.z + ")";
    }

    /** The y of the block the captured chain originally targeted. */
    private static int templateTargetY(SyncInteractionChain template) {
        return template.data != null && template.data.blockPosition != null
                ? template.data.blockPosition.y : 0;
    }

    /** A simulated parallel fork: its spec and its per-tick packets. */
    private record ForkBuild(InteractionSimulator.Fork fork,
                             List<InteractionSimulator.Packet> packets) {}

    /**
     * Builds the forged chain from the interaction VM. {@link InteractionSimulator}
     * produces the main chain's per-tick packets plus any parallel forks a
     * {@code ParallelInteraction} spawned; each fork root is simulated in turn.
     * The result is one {@code SyncInteractionChains} per 60&nbsp;Hz tick — the
     * main chain plus every fork active that tick — with the forks announced in
     * {@code newForks} on the packet that resolved the {@code ParallelInteraction}.
     * {@code null} if the root cannot be resolved.
     */
    private SyncInteractionChains[] buildFromVm(BlockPos pos, InteractionType type,
                                               DataKind dataKind, String itemId) {
        OptionalInt rootId = resolveRoot(type, itemId);
        if (rootId.isEmpty()) {
            log.warn("meridian-core: no root for {} with item {} — item catalog not "
                    + "received, or perform the action once manually", type, itemId);
            return null;
        }
        CompiledInteraction compiled = compileRoot(rootId.getAsInt());
        if (compiled == null) {
            log.warn("meridian-core: root {} not in registry", rootId.getAsInt());
            return null;
        }
        InteractionContext ctx = contextFor(type,
                new BlockPosition(pos.x(), pos.y(), pos.z()), dataKind, itemId);
        InteractionSimulator.TickedResult main = InteractionSimulator.simulateTicked(
                compiled, ctx, this::compileRoot);
        List<InteractionSimulator.Packet> mainPackets = main.packets();
        if (mainPackets.isEmpty()) {
            return null;
        }
        int forgedId = nat.allocateForged();

        // Simulate each ParallelInteraction fork root into its own packet list.
        List<ForkBuild> forks = new ArrayList<>();
        for (InteractionSimulator.Fork fork : main.forks()) {
            CompiledInteraction forkRoot = compileRoot(fork.rootId());
            if (forkRoot == null) {
                log.warn("meridian-core: fork root {} not in registry — skipped", fork.rootId());
                continue;
            }
            InteractionSimulator.TickedResult fr = InteractionSimulator.simulateTicked(
                    forkRoot, ctx, this::compileRoot);
            if (!fr.forks().isEmpty()) {
                log.warn("meridian-core: nested forks in root {} not emitted", fork.rootId());
            }
            forks.add(new ForkBuild(fork, fr.packets()));
        }

        // The forks are announced on the main packet that carries the
        // ParallelInteraction op, and run starting the tick after it.
        int announceIdx = 0;
        if (!forks.isEmpty()) {
            int entryIndex = forks.get(0).fork().entryIndex();
            for (int i = 0; i < mainPackets.size(); i++) {
                InteractionSimulator.Packet p = mainPackets.get(i);
                if (entryIndex >= p.baseIndex()
                        && entryIndex < p.baseIndex() + p.ops().size()) {
                    announceIdx = i;
                    break;
                }
            }
        }
        int forkStartTick = announceIdx + 1;

        int total = mainPackets.size();
        for (ForkBuild fb : forks) {
            total = Math.max(total, forkStartTick + fb.packets().size());
        }

        SyncInteractionChains[] perTick = new SyncInteractionChains[total];
        for (int t = 0; t < total; t++) {
            List<SyncInteractionChain> chains = new ArrayList<>();
            if (t < mainPackets.size()) {
                InteractionSimulator.Packet p = mainPackets.get(t);
                SyncInteractionChain mc = vmChain(pos, type, forgedId, null, t == 0,
                        t == mainPackets.size() - 1, p.baseIndex(),
                        p.ops().toArray(new InteractionSyncData[0]));
                if (t == announceIdx && !forks.isEmpty()) {
                    SyncInteractionChain[] announce =
                            new SyncInteractionChain[forks.size()];
                    for (int k = 0; k < forks.size(); k++) {
                        InteractionSimulator.Fork f = forks.get(k).fork();
                        announce[k] = vmChain(pos, type, forgedId,
                                new ForkedChainId(f.entryIndex(), f.subIndex(), null),
                                true, false, 0, null);
                    }
                    mc.newForks = announce;
                }
                chains.add(mc);
            }
            for (ForkBuild fb : forks) {
                int fj = t - forkStartTick;
                if (fj >= 0 && fj < fb.packets().size()) {
                    InteractionSimulator.Packet fp = fb.packets().get(fj);
                    InteractionSimulator.Fork f = fb.fork();
                    chains.add(vmChain(pos, type, forgedId,
                            new ForkedChainId(f.entryIndex(), f.subIndex(), null),
                            false, fj == fb.packets().size() - 1, fp.baseIndex(),
                            fp.ops().toArray(new InteractionSyncData[0])));
                }
            }
            SyncInteractionChains scs = new SyncInteractionChains();
            scs.updates = chains.toArray(new SyncInteractionChain[0]);
            perTick[t] = scs;
        }
        log.info("meridian-core: VM ticked root {} ({} tick(s), {} fork(s)) for {} item {}",
                rootId.getAsInt(), total, forks.size(), type, itemId);
        return perTick;
    }

    /**
     * Builds one forged {@code SyncInteractionChain}. {@code forkedId} is set
     * for a fork (null for the main chain); {@code ops} is null for a fork
     * announcement (the server reads only {@code state} for a null-data packet).
     */
    private SyncInteractionChain vmChain(BlockPos pos, InteractionType type, int forgedId,
                                         ForkedChainId forkedId, boolean first, boolean last,
                                         int baseIndex, InteractionSyncData[] ops) {
        SyncInteractionChain chain = new SyncInteractionChain();
        chain.activeHotbarSlot = inventory.activeHotbarSlot();
        chain.activeUtilitySlot = inventory.activeUtilitySlot();
        chain.activeToolsSlot = inventory.activeToolsSlot();
        chain.itemInHandId = inventory.itemInHandId();
        chain.utilityItemId = inventory.utilityItemId();
        chain.toolsItemId = inventory.toolsItemId();
        chain.initial = first;
        chain.desync = false;
        chain.overrideRootInteraction = Integer.MIN_VALUE;
        chain.interactionType = type;
        chain.equipSlot = inventory.activeHotbarSlot();
        chain.chainId = forgedId;
        chain.forkedId = forkedId;
        chain.state = last ? InteractionState.Finished : InteractionState.NotFinished;
        chain.newForks = null;
        // The server reads each op's chain index as operationBaseIndex + i, so a
        // continuation packet must resume from where the previous one ended.
        chain.operationBaseIndex = baseIndex;

        InteractionChainData data = new InteractionChainData();
        data.entityId = -1;
        data.proxyId = new UUID(0L, 0L);
        data.blockPosition = new BlockPosition(pos.x(), pos.y(), pos.z());
        data.targetSlot = Integer.MIN_VALUE;
        chain.data = data;

        chain.interactionData = ops;
        return chain;
    }

    /**
     * The VM entry-point root: the held item's asset binding ({@link ItemRegistry}),
     * or the chain observed for this (type, item) when the catalog is not in yet.
     */
    private OptionalInt resolveRoot(InteractionType type, String itemId) {
        OptionalInt fromItem = items.rootFor(itemId, type);
        if (fromItem.isPresent()) {
            return fromItem;
        }
        Integer observed = observedRoot.get(ActionKey.of(type, itemId));
        return observed == null ? OptionalInt.empty() : OptionalInt.of(observed);
    }
}
