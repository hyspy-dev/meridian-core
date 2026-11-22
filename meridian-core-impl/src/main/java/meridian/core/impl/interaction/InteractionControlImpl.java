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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import meridian.api.module.Scheduler;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.inventory.SetActiveSlot;
import meridian.core.api.BlockPos;
import meridian.core.api.Face;
import meridian.core.api.InteractionControl;
import meridian.core.api.InteractionOutcome;
import meridian.core.impl.ChunkTracker;
import meridian.core.impl.InteractionRegistry;
import meridian.core.impl.InventoryTracker;
import meridian.core.impl.ItemRegistry;
import meridian.core.impl.WorldStateImpl;
import meridian.protocol.BlockType;
import meridian.protocol.BlockFace;
import meridian.protocol.BlockPlacementRotationMode;
import meridian.protocol.BlockPosition;
import meridian.protocol.BlockRotation;
import meridian.protocol.Direction;
import meridian.protocol.Rotation;
import meridian.protocol.ForkedChainId;
import meridian.protocol.InteractionChainData;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InteractionType;
import meridian.protocol.GameMode;
import meridian.protocol.MovementStates;
import meridian.protocol.PlaceBlockInteraction;
import meridian.protocol.RootInteraction;
import meridian.protocol.VariantRotation;
import meridian.protocol.SelectInteraction;
import meridian.protocol.packets.interaction.CancelInteractionChain;
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
    /** The hotbar's inventory-section id (server {@code InventoryComponent.HOTBAR_SECTION_ID}). */
    private static final int HOTBAR_SECTION = -1;

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
     * A forge that started: when the queue may move on, and how the chain went. A forge that sent
     * nothing is done at once.
     */
    private record Started(CompletableFuture<Void> localDone, CompletableFuture<InteractionOutcome> outcome,
                           ForgeRun run) {
        static final Started NOTHING = settled(InteractionOutcome.notSent());

        static Started of(ForgeRun run) {
            return new Started(run.localDone(), run.outcome(), run);
        }

        static Started settled(InteractionOutcome outcome) {
            return new Started(CompletableFuture.completedFuture(null), CompletableFuture.completedFuture(outcome), null);
        }
    }

    /**
     * How a forge is pressed: as one more in the queue, or now, cutting short the chain in flight
     * where that chain's rules let a chain of its type interrupt it ({@link ChainRules}).
     */
    private record Press(InteractionType type, boolean now, boolean waitForServer) {
        static final Press QUEUED = new Press(null, false, false);
        static final Press AFTER_SERVER = new Press(null, false, true);
    }

    /** A unit of forging work on the serialized queue: builds the chain and starts playing it. */
    @FunctionalInterface
    private interface QueuedForge {
        Started start();
    }

    /** A forge waiting on the queue, paired with the future its caller holds. */
    private record QueuedItem(QueuedForge forge, CompletableFuture<InteractionOutcome> done, boolean waitForServer) {}

    private final InteractionRegistry registry;
    private final InventoryTracker inventory;
    private final ChunkTracker chunks;
    private final WorldStateImpl worldState;
    private final ItemRegistry items;
    private final Scheduler scheduler;
    private volatile ProjectilePlayback projectiles;

    /** Predicted projectile playback for this session. */
    public void projectiles(ProjectilePlayback playback) { projectiles = playback; }
    /** Network id &rarr; that entity as an aim (where it stands, its own interactions), or {@code null}. */
    private final EntityLookup entities;
    /** The player's eyes above its feet: its model's, as core's movement keeps it. */
    private final java.util.function.DoubleSupplier eyeHeight;

    private volatile ProxySession session;
    private volatile BlockPosition targetBlock;
    /** The player's last observed movement state — what {@code ConditionInteraction} tests. */
    private volatile MovementStates movementStates;
    /** Player's last observed look yaw (radians) — drives FacingPlayer placement. */
    private volatile float lookYaw;
    private volatile boolean haveLook;
    /** Chain-id NAT: keeps forged chain ids out of the client's counter space. */
    private final ChainIdNat nat = new ChainIdNat();
    /** The combo steps the server has been sent - what a forged swing continues from. */
    private final ChainingMemory chaining = new ChainingMemory();
    /** How the simulator enters roots and picks combo steps. */
    private final InteractionSimulator.RootResolver resolver = new InteractionSimulator.RootResolver() {
        @Override
        public CompiledInteraction resolve(int rootId) {
            return compileRoot(rootId);
        }

        @Override
        public double timeDilation() { return projectiles == null ? 1 : projectiles.timeDilation(); }

        @Override
        public void projectile(meridian.protocol.ProjectileInteraction node, InteractionSyncData data, double chargeSeconds) {
            ProjectilePlayback playback = projectiles;
            if (playback != null) playback.prepare(node, data, chargeSeconds);
        }

        @Override
        public int chainingIndex(meridian.protocol.ChainingInteraction node, int interactionId) {
            return chaining.next(node, interactionId);
        }
    };
    /** (type, held item) &rarr; root id — a fallback for the {@link ItemRegistry}. */
    private final Map<ActionKey, Integer> observedRoot = new ConcurrentHashMap<>();
    /**
     * {@code SelectInteraction} node id &rarr; the {@code HitBlock} fork root it
     * spawns (e.g. shovel #1551 &rarr; 651 {@code Shovel_Dig_Next_HitBlock}).
     * The wire {@code SelectInteraction} drops {@code hitBlock} (the server's
     * {@code configurePacket} serialises only {@code hitEntity}), so this link
     * exists nowhere in the packets — it is learned by watching the player's own
     * dig: each fork chain's {@code forkedId} points back at the SelectInteraction
     * op that spawned it. Needed to forge the dig's area-break.
     */
    private final Map<Integer, Integer> selectHitBlock = new ConcurrentHashMap<>();
    /** client chainId &rarr; that chain's main (non-fork) root, to attribute its forks. */
    private final Map<Integer, Integer> chainMainRoot = new ConcurrentHashMap<>();
    /** Chains being played, by server-side chain id - what the server's echoes and cancels go to. */
    private final Map<Integer, ForgeRun> runs = new ConcurrentHashMap<>();
    /** How long a settled run still hears the server: a late fork, a late replay. */
    private static final Duration RUN_LINGER = Duration.ofSeconds(30);
    /** The body forged pushes move ({@code ApplyForceInteraction}); set by core for a headless session. */
    private volatile ForceBody forceBody;

    /** The body forged chains push: core's, for the session it plays the client of. */
    public void forceBody(ForceBody body) {
        this.forceBody = body;
    }

    /** What a run needs from this control. */
    private final ForgeRun.Host host = new ForgeRun.Host() {
        @Override
        public void send(SyncInteractionChains packet) {
            sendChains(packet);
        }

        @Override
        public SyncInteractionChain wrap(int chainId, SyncInteractionChain fork, InteractionType type) {
            return forkParent(chainId, fork, type);
        }

        @Override
        public CompiledInteraction compile(int rootId) {
            return compileRoot(rootId);
        }

        @Override
        public InteractionSimulator.RootResolver resolver() {
            return resolver;
        }

        @Override
        public ForceBody forceBody() {
            return forceBody;
        }

        @Override
        public Scheduler scheduler() {
            return scheduler;
        }

        @Override
        public void finished(ForgeRun run) {
            try {
                scheduler.schedule(() -> forget(run), RUN_LINGER);
            } catch (RejectedExecutionException terminated) {
                forget(run);
            }
        }
    };
    /**
     * (forged chain, fork path) &rarr; the state we last told the server it is in. A fork's reply
     * rides in its parent's packet, which states the parent's own state again - it must say what
     * the parent is, not that it is still running.
     */
    private final Map<String, InteractionState> sentStates = new ConcurrentHashMap<>();
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
    /** (item|face) combos whose computed placement rotation has been logged once. */
    private final Set<String> loggedPlacementRot = ConcurrentHashMap.newKeySet();

    /**
     * The forge queue. core owns the chain-start cadence: forges run strictly
     * one at a time, each to completion, so two plugins (or one plugin's loop)
     * can never interleave packets and clobber each other's chains. A plugin
     * just calls {@code block.water()} in a loop — the spacing is core's job. One forge may cut the
     * one in flight short instead of waiting for it: one pressed now ({@link Press}), as the game lets
     * a guard cut a swing short - only where the rules of the chain in flight say so.
     */
    private final ArrayDeque<QueuedItem> forgeQueue = new ArrayDeque<>();
    /** {@code true} while a forge is in flight — guarded by {@code this}. */
    private boolean forging;
    /** The run of the forge in flight, when it plays one; guarded by {@code this}. */
    private ForgeRun inFlight;

    public InteractionControlImpl(InteractionRegistry registry, InventoryTracker inventory,
                                  ChunkTracker chunks, WorldStateImpl worldState,
                                  ItemRegistry items, Scheduler scheduler,
                                  EntityLookup entities, java.util.function.DoubleSupplier eyeHeight) {
        this.entities = entities;
        this.eyeHeight = eyeHeight;
        this.registry = registry;
        this.inventory = inventory;
        this.chunks = chunks;
        this.worldState = worldState;
        this.items = items;
        this.scheduler = scheduler;
    }

    /** Drops a settled run, and what was kept for its packets. */
    private void forget(ForgeRun run) {
        runs.remove(run.chainId(), run);
        String chain = String.valueOf(run.chainId());
        sentStates.keySet().removeIf(k -> k.equals(chain) || k.startsWith(chain + "/"));
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

    /**
     * A movement report that went to the server without passing the client-side chain (a game's
     * body core moves): its states and its look, as {@link InteractionObserver} reads them.
     */
    public void ownMovement(meridian.protocol.packets.player.ClientMovement m) {
        onMovementStates(m.movementStates);
        onPlayerLook(m.lookOrientation);
    }

    /** Whether the body's last report to the server said it crouches. */
    public boolean crouchingTold() {
        MovementStates m = movementStates;
        return m != null && m.crouching;
    }

    /** Tracks the player's movement state — {@code ConditionInteraction}'s input. */
    void onMovementStates(MovementStates states) {
        if (states != null) {
            this.movementStates = states;
        }
    }

    /** Tracks the player's look yaw — the input for FacingPlayer placement rotation. */
    void onPlayerLook(Direction look) {
        if (look != null) {
            this.lookYaw = look.yaw;
            this.haveLook = true;
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
        // The hotbar slot reaches the server only on interaction packets, so the
        // player's own chains are the reliable mirror of the active slot. For a
        // SwapFrom/SwapTo (a hotbar switch) the chain's activeHotbarSlot is the
        // slot BEFORE the switch — the new active slot is data.targetSlot. Using
        // activeHotbarSlot there left us reading the held item from the previous
        // slot (the "wrong block" bug). Item contents come from the inventory map.
        int hotbarSlot = chain.activeHotbarSlot;
        if ((chain.interactionType == InteractionType.SwapFrom
                || chain.interactionType == InteractionType.SwapTo)
                && chain.data != null && chain.data.targetSlot >= 0) {
            hotbarSlot = chain.data.targetSlot;
        }
        inventory.observeActiveSlots(hotbarSlot, chain.activeUtilitySlot,
                chain.activeToolsSlot);
        chaining.noteSent(chain, this::compileRoot); // the player's own swings move the combo too

        // Diagnostic, at debug: every chain's walk, including initial=false continuations.
        if (chain.interactionData != null && log.isDebugEnabled()) {
            StringBuilder ops = new StringBuilder();
            for (InteractionSyncData d : chain.interactionData) {
                if (d != null) {
                    ops.append(d.operationCounter).append('/').append(d.rootInteraction)
                            .append('=').append(d.state).append(' ');
                }
            }
            log.debug("meridian-core: observed {} chain {} initial={} desync={} state={} ops=[{}]",
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
            // Remember this chain's main root so its forks can be attributed to
            // the SelectInteraction that spawned them (learnSelectHitBlock).
            if (chain.forkedId == null && initialRoot != null) {
                chainMainRoot.put(chain.chainId, initialRoot);
            }
            pendingKey.put(chain.chainId, key);
            pendingSeq.put(chain.chainId, new ArrayList<>());
            validateVm(chain);
        }

        // Learn the SelectInteraction -> HitBlock-fork-root link from the dig's
        // own forks (the only place this mapping is observable — it's absent
        // from the wire).
        learnSelectHitBlock(chain);

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
     * Learns {@code SelectInteraction node -> HitBlock fork root} from an observed
     * dig fork. The wire carries no {@code hitBlock}, so the only evidence is the
     * fork itself: its {@code forkedId} attributes it to a chain whose main root
     * contains the spawning {@code SelectInteraction}. The shovel dig has exactly
     * one {@code SelectInteraction}, so the mapping is unambiguous.
     */
    private void learnSelectHitBlock(SyncInteractionChain chain) {
        if (chain.forkedId == null || !chain.initial
                || chain.interactionData == null || chain.interactionData.length == 0) {
            return;
        }
        Integer parentRoot = chainMainRoot.get(chain.chainId);
        if (parentRoot == null) {
            return;
        }
        int forkRoot = chain.interactionData[0].rootInteraction;
        CompiledInteraction parent = compileRoot(parentRoot);
        if (parent == null) {
            return;
        }
        Integer selNode = null;
        int selCount = 0;
        for (FlatOp op : parent.ops()) {
            if (op instanceof FlatOp.Node n && n.interaction() instanceof SelectInteraction) {
                selNode = n.interactionId();
                selCount++;
            }
        }
        if (selCount != 1) {
            // Ambiguous (or none) — fall back to the op the forkedId points at.
            FlatOp at = parent.op(chain.forkedId.entryIndex);
            selNode = at instanceof FlatOp.Node n && n.interaction() instanceof SelectInteraction
                    ? n.interactionId() : null;
        }
        if (selNode != null && selectHitBlock.put(selNode, forkRoot) == null) {
            log.info("meridian-core: learned SelectInteraction #{} -> HitBlock fork root {} "
                    + "(parent root {})", selNode, forkRoot, parentRoot);
        }
    }

    /**
     * The {@code blockRotation} a placed block must carry to come out oriented
     * the way the client would place it. The client just sends a rotation and the
     * server stores it; the rotation behaviour is driven by the block's
     * {@link VariantRotation} (a fixed {@code BlockType} field that parses
     * reliably — {@code placementSettings} often arrives empty).
     *
     * <ul>
     *   <li><b>Pipe / DoublePipe / All</b> (logs, beams, pillars) — lies along the
     *       axis of the clicked face ({@link #axisRotation}). Verified against
     *       real {@code Wood_Beech_Trunk} captures.</li>
     *   <li><b>NESW / UpDownNESW / Wall</b> (stairs, furniture) — faces the player,
     *       yaw from look direction ({@link #facingPlayerRotation}).</li>
     *   <li><b>UpDown</b> — vertical, flipped on a ceiling face
     *       ({@link #upDownRotation}).</li>
     *   <li><b>None</b> — no rotation.</li>
     * </ul>
     *
     * @return the rotation to send, or {@code null} for "default (no rotation)"
     */
    private BlockRotation placementRotationFor(int placedBlockId, String itemId, BlockFace face) {
        VariantRotation variant = placedBlockId >= 0
                ? worldState.variantRotationForBlock(placedBlockId) : VariantRotation.None;
        BlockRotation rot = switch (variant) {
            case Pipe, DoublePipe, All -> axisRotation(face);
            case NESW, UpDownNESW, Wall -> facingPlayerRotation();
            case UpDown -> upDownRotation(face);
            default -> null;   // None — block isn't orientable
        };
        // One line per (item, face) so we can confirm the variant resolved and
        // what orientation a forged placement carries — without flooding the log.
        if (loggedPlacementRot.add(itemId + "|" + face)) {
            log.info("meridian-core: placement rotation — item {} block {} variant {} face {} -> {}",
                    itemId, placedBlockId, variant, face, rot == null ? "default(none)"
                            : rot.rotationYaw + "/" + rot.rotationPitch + "/" + rot.rotationRoll);
        }
        return rot;
    }

    /** Vertical orientation for an {@code UpDown} block — flipped on a ceiling. */
    private static BlockRotation upDownRotation(BlockFace face) {
        return blockRotation(face == BlockFace.Down
                ? meridian.core.api.Rotation.of(0, 2, 0) : meridian.core.api.Rotation.NONE);
    }

    /** The protocol's quarter turns, by count: None, Ninety, OneEighty, TwoSeventy. */
    private static final Rotation[] QUARTERS = Rotation.values();

    /** The server's orientation number as the three quarter turns a placement carries. */
    static BlockRotation blockRotation(int index) {
        return new BlockRotation(QUARTERS[meridian.core.api.Rotation.yaw(index)],
                QUARTERS[meridian.core.api.Rotation.pitch(index)],
                QUARTERS[meridian.core.api.Rotation.roll(index)]);
    }

    /** The block id a {@code PlaceBlockInteraction} in {@code compiled} places, or -1. */
    private static int placedBlockIdOf(CompiledInteraction compiled) {
        for (FlatOp op : compiled.ops()) {
            if (op instanceof FlatOp.Node n && n.interaction() instanceof PlaceBlockInteraction pb) {
                return pb.blockId;
            }
        }
        return -1;
    }

    /**
     * The {@code FacingPlayer} rotation: the block turns so its front faces the
     * player, i.e. opposite the player's look direction, quantised to a cardinal.
     *
     * <p>Derived from the engine convention: look dir is
     * {@code (-sin(yaw), …, -cos(yaw))} ({@code Vector3dUtil.setYawPitch}) so
     * yaw 0°=North, 90°=West, 180°=South, 270°=East; the block's default front is
     * {@code +Z} (South) and {@code Rotation.rotateY} turns it, which works out to
     * {@code yaw = closestOfDegrees(playerYawDegrees)} (matches a real capture:
     * a placement at look-yaw ≈90° → {@code Ninety}). {@code StairFacingPlayer}
     * shares the yaw; its up/down (ceiling) pitch variant is not reproduced.
     *
     * @return the rotation, or {@code null} if no look has been observed yet
     */
    private BlockRotation facingPlayerRotation() {
        if (!haveLook) {
            return null;
        }
        double deg = Math.toDegrees(lookYaw);
        int q = (int) Math.floorMod(Math.round(deg / 90.0), 4L);
        return blockRotation(meridian.core.api.Rotation.of(q, 0, 0));
    }

    /**
     * Axis orientation for a {@code Pipe}-family block (log / beam / pillar): it
     * lies along the <em>axis</em> of the clicked face — opposite faces share a
     * rotation (East==West, North==South). Matches the server {@code Pipe}
     * variant's rotation set and real {@code Wood_Beech_Trunk} captures:
     * East/West → {@code yaw=90,pitch=90} (X), North/South → {@code pitch=90} (Z),
     * Up/Down → none (Y).
     */
    private static BlockRotation axisRotation(BlockFace face) {
        return switch (face) {
            case Up, Down     -> blockRotation(meridian.core.api.Rotation.NONE);
            case North, South -> blockRotation(meridian.core.api.Rotation.of(0, 1, 0));
            case East, West   -> blockRotation(meridian.core.api.Rotation.of(1, 1, 0));
            default           -> null;
        };
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
                captured.data.blockPosition, DataKind.BLOCK, captured.itemInHandId, BlockFace.Up);
        List<InteractionSyncData> vm = InteractionSimulator.simulate(
                compiled, ctx, resolver);

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
                DataKind.BLOCK, key.item(), BlockFace.Up);
        List<InteractionSimulator.Packet> ticked = InteractionSimulator.simulateTicked(
                compiled, ctx, resolver, selectHitBlock).packets();

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
                                          DataKind dataKind, String itemId, BlockFace face) {
        BlockType blockType = null;
        int blockId = -1;
        if (target != null) {
            blockId = chunks.blockIdAt(target.x, target.y, target.z);
            if (blockId > 0) {
                blockType = worldState.blockTypeById(blockId);
            }
        }
        MovementStates movement = movementStates;
        GameMode mode = entities.selfGameMode();
        Map<String, Integer> vars = items.varsFor(itemId);
        // Placement rotation is filled in by buildFromVm (it knows the placed
        // block id) via withBlockRotation; null here means "default for now".
        InteractionContext ctx = dataKind == DataKind.PLACEMENT
                ? InteractionContext.ofPlacement(type, target, blockType, blockId, face,
                        null, movement, mode, vars)
                : InteractionContext.ofBlock(type, target, blockType, blockId, face,
                        movement, mode, vars);
        // The player's own effects as last seen - what effect conditions on the user test.
        return ctx.withEffects(new WalkEffects(entities.selfEffects(), null, entities.selfStats())
                .withCooldowns(cooldowns));
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

    /** A root's operation list as the VM sees it, or why there is none - for the command channel. */
    public String describeRoot(int rootId) {
        RootInteraction root = registry.root(rootId).orElse(null);
        CompiledInteraction compiled = compileRoot(rootId);
        if (root == null || compiled == null) {
            return "root " + rootId + " is not in the registry";
        }
        return "root '" + root.id + "' #" + rootId + "\n" + compiled.describe();
    }

    /** The root the server last used for {@code type} with {@code itemId} in hand, if one was seen. */
    public Integer observedRootFor(InteractionType type, String itemId) {
        return observedRoot.get(ActionKey.of(type, itemId));
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
    public CompletableFuture<InteractionOutcome> useOnBlock(BlockPos pos) {
        // Harvest interactions are requiresClient — the server waits for per-op
        // client data, so a bare chain is not enough. Build it via the VM.
        return enqueueForge(() -> forge(pos, InteractionType.Use, DataKind.BLOCK));
    }

    @Override
    public CompletableFuture<InteractionOutcome> hitBlock(BlockPos pos) {
        // A hit is a Primary (left-click) interaction — the held item's Primary
        // root carries a BreakBlockInteraction the server resolves into block
        // damage. One forge = one hit; the server decides the HP removed, so a
        // block may need several hits to break.
        return enqueueForge(() -> forge(pos, InteractionType.Primary, DataKind.BLOCK));
    }

    @Override
    public CompletableFuture<InteractionOutcome> digSwing(BlockPos aim) {
        if (aim == null) {
            return CompletableFuture.completedFuture(InteractionOutcome.notSent());
        }
        // One swing: forge the held tool's Primary main chain through its
        // SelectInteraction. We do NOT pre-compute break forks — the server runs
        // the SelectInteraction's area selector itself and spawns the forks,
        // which we answer reactively (onServerForkAnnounce). For a tool without a
        // SelectInteraction (a pickaxe) the main chain just breaks the aimed block.
        return enqueueForge(() -> forgeDigSwing(aim));
    }

    /**
     * How long after the last swing the block's own change is waited for: it comes beside the
     * server's word on the chain, and the two can arrive a tick or two apart.
     */
    private static final Duration BREAK_SETTLE = Duration.ofMillis(500);

    @Override
    public java.util.OptionalDouble digReach(String itemId) {
        String item = itemId == null || itemId.isEmpty() ? null : itemId;
        double sweep = sweepOf(item);
        if (Double.isNaN(sweep)) {
            return java.util.OptionalDouble.empty();
        }
        return java.util.OptionalDouble.of(sweep > 0 ? sweep : useDistance(item));
    }

    @Override
    public java.util.OptionalDouble useReach(String itemId) {
        return java.util.OptionalDouble.of(useDistance(itemId == null || itemId.isEmpty() ? null : itemId));
    }

    /**
     * The server's reach with the item in hand, before its buffer ({@code getPlayerInteractionDistanceSq}):
     * the item's use distance for the game mode, in Creative at least the client's creative reach.
     */
    private double useDistance(String item) {
        GameMode own = entities.selfGameMode();
        GameMode mode = own != null ? own : GameMode.Adventure;
        double use = items.useDistance(item, mode);
        return mode == GameMode.Creative ? Math.max(use, creativeReach) : use;
    }

    /**
     * The client's creative reach, as its preferences tell the server - clamped as the server
     * clamps it. Until they come, the server's own default.
     */
    void onPreferences(meridian.protocol.packets.player.SyncPlayerPreferences prefs) {
        creativeReach = Math.max(0, Math.min(MAX_CREATIVE_REACH, prefs.creativeInteractionDistance));
    }

    @Override
    public boolean digSweeps(String itemId) {
        return sweepOf(itemId == null || itemId.isEmpty() ? null : itemId) > 0;
    }

    /**
     * How far the item's dig sweeps: a BreakBlock in its swing breaks the block aimed at (a
     * pickaxe's) - {@code 0}; with none, what breaks blocks is a sweep the server makes (a shovel's,
     * whose HitBlock forks break them) - its reach. A sweep beside a BreakBlock hits entities only.
     * {@code NaN} when the swing is not known.
     */
    private double sweepOf(String item) {
        OptionalInt rootId = resolveRoot(InteractionType.Primary, item);
        CompiledInteraction root = rootId.isEmpty() ? null : compileRoot(rootId.getAsInt());
        if (root == null) {
            return Double.NaN;
        }
        boolean aimed = false;
        double sweep = Double.MAX_VALUE;
        for (FlatOp op : root.ops()) {
            if (op instanceof FlatOp.Node n) {
                if (n.interaction() instanceof meridian.protocol.BreakBlockInteraction) {
                    aimed = true;
                } else if (n.interaction() instanceof SelectInteraction sel) {
                    double r = selectorReach(sel.selector);
                    if (r > 0) {
                        sweep = Math.min(sweep, r);
                    }
                }
            }
        }
        return !aimed && sweep < Double.MAX_VALUE ? sweep : 0;
    }

    /** How far from where it starts a selector takes things in; 0 when it does not say. */
    private static double selectorReach(meridian.protocol.Selector selector) {
        return switch (selector) {
            case meridian.protocol.HorizontalSelector h -> h.endDistance;
            case meridian.protocol.StabSelector st -> st.endDistance;
            case meridian.protocol.AOECircleSelector c -> c.range;
            case meridian.protocol.AOECylinderSelector c -> c.range;
            case meridian.protocol.DonutSelector d -> d.maxRadius;
            case meridian.protocol.RaycastSelector r -> r.distance;
            case null, default -> 0;
        };
    }

    @Override
    public CompletableFuture<Boolean> breakBlock(BlockPos pos, int maxSwings) {
        if (pos == null || maxSwings < 1 || session == null) {
            return CompletableFuture.completedFuture(false);
        }
        int x = pos.x(), y = pos.y(), z = pos.z();
        int start = chunks.blockIdAt(x, y, z);
        if (!chunks.known(x, y, z) || start <= 0) {
            return CompletableFuture.completedFuture(false);  // nothing known there, or nothing to break
        }
        // Gone once core is sent the cell holding something else: air, or what the block breaks into.
        java.util.function.BooleanSupplier gone = () -> chunks.known(x, y, z) && chunks.blockIdAt(x, y, z) != start;
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        ChunkTracker.CellWatch watch = chunks.watchCell(x, y, z, () -> {
            if (!done.isDone() && gone.getAsBoolean()) {
                answerBreak(done, () -> true, Duration.ZERO);
            }
        });
        done.whenComplete((v, e) -> watch.close());
        meridian.core.impl.movement.Urgency u = urgency;
        swingUntilGone(pos, 1, maxSwings, gone, done, u == null ? Long.MAX_VALUE : u.stamp());
        return done;
    }

    /** Core's urgent holds: a loop of swings begun before one is held ends at its next swing. */
    private volatile meridian.core.impl.movement.Urgency urgency;

    public void urgency(meridian.core.impl.movement.Urgency urgency) {
        this.urgency = urgency;
    }

    /** Swing {@code n} of {@code max}, once the one before has been answered - as a held button swings. */
    private void swingUntilGone(BlockPos pos, int n, int max, java.util.function.BooleanSupplier gone,
                                CompletableFuture<Boolean> done, long born) {
        if (done.isDone()) {
            return;
        }
        meridian.core.impl.movement.Urgency u = urgency;
        if (u != null && u.holdsBack(born)) {
            // An urgent hold came: the swings stop, and the loop is over once it is given back - the
            // body will be elsewhere by then, so whoever swung decides again from there.
            u.turn(born).thenRun(() -> answerBreak(done, gone, Duration.ZERO));
            return;
        }
        digSwing(pos).whenComplete((outcome, error) -> {
            if (done.isDone()) {
                return;
            }
            if (gone.getAsBoolean()) {
                answerBreak(done, () -> true, Duration.ZERO);
                return;
            }
            boolean sent = outcome != null && outcome.status() != InteractionOutcome.Status.NOT_SENT;
            if (sent && n < max) {
                swingUntilGone(pos, n + 1, max, gone, done, born);
            } else if (!sent && n == 1) {
                answerBreak(done, () -> false, Duration.ZERO);   // nothing went out
            } else {
                // The last swing, or no more could be sent: its block change may still be coming.
                answerBreak(done, gone, BREAK_SETTLE);
            }
        });
    }

    /** Answers a break on the scheduler after {@code delay}, with what {@code answer} says then. */
    private void answerBreak(CompletableFuture<Boolean> done, java.util.function.BooleanSupplier answer,
                             Duration delay) {
        Runnable complete = () -> done.complete(answer.getAsBoolean());
        try {
            scheduler.schedule(complete, delay);
        } catch (RejectedExecutionException terminated) {
            complete.run();
        }
    }

    // ------------------------------------------------------------------
    // What an item does in a fight
    // ------------------------------------------------------------------

    /** The most roots one look into an item's chain goes through. */
    private static final int MOST_ROOTS = 64;
    /** The server's entity effects by index, for what a use heals ({@link #fightLookups}). */
    private volatile java.util.function.IntFunction<meridian.protocol.EntityEffect> effectAt = i -> null;
    /** A stat's catalog index by name, {@code -1} for none ({@link #fightLookups}). */
    private volatile java.util.function.ToIntFunction<String> statIndex = n -> -1;

    /** Repairs an item with a kit ({@link #repair}); set by core's module. */
    private volatile java.util.function.Function<String, CompletableFuture<Boolean>> repairer =
            item -> CompletableFuture.completedFuture(false);

    public void repairer(java.util.function.Function<String, CompletableFuture<Boolean>> repairs) {
        if (repairs != null) {
            repairer = repairs;
        }
    }

    @Override
    public CompletableFuture<Boolean> repair(String itemId) {
        return repairer.apply(itemId);
    }

    @Override
    public boolean placedCrouching(String itemId) {
        OptionalInt root = itemId == null ? OptionalInt.empty() : resolveRoot(InteractionType.Secondary, itemId);
        CompiledInteraction compiled = root.isEmpty() ? null : compileRoot(root.getAsInt());
        if (compiled == null) {
            return false;
        }
        for (FlatOp op : compiled.ops()) {
            if (op instanceof FlatOp.Node n) {
                // The use's first question: "is the body standing?" - yes, the item's own doing (a throw);
                // no, the block is put down.
                if (n.interaction() instanceof meridian.protocol.ConditionInteraction c && c.crouching != null
                        && !c.crouching) {
                    GameMode mode = entities.selfGameMode();
                    return c.requiredGameMode == null || mode == null || c.requiredGameMode == mode;
                }
                return false;
            }
        }
        return false;
    }

    /** Where the effects and the stat names come from: core's own catalogs of them. */
    public void fightLookups(java.util.function.IntFunction<meridian.protocol.EntityEffect> effects,
                             java.util.function.ToIntFunction<String> stats) {
        if (effects != null) {
            effectAt = effects;
        }
        if (stats != null) {
            statIndex = stats;
        }
    }

    /**
     * Every operation an item's chain of {@code type} can reach: its root's, and those of every root
     * it calls - a {@code Replace} through the item's own variables (else its default), a
     * {@code RunRoot}, a {@code Parallel}'s branches, and with {@code intoHits} what a swing runs on
     * what it hits. {@code null} when core knows no root for it.
     */
    private List<meridian.protocol.Interaction> reached(String item, InteractionType type, boolean intoHits) {
        OptionalInt root = resolveRoot(type, item);
        if (root.isEmpty()) {
            return null;
        }
        Map<String, Integer> vars = items.varsFor(item);
        List<meridian.protocol.Interaction> out = new ArrayList<>();
        ArrayDeque<Integer> todo = new ArrayDeque<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        todo.add(root.getAsInt());
        while (!todo.isEmpty() && seen.size() < MOST_ROOTS) {
            int id = todo.poll();
            if (id == Integer.MIN_VALUE || !seen.add(id)) {
                continue;
            }
            CompiledInteraction c = compileRoot(id);
            if (c == null) {
                continue;
            }
            for (FlatOp op : c.ops()) {
                if (!(op instanceof FlatOp.Node n) || n.interaction() == null) {
                    continue;
                }
                meridian.protocol.Interaction i = n.interaction();
                out.add(i);
                if (i instanceof meridian.protocol.ReplaceInteraction r) {
                    Integer to = r.variable == null ? null : vars.get(r.variable);
                    todo.add(to != null ? to : r.defaultValue);
                } else if (i instanceof meridian.protocol.RunRootInteraction rr) {
                    todo.add(rr.rootInteraction);
                } else if (i instanceof meridian.protocol.ParallelInteraction par && par.next != null) {
                    for (int next : par.next) {
                        todo.add(next);
                    }
                } else if (intoHits && i instanceof SelectInteraction sel) {
                    todo.add(sel.hitEntity);
                }
            }
        }
        return out;
    }

    /** One ordinary draw branch, resolved through this item's variables. */
    public record BowTier(float seconds, String config) { }

    /** Draw tiers from the server's charge graph, never from a numeric preset. */
    public List<BowTier> bowTiers(String item) {
        if (item == null || !item.startsWith("Weapon_Shortbow_")) return List.of();
        List<meridian.protocol.Interaction> ops = reached(item, InteractionType.Primary, false);
        if (ops == null) return List.of();
        List<BowTier> tiers = new ArrayList<>();
        for (var op : ops) {
            if (!(op instanceof meridian.protocol.ChargingInteraction charge) || charge.chargedNext == null) continue;
            List<BowTier> found = new ArrayList<>();
            for (var branch : charge.chargedNext.entrySet()) {
                if (branch.getKey() <= 0) continue;
                var configs = new java.util.HashSet<String>();
                collectProjectiles(branch.getValue(), false, items.varsFor(item), new java.util.HashSet<>(), configs);
                if (configs.size() == 1) found.add(new BowTier(branch.getKey(), configs.iterator().next()));
            }
            if (found.size() > tiers.size()) tiers = found;
        }
        return tiers.stream().sorted(java.util.Comparator.comparingDouble(BowTier::seconds)).toList();
    }

    private void collectProjectiles(int id, boolean root, Map<String, Integer> vars,
                                    Set<String> seen, Set<String> configs) {
        if (id == Integer.MIN_VALUE || seen.size() > 256 || !seen.add((root ? "r" : "n") + id)) return;
        CompiledInteraction compiled = root ? compileRoot(id) : InteractionFlattener.compile(-1, new int[] {id},
                n -> registry.interaction(n).orElse(null));
        if (compiled == null) return;
        for (FlatOp op : compiled.ops()) {
            if (!(op instanceof FlatOp.Node node)) continue;
            var i = node.interaction();
            if (i instanceof meridian.protocol.ProjectileInteraction p && p.configId != null) configs.add(p.configId);
            else if (i instanceof meridian.protocol.ReplaceInteraction r) {
                collectProjectiles(vars.getOrDefault(r.variable, r.defaultValue), true, vars, seen, configs);
            } else if (i instanceof meridian.protocol.RunRootInteraction r) {
                collectProjectiles(r.rootInteraction, true, vars, seen, configs);
            }
        }
    }

    /** The ammunition removed by the server's ordinary primary graph. */
    public String bowAmmo(String item) {
        List<meridian.protocol.Interaction> ops = reached(item, InteractionType.Primary, false);
        if (ops != null) for (var op : ops) {
            if (op instanceof meridian.protocol.ModifyInventoryInteraction m && m.itemToRemove != null
                    && m.itemToRemove.itemId != null) return m.itemToRemove.itemId;
        }
        return null;
    }

    /** Plays Primary with charge time measured after nocking, waiting for a reported release aim. */
    public CompletableFuture<InteractionOutcome> draw(float seconds, java.util.function.DoubleFunction<Boolean> ready,
                                                       java.util.function.Consumer<UUID> launched) {
        return enqueueForge(() -> {
            ForgeRun run = startRun(null, InteractionType.Primary, DataKind.NONE, inventory.itemInHandId(),
                    BlockFace.None, null, Map.of(), BlockFace.None, null, Integer.MIN_VALUE, Float.NaN,
                    w -> w.draw(seconds, ready, launched));
            return run == null ? Started.NOTHING : Started.of(run);
        });
    }

    /** Starts a new client contact chain using the projectile's own root and prediction UUID. */
    public CompletableFuture<InteractionOutcome> projectileContact(int root, InteractionType type,
                                                                   InteractionChainData data) {
        CompiledInteraction compiled = compileRoot(root);
        if (session == null || compiled == null) return CompletableFuture.completedFuture(InteractionOutcome.notSent());
        InteractionContext ctx = contextFor(type, data.blockPosition, DataKind.BLOCK,
                inventory.itemInHandId(), BlockFace.Up);
        if (data.entityId >= 0) {
            EntityTarget target = entities.target(data.entityId);
            if (target != null) ctx = ctx.withEntity(target);
        }
        ForgeRun.Header header = new ForgeRun.Header(inventory.activeHotbarSlot(), inventory.activeUtilitySlot(),
                inventory.activeToolsSlot(), inventory.itemInHandId(), inventory.utilityItemId(), inventory.toolsItemId());
        int id = nat.allocateForged();
        ForgeRun run = new ForgeRun(host, id, header, type, new ChainWalk(compiled, ctx, resolver, Map.of()).live(forceBody),
                data, ctx, Map.of());
        runs.put(id, run);
        run.start();
        return run.outcome();
    }

    @Override
    public java.util.OptionalDouble hitReach(String itemId) {
        String item = itemId == null || itemId.isEmpty() ? null : itemId;
        List<meridian.protocol.Interaction> ops = reached(item, InteractionType.Primary, false);
        if (ops == null) {
            return java.util.OptionalDouble.empty();
        }
        double best = 0;
        for (meridian.protocol.Interaction i : ops) {
            if (i instanceof SelectInteraction sel && (sel.hitEntity != Integer.MIN_VALUE || sel.hitEntityRules != null)) {
                best = Math.max(best, selectorReach(sel.selector));
            }
        }
        return best > 0 ? java.util.OptionalDouble.of(best) : java.util.OptionalDouble.empty();
    }

    @Override
    public boolean guards(String itemId) {
        String item = itemId == null || itemId.isEmpty() ? null : itemId;
        List<meridian.protocol.Interaction> ops = reached(item, InteractionType.Secondary, false);
        if (ops == null) {
            return false;
        }
        for (meridian.protocol.Interaction i : ops) {
            if (i instanceof meridian.protocol.WieldingInteraction w
                    && (w.angledWielding != null || w.hasModifiers || w.blockedEffects != null)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Heal> heal(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return Optional.empty();
        }
        int health = statIndex.applyAsInt("Health");
        List<meridian.protocol.Interaction> ops = reached(itemId, InteractionType.Secondary, false);
        if (health < 0 || ops == null) {
            return Optional.empty();
        }
        StatSheet own = entities.selfStats();
        float[] mine = own == null ? null : own.read(health);
        double max = mine == null ? 100 : Math.max(1, mine[2] - mine[1]);
        double now = 0;
        double slow = 0;
        double seconds = 0;
        for (meridian.protocol.Interaction i : ops) {
            if (i instanceof meridian.protocol.ApplyEffectInteraction apply
                    && apply.entityTarget == meridian.protocol.InteractionTarget.User) {
                meridian.protocol.EntityEffect e = effectAt.apply(apply.effectId);
                Float v = e == null || e.statModifiers == null ? null : e.statModifiers.get(health);
                if (v == null || v <= 0) {
                    continue;
                }
                double per = e.valueType == meridian.protocol.ValueType.Percent ? v / 100.0 * max : v;
                if (e.damageCalculatorCooldown > 0 && !e.infinite) {
                    // Applied each cooldown for as long as the effect lasts (ActiveEntityEffect).
                    slow += per * Math.floor(e.duration / e.damageCalculatorCooldown);
                    seconds = Math.max(seconds, e.duration);
                } else {
                    now += per;
                }
            } else if (i instanceof meridian.protocol.ChangeStatInteraction change
                    && change.entityTarget == meridian.protocol.InteractionTarget.User
                    && change.changeStatBehaviour == meridian.protocol.ChangeStatBehaviour.Add
                    && change.statModifiers != null) {
                Float v = change.statModifiers.get(health);
                if (v != null && v > 0) {
                    now += change.valueType == meridian.protocol.ValueType.Percent ? v / 100.0 * max : v;
                }
            }
        }
        return now + slow > 0 ? Optional.of(new Heal(now, slow, seconds)) : Optional.empty();
    }

    @Override
    public Heal healing() {
        int health = statIndex.applyAsInt("Health");
        if (health < 0) {
            return Heal.NONE;
        }
        return Healing.underWay(entities.selfEffectsLeft(), effectAt, health, maxHealth(health));
    }

    /** The player's cooldowns, as the chains core walks for it start them (a potion's, 15 s). */
    private final Cooldowns cooldowns = new Cooldowns(System::nanoTime);

    /** The longest a use's chain is played out for {@link #healGain}: a bite, a drink, at 60 a second. */
    private static final int MOST_USE_TICKS = 60 * 30;

    @Override
    public Optional<Heal> healGain(String itemId) {
        if (heal(itemId).isEmpty()) {
            return Optional.empty();
        }
        int health = statIndex.applyAsInt("Health");
        OptionalInt root = resolveRoot(InteractionType.Secondary, itemId);
        CompiledInteraction compiled = root.isEmpty() ? null : compileRoot(root.getAsInt());
        if (health < 0 || compiled == null) {
            return heal(itemId);
        }
        // The use played out as a prediction on the player's effects and stats as they are now:
        // its effect conditions see what is on, its own clears and applies are kept in order.
        InteractionContext ctx = contextFor(InteractionType.Secondary, null, DataKind.NONE, itemId, BlockFace.None);
        WalkEffects after = Healing.play(compiled, ctx, resolver, MOST_USE_TICKS);
        float[] was = ctx.effects().stats().read(health);
        float[] is = after.stats().read(health);
        double changed = was == null || is == null ? 0 : Math.max(0, is[0] - was[0]);   // ChangeStat on the way
        double missing = was == null ? maxHealth(health) : Math.max(0, was[2] - was[0]);
        return Optional.of(Healing.gain(after.changes(), entities.selfEffectsLeft(), effectAt, health,
                maxHealth(health), changed, missing));
    }

    /** The player's max health in points, counted as the stat's span; 100 before the server has said. */
    private double maxHealth(int health) {
        StatSheet own = entities.selfStats();
        float[] mine = own == null ? null : own.read(health);
        return mine == null ? 100 : Math.max(1, mine[2] - mine[1]);
    }

    /**
     * The item a chain of {@code type} runs with, by the server's rule
     * ({@code InteractionContext.forInteraction}): the main hand's, but the utility slot's (a
     * shield) when the hand is empty, when the utility item's priority for the type is higher, when
     * the hand's item has no such interaction, or - for {@code Secondary} at equal priority - when
     * the hand's item works beside a utility item ({@code Utility.Compatible}, a sword). The utility
     * slot is taken only once core knows which one is active.
     */
    String handItem(InteractionType type) {
        String primary = inventory.itemInHandId();
        if (type != InteractionType.Primary && type != InteractionType.Secondary) {
            return primary;
        }
        String secondary = inventory.utilitySlotKnown() ? inventory.utilityItemId() : null;
        if (secondary == null) {
            return primary;
        }
        if (primary == null) {
            return secondary;
        }
        meridian.protocol.ItemBase main = items.item(primary).orElse(null);
        meridian.protocol.ItemBase off = items.item(secondary).orElse(null);
        if (main == null || off == null) {
            return primary;
        }
        int pMain = priority(main, type, meridian.protocol.PrioritySlot.MainHand);
        int pOff = priority(off, type, meridian.protocol.PrioritySlot.OffHand);
        if (pMain == pOff) {
            return type == InteractionType.Secondary && main.utility != null && main.utility.compatible ? secondary : primary;
        }
        if (pMain < pOff) {
            return secondary;
        }
        return main.interactions == null || !main.interactions.containsKey(type) ? secondary : primary;
    }

    /** {@code InteractionConfiguration.getPriorityFor}: the slot's value, else the default's, else 0. */
    private static int priority(meridian.protocol.ItemBase item, InteractionType type, meridian.protocol.PrioritySlot slot) {
        if (item.interactionConfig == null || item.interactionConfig.priorities == null) {
            return 0;
        }
        meridian.protocol.InteractionPriority p = item.interactionConfig.priorities.get(type);
        if (p == null || p.values == null) {
            return 0;
        }
        Integer v = p.values.get(slot);
        if (v == null) {
            v = p.values.get(meridian.protocol.PrioritySlot.Default);
        }
        return v == null ? 0 : v;
    }

    @Override
    public CompletableFuture<InteractionOutcome> hitEntity(int entityId) {
        return enqueueForge(() -> forgeAim(entityId, InteractionType.Primary));
    }

    @Override
    public CompletableFuture<InteractionOutcome> useOnEntity(int entityId) {
        return enqueueForge(() -> forgeAim(entityId, InteractionType.Use));
    }

    @Override
    public CompletableFuture<InteractionOutcome> dodge() {
        return enqueueForge(() -> forgeAim(-1, InteractionType.Dodge));
    }

    @Override
    public CompletableFuture<InteractionOutcome> useItem() {
        return enqueueForge(() -> forgeAim(-1, InteractionType.Secondary));
    }

    @Override
    public CompletableFuture<InteractionOutcome> useItem(Duration hold) {
        return enqueueForge(() -> forgeAim(-1, InteractionType.Secondary, seconds(hold)));
    }

    @Override
    public CompletableFuture<InteractionOutcome> useItem(Duration hold, boolean interrupt) {
        return enqueueForge(() -> forgeAim(-1, InteractionType.Secondary, seconds(hold)),
                interrupt ? new Press(InteractionType.Secondary, true, false) : Press.AFTER_SERVER);
    }

    @Override
    public CompletableFuture<InteractionOutcome> swing() {
        return enqueueForge(() -> forgeAim(-1, InteractionType.Primary));
    }

    @Override
    public CompletableFuture<InteractionOutcome> swing(Duration hold) {
        return enqueueForge(() -> forgeAim(-1, InteractionType.Primary, seconds(hold)));
    }

    /** A hold in seconds; a negative or missing one is a tap. */
    private static float seconds(Duration hold) {
        return hold == null || hold.isNegative() ? 0f : hold.toNanos() / 1_000_000_000f;
    }

    /** What the server adds to the use distance before it refuses ({@code MAX_INTERACTION_DISTANCE_BUFFER}). */
    private static final double REACH_BUFFER = 2.0;
    /** The creative reach a client has unless its settings say otherwise. */
    private static final double CREATIVE_REACH = 10.0;
    /** The most creative reach the server takes from a client's settings. */
    private static final double MAX_CREATIVE_REACH = 128.0;
    /** The creative reach the client's preferences set ({@link #onPreferences}). */
    private volatile double creativeReach = CREATIVE_REACH;

    /**
     * An entity as an aim, with whether the player reaches it - the server's
     * {@code canPlayerInteractWithEntity}: the held item's use distance for the game mode (at
     * least the creative reach in Creative) plus a buffer, from the eyes to the entity's feet.
     * {@code null} while core does not see it.
     */
    private EntityTarget reachable(int entityId) {
        EntityTarget target = entities.target(entityId);
        if (target == null) {
            return null;
        }
        String held = inventory.itemInHandId();
        double reach = useDistance(held == null || held.isEmpty() ? null : held) + REACH_BUFFER;
        return target.withReach(target.distanceSqFromEyes(eyeHeight.getAsDouble()) <= reach * reach);
    }

    /**
     * Forges {@code type} with the held item aimed at an entity - or, for {@code entityId < 0},
     * at nothing: no block, no entity.
     */
    private Started forgeAim(int entityId, InteractionType type) {
        return forgeAim(entityId, type, Float.NaN);
    }

    /** {@link #forgeAim(int, InteractionType)} with the button held {@code holdSeconds} ({@code NaN}: the default hold). */
    private Started forgeAim(int entityId, InteractionType type, float holdSeconds) {
        if (session == null) {
            log.warn("meridian-core: interaction {} requested but no session yet", type);
            return Started.NOTHING;
        }
        EntityTarget target = null;
        if (entityId >= 0) {
            target = reachable(entityId);
            if (target == null) {
                log.warn("meridian-core: {} on entity {} - core does not see it", type, entityId);
                return Started.NOTHING;
            }
        }
        // The hand the server runs it with: a shield in the utility slot takes a sword's Secondary.
        String itemId = handItem(type);
        ForgeRun run = startRun(null, type, DataKind.NONE, itemId, BlockFace.None, null, Map.of(),
                BlockFace.None, target, Integer.MIN_VALUE, holdSeconds);
        return run == null ? Started.NOTHING : Started.of(run);
    }

    /**
     * Forges the held tool's {@code Primary} main chain at {@code pos}. The server runs the swing's
     * area selection and forks a break per block; the run answers each as it is announced.
     */
    private Started forgeDigSwing(BlockPos pos) {
        if (session == null) {
            log.warn("meridian-core: dig swing requested but no session yet");
            return Started.NOTHING;
        }
        String itemId = inventory.itemInHandId();
        // Empty hit-block map: the walk runs the SelectInteraction op but spawns no forks - the
        // server owns the area selection.
        ForgeRun run = startRun(pos, InteractionType.Primary, DataKind.BLOCK, itemId, BlockFace.Up, null,
                Map.of(), BlockFace.Up, null, Integer.MIN_VALUE, Float.NaN);
        if (run == null) {
            log.warn("meridian-core: dig swing — no Primary root for item {}", itemId);
            return Started.NOTHING;
        }
        return Started.of(run);
    }

    /**
     * The server's echo of a chain core forged: the forks it made, to be answered, and the chain's
     * path and states, for the run playing it ({@link ForgeRun#onServer}).
     *
     * <p>A swing's selector, a shovel's area, a hit on an entity: the server runs these on its side
     * and forks a chain per block or entity it picked, naming the root it runs on the fork's first
     * sync ({@code overrideRootInteraction}). Each is answered as one more lane of the run, so its
     * {@code WaitForDataFrom.Client} operations get their data. Forks core sent itself (a
     * gesture's, a {@code ParallelInteraction}'s) are lanes of the run already.
     */
    void onServerEcho(SyncInteractionChain serverChain) {
        if (serverChain == null) {
            return;
        }
        ForgeRun run = runs.get(serverChain.chainId);
        if (run == null) {
            return;
        }
        if (serverChain.newForks != null) {
            for (SyncInteractionChain fork : serverChain.newForks) {
                if (fork != null && fork.forkedId != null) {
                    answerFork(run, fork);
                }
            }
        }
        if (serverChain.forkedId != null) {
            answerFork(run, serverChain); // a fork can arrive as its own sync, too
        }
        run.onServer(serverChain);
        if (serverChain.newForks != null) {
            for (SyncInteractionChain fork : serverChain.newForks) {
                if (fork != null && fork.forkedId != null) {
                    run.onServer(fork);
                }
            }
        }
    }

    /**
     * A chain the server started for a headless player (a negative id): a projectile's miss, a stat
     * effect. The client runs it too and sends its side - the server waits for that on every
     * operation that takes the client's data, and cancels the chain after a few seconds without it.
     * A game does this itself; for a headless session core is the client, and plays it as one more
     * run from the root the server names on its first sync, following the server's word.
     */
    void onServerChain(SyncInteractionChain s) {
        if (s == null) {
            return;
        }
        ForgeRun run = runs.get(s.chainId);
        boolean opens = s.forkedId == null && s.initial && s.overrideRootInteraction > 0;
        if (opens && (run == null || run.isServerStarted())) {
            // The server numbers its own chains itself and may use an id again once one is over.
            ForgeRun fresh = answerServerChain(s);
            if (fresh == null) {
                return;
            }
            runs.put(s.chainId, fresh);
        }
        onServerEcho(s);
    }

    /** The client's side of a chain the server started: a run of its root, not yet playing. */
    private ForgeRun answerServerChain(SyncInteractionChain s) {
        CompiledInteraction compiled = compileRoot(s.overrideRootInteraction);
        if (compiled == null || compiled.size() == 0) {
            log.warn("meridian-core: server chain {} runs root {} - not in registry", s.chainId,
                    s.overrideRootInteraction);
            return null;
        }
        InteractionType type = s.interactionType != null ? s.interactionType : InteractionType.Primary;
        BlockPosition block = s.data != null ? s.data.blockPosition : null;
        InteractionContext ctx = contextFor(type, block, block == null ? DataKind.NONE : DataKind.BLOCK,
                inventory.itemInHandId(), BlockFace.Up);
        if (s.data != null && s.data.entityId >= 0) {
            EntityTarget target = reachable(s.data.entityId);
            if (target != null) {
                ctx = ctx.withEntity(target);
            }
        }
        InteractionChainData data = s.data != null ? s.data.clone() : new InteractionChainData();
        if (data.proxyId == null) {
            data.proxyId = new UUID(0L, 0L);
        }
        ForgeRun.Header header = new ForgeRun.Header(inventory.activeHotbarSlot(),
                inventory.activeUtilitySlot(), inventory.activeToolsSlot(), inventory.itemInHandId(),
                inventory.utilityItemId(), inventory.toolsItemId());
        log.info("meridian-core: answering server chain {} ({} root {})", s.chainId, type,
                s.overrideRootInteraction);
        return new ForgeRun(host, s.chainId, header, type, new ChainWalk(compiled, ctx, resolver, Map.of()).live(forceBody),
                data, ctx, Map.of()).serverStarted();
    }

    /** The server cancelled a chain core forged, or one of its forks. */
    void onServerCancel(CancelInteractionChain cancel) {
        ForgeRun run = runs.get(cancel.chainId);
        if (run != null) {
            run.onCancel(cancel.forkedId);
        }
    }

    /** Answers one fork the server made of a forged chain, once. */
    private void answerFork(ForgeRun run, SyncInteractionChain serverFork) {
        ForkedChainId forkedId = serverFork.forkedId;
        // The server names the fork's root on the fork's first sync - the HitBlock / HitEntity root
        // its selector chose. A later sync of it carries none.
        int forkRoot = serverFork.overrideRootInteraction;
        if (forkRoot <= 0 || run.hasLane(forkedId)) {
            return;
        }
        InteractionType forkType = serverFork.interactionType != null
                ? serverFork.interactionType : InteractionType.Primary;
        CompiledInteraction compiled = compileRoot(forkRoot);
        if (compiled == null) {
            log.warn("meridian-core: server fork root {} not in registry", forkRoot);
            return;
        }
        BlockPosition block = serverFork.data != null ? serverFork.data.blockPosition : null;
        InteractionContext ctx = contextFor(forkType, block, DataKind.BLOCK,
                inventory.itemInHandId(), BlockFace.Up);
        if (block != null) {
            ctx = ctx.withTarget(block);
        }
        InteractionChainData data = new InteractionChainData();
        data.entityId = -1;
        data.proxyId = new UUID(0L, 0L);
        data.targetSlot = 0; // forks report targetSlot 0, as the client does
        data.blockPosition = block != null ? block.clone() : null;
        if (serverFork.data != null && serverFork.data.entityId >= 0) {
            // A HitEntity fork is aimed at the entity the swing hit.
            EntityTarget hit = reachable(serverFork.data.entityId);
            if (hit != null) {
                ctx = ctx.withEntity(hit);
            }
        }
        if (block == null && serverFork.data != null) {
            // An entity fork names the entity and the hit point the server gave it.
            data.entityId = serverFork.data.entityId;
            data.hitLocation = serverFork.data.hitLocation;
        }
        if (run.answerFork(forkedId, forkType, new ChainWalk(compiled, ctx, resolver, Map.of()).live(forceBody), data)) {
            log.info("meridian-core: answering server fork {} of chain {} (root {})",
                    ForgeRun.path(forkedId), run.chainId(), forkRoot);
        }
    }

    /** A parent chain that carries a single fork sync in its {@code newForks}. */
    private SyncInteractionChain forkParent(int forgedChainId, SyncInteractionChain fork,
                                            InteractionType type) {
        SyncInteractionChain c = new SyncInteractionChain();
        c.activeHotbarSlot = inventory.activeHotbarSlot();
        c.activeUtilitySlot = inventory.activeUtilitySlot();
        c.activeToolsSlot = inventory.activeToolsSlot();
        c.itemInHandId = inventory.itemInHandId();
        c.utilityItemId = inventory.utilityItemId();
        c.toolsItemId = inventory.toolsItemId();
        c.initial = false;
        c.desync = false;
        c.overrideRootInteraction = Integer.MIN_VALUE;
        c.interactionType = type;
        c.equipSlot = inventory.activeHotbarSlot();
        c.chainId = forgedChainId;
        // The server takes a fork's sync on the chain it was forked from (syncFork resolves the
        // innermost step there), so a fork forked inside a fork rides in that fork's packet.
        c.forkedId = outerFork(fork.forkedId);
        c.state = sentStates.getOrDefault(forkKey(forgedChainId, c.forkedId), InteractionState.NotFinished);
        c.newForks = new SyncInteractionChain[] {fork};
        c.operationBaseIndex = 0;
        c.interactionData = null;
        InteractionChainData data = new InteractionChainData();
        data.entityId = -1;
        data.proxyId = new UUID(0L, 0L);
        data.targetSlot = Integer.MIN_VALUE;
        c.data = data;
        return c;
    }

    /**
     * One fork of one chain, by its whole path: a fork spawned inside a fork (a pickaxe's hit,
     * forked from the swing's own parallel fork) is a different fork from its parent.
     */
    private static String forkKey(int chainId, ForkedChainId f) {
        StringBuilder sb = new StringBuilder().append(chainId);
        for (ForkedChainId at = f; at != null; at = at.forkedId) {
            sb.append('/').append(at.entryIndex).append('.').append(at.subIndex);
        }
        return sb.toString();
    }

    /** The fork a nested fork was spawned in - its path without the last step; {@code null} at the top. */
    private static ForkedChainId outerFork(ForkedChainId f) {
        if (f == null || f.forkedId == null) {
            return null;
        }
        return new ForkedChainId(f.entryIndex, f.subIndex, outerFork(f.forkedId));
    }

    @Override
    public CompletableFuture<InteractionOutcome> waterBlock(BlockPos pos) {
        return enqueueForge(() -> forge(pos, InteractionType.Secondary, DataKind.BLOCK));
    }

    @Override
    public CompletableFuture<InteractionOutcome> plantOnBlock(BlockPos pos) {
        return enqueueForge(() -> forge(pos, InteractionType.Secondary, DataKind.PLACEMENT));
    }

    @Override
    public CompletableFuture<InteractionOutcome> placeOnBlock(BlockPos pos) {
        return placeOnBlock(pos, Face.UP);
    }

    @Override
    public CompletableFuture<InteractionOutcome> placeOnBlock(BlockPos target, Face face) {
        return placeOnBlock(target, face, face);
    }

    @Override
    public CompletableFuture<InteractionOutcome> placeOnBlock(BlockPos target, Face face, Face orient) {
        // Same Secondary / PlaceBlockInteraction forge as plant — the server
        // places whatever block the held item maps to, not just a crop — but
        // against the chosen face, so the block can land sideways / below. The
        // orientation is derived from a separate face: the server stores the
        // sent rotation verbatim, so facing is independent of the attach side.
        BlockFace bf = toBlockFace(face);
        BlockFace ob = toBlockFace(orient);
        return enqueueForge(() ->
                forge(target, InteractionType.Secondary, DataKind.PLACEMENT, bf, null, ob));
    }

    @Override
    public CompletableFuture<InteractionOutcome> placeOnBlock(BlockPos target, Face face, int rotation) {
        if (rotation < 0 || rotation > 63) {
            return placeOnBlock(target, face);
        }
        BlockFace bf = toBlockFace(face);
        // The forges run one at a time, in order: the rotation rides this one only.
        return enqueueForge(() -> {
            forcedRotation = rotation;
            try {
                return forge(target, InteractionType.Secondary, DataKind.PLACEMENT, bf, null, bf);
            } finally {
                forcedRotation = -1;
            }
        });
    }

    /** The orientation number the placement being forged carries as it is, or -1: worked out. */
    private volatile int forcedRotation = -1;

    private static BlockFace toBlockFace(Face face) {
        return switch (face) {
            case UP -> BlockFace.Up;
            case DOWN -> BlockFace.Down;
            case NORTH -> BlockFace.North;
            case SOUTH -> BlockFace.South;
            case EAST -> BlockFace.East;
            case WEST -> BlockFace.West;
        };
    }

    @Override
    public CompletableFuture<InteractionOutcome> switchHotbarSlot(int slot) {
        return enqueueForge(() -> doSwitchHotbarSlot(slot));
    }

    // ------------------------------------------------------------------
    // The hands held by one short operation
    // ------------------------------------------------------------------

    /** What an operation holding the hands does with them; nothing else gets in between. */
    public interface Hands {
        CompletableFuture<InteractionOutcome> switchHotbarSlot(int slot);
        CompletableFuture<InteractionOutcome> useItem();
    }

    /** Longest the hands are held: past it they are let go whatever the operation is doing. */
    static final long HANDS_MAX_MS = 20_000;
    /** The operation holding the hands, or {@code null}; guarded by {@code this}. */
    private Object handsHolder;
    /** Others' forges asked for while the hands were held, in order; guarded by {@code this}. */
    private final ArrayDeque<QueuedItem> heldBack = new ArrayDeque<>();
    /** Done when the last operation that asked for the hands has let them go; guarded by {@code this}. */
    private CompletableFuture<Void> handsFree = CompletableFuture.completedFuture(null);

    /**
     * Runs a short operation of several steps - a repair: slot to the kit, the kit used, the server's
     * page answered, the slot back - with the hands to itself. Forges asked for meanwhile by anyone
     * else (a guard turning to a mob, a dig) wait and run after, in the order asked; a forge pressed
     * now does not cut the operation's chain short. Operations asking while one holds the hands take
     * turns. The hands are let go when the operation's future completes, or after
     * {@link #HANDS_MAX_MS} at the latest.
     */
    public <T> CompletableFuture<T> withHands(java.util.function.Function<Hands, CompletableFuture<T>> operation) {
        Object holder = new Object();
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> before;
        synchronized (this) {
            before = handsFree;
            handsFree = mine;
        }
        Hands hands = new Hands() {
            @Override public CompletableFuture<InteractionOutcome> switchHotbarSlot(int slot) {
                return enqueueForge(() -> doSwitchHotbarSlot(slot), Press.QUEUED, holder);
            }
            @Override public CompletableFuture<InteractionOutcome> useItem() {
                return enqueueForge(() -> forgeAim(-1, InteractionType.Secondary), Press.QUEUED, holder);
            }
        };
        CompletableFuture<T> result = before.thenCompose(v -> {
            synchronized (this) {
                handsHolder = holder;
            }
            try {
                return operation.apply(hands).orTimeout(HANDS_MAX_MS, TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
        result.whenComplete((r, e) -> {
            letGo(holder);
            mine.complete(null);
        });
        return result;
    }

    /** The hands let go: what was held back goes on the queue, in the order it was asked for. */
    private synchronized void letGo(Object holder) {
        if (handsHolder != holder) {
            return;
        }
        handsHolder = null;
        if (heldBack.isEmpty()) {
            return;
        }
        log.info("meridian-core: hands let go - {} forge(s) held back go on", heldBack.size());
        forgeQueue.addAll(heldBack);
        heldBack.clear();
        if (!forging) {
            runNextForge();
        }
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
    private CompletableFuture<InteractionOutcome> enqueueForge(QueuedForge forge) {
        return enqueueForge(forge, Press.QUEUED);
    }

    /**
     * {@link #enqueueForge(QueuedForge)}, pressed as {@code press} says: a forge pressed now, with
     * nothing else waiting, cuts the chain in flight short where that chain's rules let it - as the
     * game cuts a swing short when the guard is raised in it - and starts at once.
     */
    private CompletableFuture<InteractionOutcome> enqueueForge(QueuedForge forge, Press press) {
        return enqueueForge(forge, press, null);
    }

    /**
     * {@link #enqueueForge(QueuedForge, Press)} for the operation holding the hands ({@code holder},
     * {@link #withHands}), or for anyone ({@code null}): while another holds them, the forge is held
     * back - not run, and not cutting anything short - until they are let go.
     */
    private synchronized CompletableFuture<InteractionOutcome> enqueueForge(QueuedForge forge, Press press, Object holder) {
        CompletableFuture<InteractionOutcome> done = new CompletableFuture<>();
        if (handsHolder != null && handsHolder != holder) {
            heldBack.addLast(new QueuedItem(forge, done, press.waitForServer()));
            return done;
        }
        forgeQueue.addLast(new QueuedItem(forge, done, press.waitForServer()));
        if (!forging) {
            runNextForge();
        } else if (press.now() && forgeQueue.size() == 1) {
            cutShort(press.type());
        }
        return done;
    }

    /**
     * Cuts the chain in flight short for a chain of {@code type} pressed now, where its rules let
     * that type interrupt it ({@link ChainRules}): the client's way - nothing more of it goes out,
     * and the server, by the same rules, cancels it once the new chain comes in - provided the server
     * still stands where the rules let it in ({@link ForgeRun#interruptedBy}). Called with the
     * {@code this} monitor held.
     */
    private void cutShort(InteractionType type) {
        ForgeRun run = inFlight;
        if (run == null) {
            return;
        }
        OptionalInt rootId = resolveRoot(type, handItem(type));
        RootInteraction root = rootId.isPresent() ? registry.root(rootId.getAsInt()).orElse(null) : null;
        if (run.interruptedBy(type, root, id -> registry.root(id).orElse(null))) {
            log.info("meridian-core: a {} pressed now cuts forged {} chain {} short", type, run.type(), run.chainId());
            run.interrupt();
        }
    }

    /**
     * Pulls the next forge off the queue and starts it. The queue moves on a tick after the chain's
     * last packet - the client starts its next chain once its own is played, not once the server has
     * said how it went - and the caller's future completes with the server's word. The queue only
     * serializes (overlapping chains of one player are invalid); it adds no padding, so a burst of
     * instant harvests runs back-to-back at the tick rate. Any human-like spacing is the calling
     * plugin's choice. Called only with the {@code this} monitor held.
     */
    private synchronized void runNextForge() {
        QueuedItem next = forgeQueue.peekFirst();
        if (next == null) {
            forging = false;
            if (inFlight == null || inFlight.outcome().isDone()) inFlight = null;
            return;
        }
        forging = true;
        if (next.waitForServer() && inFlight != null && !inFlight.outcome().isDone()) {
            // interrupt=false promises the preceding swing will finish on the server too. Its
            // last client packet can arrive a server tick before the chain/forks actually end;
            // the old guard's Interrupting:Primary rule would otherwise cancel that tail.
            inFlight.outcome().whenComplete((v, error) -> nextForgeIn(Duration.ZERO));
            return;
        }
        forgeQueue.removeFirst();
        Started started;
        try {
            started = next.forge().start();
        } catch (RuntimeException ex) {
            log.warn("meridian-core: queued forge failed", ex);
            started = Started.NOTHING;
        }
        inFlight = started.run();
        started.outcome().whenComplete((outcome, error) ->
                next.done().complete(outcome != null ? outcome : InteractionOutcome.notSent()));
        // Even an instant chain occupies the tick its packet lands on - but one cut short by the
        // forge pressed over it lets that one go out at once, as the client sends it.
        ForgeRun run = started.run();
        started.localDone().whenComplete((v, error) -> nextForgeIn(run != null && run.wasInterrupted()
                ? Duration.ZERO : Duration.ofNanos(ForgeRun.TICK_NANOS)));
    }

    private void nextForgeIn(Duration delay) {
        try {
            scheduler.schedule(this::runNextForge, delay);
        } catch (RejectedExecutionException terminated) {
            // The module was torn down (e.g. after a disconnect) while a stale UI still holds this
            // control - the scheduler is shut down. Complete the futures and stop draining; never
            // propagate to the caller (a Swing button handler) as an unhandled exception.
            List<QueuedItem> dropped;
            synchronized (this) {
                forging = false;
                dropped = new ArrayList<>(forgeQueue);
                dropped.addAll(heldBack);
                heldBack.clear();
                forgeQueue.clear();
            }
            log.warn("meridian-core: forge scheduler is shut down (module torn down?) — "
                    + "dropping {} queued forge(s)", dropped.size());
            dropped.forEach(q -> q.done().complete(InteractionOutcome.notSent()));
        }
    }

    /** Body of {@link #switchHotbarSlot}. */
    private Started doSwitchHotbarSlot(int slot) {
        if (session == null) {
            log.warn("meridian-core: switchHotbarSlot requested but no session yet");
            return Started.NOTHING;
        }
        if (slot == inventory.activeHotbarSlot()) {
            // Already on it: the slot is the one asked for.
            return Started.settled(new InteractionOutcome(InteractionOutcome.Status.FINISHED, List.of(), Map.of(), 0));
        }
        if (resolveRoot(InteractionType.SwapFrom, inventory.itemInHandId()).isEmpty()) {
            log.warn("meridian-core: no SwapFrom root known — cannot switch hotbar slot");
            return Started.NOTHING;
        }

        // The held item's SwapFrom root, walked like any other: it ends in the
        // ChangeActiveSlotInteraction (reading the slot to go to from data.targetSlot), and an item
        // can do things before it lets go - a drawn bow puts its arrow back, gated on its stats.
        String itemId = inventory.itemInHandId();
        int from = inventory.activeHotbarSlot();
        ForgeRun run = startRun(null, InteractionType.SwapFrom, DataKind.NONE, itemId, BlockFace.None, null,
                Map.of(), BlockFace.None, null, slot, Float.NaN);
        if (run == null) {
            log.warn("meridian-core: SwapFrom root for {} could not be walked", itemId);
            return Started.NOTHING;
        }
        log.info("meridian-core: forged SwapFrom - hotbar slot {} -> {}", from, slot);

        // The server's ChangeActiveSlotInteraction always sets the slot — mirror it so a follow-up
        // forge sends the matching activeHotbarSlot / item. (The chain itself keeps reporting the
        // slot it leaves: its header was taken at its start.)
        inventory.observeActiveSlots(slot, inventory.activeUtilitySlot(),
                inventory.activeToolsSlot());
        // The server tells a client nothing of a slot it changed (it sends SetActiveSlot only to
        // correct one): a game behind the session is put on the slot the server is now on, so the
        // hand it shows, and the chains it sends next, match.
        if (!headless(session)) {
            SetActiveSlot onClient = new SetActiveSlot();
            onClient.inventorySectionId = HOTBAR_SECTION;
            onClient.activeSlot = slot;
            session.sendToClient(onClient);
        }
        // A switch the server did not run - cancelled: the hand's item changed under it (a rubble taken
        // up into the empty slot in hand while the switch was on its way, 22.11) - left the server on
        // the slot it was on: core's picture goes back to it, so the next switch is sent and the next
        // dig is not made with "the pickaxe" the server never took into the hand.
        ProxySession live = session;
        run.outcome().thenAccept(o -> {
            if (o.status() == InteractionOutcome.Status.FINISHED || inventory.activeHotbarSlot() != slot) {
                return;
            }
            log.info("meridian-core: the switch to hotbar slot {} was not made ({}) - still on slot {}", slot, o.status(), from);
            inventory.observeActiveSlots(from, inventory.activeUtilitySlot(), inventory.activeToolsSlot());
            if (!headless(live)) {
                SetActiveSlot back = new SetActiveSlot();
                back.inventorySectionId = HOTBAR_SECTION;
                back.activeSlot = from;
                live.sendToClient(back);
            }
        });
        return Started.of(run);
    }

    /** Whether no game is behind the session - then core is the client. */
    private static boolean headless(ProxySession session) {
        try {
            return session != null && session.headless() != null;
        } catch (NoSuchMethodError olderProxy) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Forge
    // ------------------------------------------------------------------

    /** Builds one forged chain and starts playing it. */
    private Started forge(BlockPos pos, InteractionType type, DataKind dataKind) {
        return forge(pos, type, dataKind, BlockFace.Up, null);
    }

    private Started forge(BlockPos pos, InteractionType type, DataKind dataKind, BlockFace face) {
        return forge(pos, type, dataKind, face, null, face);
    }

    /**
     * Forge variant carrying the placement face (ignored for non-placement) and,
     * for an area dig, the set of blocks each {@code SelectInteraction} fork breaks.
     */
    private Started forge(BlockPos pos, InteractionType type, DataKind dataKind, BlockFace face,
                      List<BlockPos> hitBlocks) {
        return forge(pos, type, dataKind, face, hitBlocks, face);
    }

    /**
     * Forge variant taking a separate {@code orientFace} for the placed block's
     * rotation, independent of the geometry {@code face} it attaches against.
     */
    private Started forge(BlockPos pos, InteractionType type, DataKind dataKind, BlockFace face,
                      List<BlockPos> hitBlocks, BlockFace orientFace) {
        if (session == null) {
            log.warn("meridian-core: interaction {} requested but no session yet", type);
            return Started.NOTHING;
        }

        // Chunk-decode verification: block ids around the target.
        log.info("meridian-core: blocks at ({},{},{}) — target={}, below={}, above={}",
                pos.x(), pos.y(), pos.z(),
                chunks.blockIdAt(pos.x(), pos.y(), pos.z()),
                chunks.blockIdAt(pos.x(), pos.y() - 1, pos.z()),
                chunks.blockIdAt(pos.x(), pos.y() + 1, pos.z()));

        String itemId = inventory.itemInHandId();

        // The VM is the primary path - it plays the server's walk honestly, and follows the server
        // where it decides. The captured-sequence replay is the fallback when a root cannot be
        // resolved. No HitBlock forks of our own: the server runs a swing's selector and spawns them
        // (SelectInteraction maps only predicted entity forks), and they are answered as announced.
        ForgeRun run = startRun(pos, type, dataKind, itemId, face, hitBlocks, Map.of(), orientFace, null,
                Integer.MIN_VALUE, Float.NaN);
        if (run != null) {
            return Started.of(run);
        }
        List<TimedChain> sequence = capturedSeq.get(ActionKey.of(type, itemId));
        if (sequence != null && !sequence.isEmpty()) {
            return sendPacedReplay(sequence, pos, type);
        }
        return Started.NOTHING;
    }

    /**
     * Replays a captured chain sequence at its original cadence. A water charge
     * plays out over many server ticks, and the server validates the charge
     * against its own clock — so the packets must be delivered spread over time
     * (one {@code SyncInteractionChains} each), not in one burst.
     */
    private Started sendPacedReplay(List<TimedChain> sequence, BlockPos pos, InteractionType type) {
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
        // Nothing to play, only the server to hear: how the replayed chain went.
        ForgeRun run = ForgeRun.passive(host, forgedId, type);
        runs.put(forgedId, run);
        run.replayedOver(Duration.ofMillis(spanMs));
        return Started.of(run);
    }

    /** Sends one forged chain to the server as its own {@code SyncInteractionChains}. */
    private void sendChain(SyncInteractionChain chain) {
        ProxySession s = session;
        if (s == null) {
            return;
        }
        SyncInteractionChains packet = new SyncInteractionChains();
        packet.updates = new SyncInteractionChain[] {chain};
        chaining.noteSent(chain, this::compileRoot);
        noteState(chain);
        s.sendToServer(packet);
    }

    /** Remembers the state a chain (and the forks it carries) was last sent in. */
    private void noteState(SyncInteractionChain c) {
        if (c == null) {
            return;
        }
        if (c.state != null) {
            sentStates.put(forkKey(c.chainId, c.forkedId), c.state);
        }
        if (c.newForks != null) {
            for (SyncInteractionChain f : c.newForks) {
                noteState(f);
            }
        }
    }

    /** Sends a pre-batched {@code SyncInteractionChains} (main chain + forks). */
    private void sendChains(SyncInteractionChains packet) {
        ProxySession s = session;
        if (s != null) {
            if (packet.updates != null) {
                for (SyncInteractionChain c : packet.updates) {
                    chaining.noteSent(c, this::compileRoot);
                    noteState(c);
                    dumpChain("FORGED-vm", c);
                }
            }
            s.sendToServer(packet);
            ProjectilePlayback playback = projectiles;
            if (playback != null) playback.sent(packet);
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

    /**
     * Dumps every decision-relevant field of a chain — for VM/replay diffing. At debug only: every
     * chain core sends goes through here, a few a second in a dig or a fight (a night of them was
     * nine tenths of a 280 MB log).
     */
    private static void dumpChain(String label, SyncInteractionChain c) {
        if (!log.isDebugEnabled()) {
            return;
        }
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
        log.debug(sb.toString());
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
    /**
     * Starts playing the held item's {@code type} chain: a {@link ChainWalk} of its root under the
     * action's context, live against the server ({@link ForgeRun}), the button held
     * {@code holdSeconds} ({@code NaN}: each charge to its first tier). The chain reports the held
     * items as they are now, for its whole length. {@code null} if the root cannot be resolved.
     */
    private ForgeRun startRun(BlockPos pos, InteractionType type, DataKind dataKind, String itemId,
                              BlockFace face, List<BlockPos> hitBlocks, Map<Integer, Integer> hitBlockMap,
                              BlockFace orientFace, EntityTarget entity, int targetSlot,
                              float holdSeconds) {
        return startRun(pos, type, dataKind, itemId, face, hitBlocks, hitBlockMap, orientFace,
                entity, targetSlot, holdSeconds, w -> { });
    }

    private ForgeRun startRun(BlockPos pos, InteractionType type, DataKind dataKind, String itemId,
                              BlockFace face, List<BlockPos> hitBlocks, Map<Integer, Integer> hitBlockMap,
                              BlockFace orientFace, EntityTarget entity, int targetSlot, float holdSeconds,
                              java.util.function.Consumer<ChainWalk> setup) {
        OptionalInt rootId = resolveRoot(type, itemId);
        if (rootId.isEmpty()) {
            log.warn("meridian-core: no root for {} with item {} — item catalog not "
                    + "received, or perform the action once manually", type, itemId);
            return null;
        }
        CompiledInteraction compiled = compileRoot(rootId.getAsInt());
        if (compiled == null || compiled.size() == 0) {
            log.warn("meridian-core: root {} not in registry", rootId.getAsInt());
            return null;
        }
        InteractionContext ctx = contextFor(type,
                pos == null ? null : new BlockPosition(pos.x(), pos.y(), pos.z()), dataKind, itemId, face);
        if (entity != null) {
            ctx = ctx.withEntity(entity);
        }
        if (dataKind == DataKind.PLACEMENT) {
            // Resolve the block being placed: the PlaceBlock node's id, or — when
            // it's the generic -1 ("place the held item's block") — the item
            // catalog's blockId. Then send the right orientation for its mode.
            int placedId = placedBlockIdOf(compiled);
            if (placedId < 0) {
                placedId = items.blockIdOf(itemId).orElse(-1);
            }
            int forced = forcedRotation;
            ctx = ctx.withBlockRotation(forced >= 0 ? blockRotation(forced)
                    : placementRotationFor(placedId, itemId, orientFace));
        }
        if (hitBlocks != null && !hitBlocks.isEmpty()) {
            List<BlockPosition> targets = new ArrayList<>(hitBlocks.size());
            for (BlockPos b : hitBlocks) {
                targets.add(new BlockPosition(b.x(), b.y(), b.z()));
            }
            ctx = ctx.withHitBlocks(targets);
        }

        InteractionChainData data = new InteractionChainData();
        data.entityId = -1;
        data.proxyId = new UUID(0L, 0L);
        // The main chain carries the clicked block, and the slot a hotbar switch goes to.
        data.blockPosition = pos != null ? new BlockPosition(pos.x(), pos.y(), pos.z()) : null;
        data.targetSlot = targetSlot;
        if (entity != null && entity.inReach()) {
            // Only report a reached entity: the server puts it in the chain's context as target
            // (InteractionManager: data.entityId, data.hitLocation).
            data.entityId = entity.networkId();
            data.hitLocation = new org.joml.Vector3f(entity.hitLocation());
        }
        ForgeRun.Header header = new ForgeRun.Header(inventory.activeHotbarSlot(),
                inventory.activeUtilitySlot(), inventory.activeToolsSlot(), inventory.itemInHandId(),
                inventory.utilityItemId(), inventory.toolsItemId());
        int forgedId = nat.allocateForged();
        ChainWalk walk = new ChainWalk(compiled, ctx, resolver, hitBlockMap).live(forceBody).hold(holdSeconds);
        setup.accept(walk);
        ForgeRun run = new ForgeRun(host, forgedId, header, type, walk, data, ctx, hitBlockMap);
        runs.put(forgedId, run);
        log.info("meridian-core: forged {} chain {} (root {}, item {}) at {} — live", type, forgedId,
                rootId.getAsInt(), itemId,
                pos == null ? "no block" : "(" + pos.x() + "," + pos.y() + "," + pos.z() + ")");
        run.start();
        return run;
    }

    /**
     * The VM entry-point root: the held item's asset binding ({@link ItemRegistry}),
     * or the chain observed for this (type, item) when the catalog is not in yet.
     */
    private OptionalInt resolveRoot(InteractionType type, String itemId) {
        // The server's InteractionContext.getRootInteractionId: the player entity's own roots
        // first (its Interactions component), then the held item's.
        Integer own = entities.selfInteractions().get(type);
        if (own != null) {
            return OptionalInt.of(own);
        }
        OptionalInt fromItem = items.rootFor(itemId, type);
        if (fromItem.isPresent()) {
            return fromItem;
        }
        Integer observed = observedRoot.get(ActionKey.of(type, itemId));
        return observed == null ? OptionalInt.empty() : OptionalInt.of(observed);
    }
}
