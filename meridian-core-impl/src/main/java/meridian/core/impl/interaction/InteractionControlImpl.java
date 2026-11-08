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
import meridian.api.module.Scheduler;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
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
    private record Started(CompletableFuture<Void> localDone, CompletableFuture<InteractionOutcome> outcome) {
        static final Started NOTHING = settled(InteractionOutcome.notSent());

        static Started of(ForgeRun run) {
            return new Started(run.localDone(), run.outcome());
        }

        static Started settled(InteractionOutcome outcome) {
            return new Started(CompletableFuture.completedFuture(null), CompletableFuture.completedFuture(outcome));
        }
    }

    /** A unit of forging work on the serialized queue: builds the chain and starts playing it. */
    @FunctionalInterface
    private interface QueuedForge {
        Started start();
    }

    /** A forge waiting on the queue, paired with the future its caller holds. */
    private record QueuedItem(QueuedForge forge, CompletableFuture<InteractionOutcome> done) {}

    private final InteractionRegistry registry;
    private final InventoryTracker inventory;
    private final ChunkTracker chunks;
    private final WorldStateImpl worldState;
    private final ItemRegistry items;
    private final Scheduler scheduler;
    /** Network id &rarr; that entity as an aim (where it stands, its own interactions), or {@code null}. */
    private final EntityLookup entities;

    private volatile ProxySession session;
    private volatile BlockPosition targetBlock;
    /** The player's last observed movement state — what {@code ConditionInteraction} tests. */
    private volatile MovementStates movementStates;
    /** The player's last observed game mode — what {@code ConditionInteraction.requiredGameMode} tests. */
    private volatile GameMode gameMode;
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
     * just calls {@code block.water()} in a loop — the spacing is core's job.
     */
    private final ArrayDeque<QueuedItem> forgeQueue = new ArrayDeque<>();
    /** {@code true} while a forge is in flight — guarded by {@code this}. */
    private boolean forging;

    public InteractionControlImpl(InteractionRegistry registry, InventoryTracker inventory,
                                  ChunkTracker chunks, WorldStateImpl worldState,
                                  ItemRegistry items, Scheduler scheduler,
                                  EntityLookup entities) {
        this.entities = entities;
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

    /** Tracks the player's movement state — {@code ConditionInteraction}'s input. */
    void onMovementStates(MovementStates states) {
        if (states != null) {
            this.movementStates = states;
        }
    }

    /** Tracks the player's game mode — {@code ConditionInteraction.requiredGameMode}'s input. */
    void onGameMode(GameMode mode) {
        if (mode != null) {
            this.gameMode = mode;
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
        return face == BlockFace.Down
                ? new BlockRotation(Rotation.None, Rotation.OneEighty, Rotation.None)
                : new BlockRotation(Rotation.None, Rotation.None, Rotation.None);
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
        Rotation yaw = switch (q) {
            case 1 -> Rotation.Ninety;
            case 2 -> Rotation.OneEighty;
            case 3 -> Rotation.TwoSeventy;
            default -> Rotation.None;
        };
        return new BlockRotation(yaw, Rotation.None, Rotation.None);
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
            case Up, Down     -> new BlockRotation(Rotation.None,   Rotation.None,   Rotation.None);
            case North, South -> new BlockRotation(Rotation.None,   Rotation.Ninety, Rotation.None);
            case East, West   -> new BlockRotation(Rotation.Ninety, Rotation.Ninety, Rotation.None);
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
        GameMode mode = gameMode;
        Map<String, Integer> vars = items.varsFor(itemId);
        // Placement rotation is filled in by buildFromVm (it knows the placed
        // block id) via withBlockRotation; null here means "default for now".
        InteractionContext ctx = dataKind == DataKind.PLACEMENT
                ? InteractionContext.ofPlacement(type, target, blockType, blockId, face,
                        null, movement, mode, vars)
                : InteractionContext.ofBlock(type, target, blockType, blockId, face,
                        movement, mode, vars);
        // The player's own effects as last seen - what effect conditions on the user test.
        return ctx.withEffects(new WalkEffects(entities.selfEffects(), null, entities.selfStats()));
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

    /** Where the eyes are above the feet - the server's player eye height. */
    private static final double EYE_HEIGHT = 1.6;
    /** What the server adds to the use distance before it refuses ({@code MAX_INTERACTION_DISTANCE_BUFFER}). */
    private static final double REACH_BUFFER = 2.0;
    /** The creative reach a client has unless its settings say otherwise. */
    private static final double CREATIVE_REACH = 10.0;

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
        GameMode mode = gameMode != null ? gameMode : GameMode.Adventure;
        double reach = items.useDistance(inventory.itemInHandId(), mode);
        if (mode == GameMode.Creative) {
            reach = Math.max(reach, CREATIVE_REACH);
        }
        reach += REACH_BUFFER;
        return target.withReach(target.distanceSqFromEyes(EYE_HEIGHT) <= reach * reach);
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
        String itemId = inventory.itemInHandId();
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
    // Forge queue — core owns the chain-start cadence
    // ------------------------------------------------------------------

    /**
     * Adds a forge to the queue and returns the future that completes when it
     * has played out. If nothing is in flight it starts immediately; otherwise
     * it runs once the chains ahead of it have played out. The build happens
     * when the forge is <em>dequeued</em>, not here — so a queued slot-switch's
     * inventory mirror is in place before the forges behind it read it.
     */
    private synchronized CompletableFuture<InteractionOutcome> enqueueForge(QueuedForge forge) {
        CompletableFuture<InteractionOutcome> done = new CompletableFuture<>();
        forgeQueue.addLast(new QueuedItem(forge, done));
        if (!forging) {
            runNextForge();
        }
        return done;
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
        QueuedItem next = forgeQueue.pollFirst();
        if (next == null) {
            forging = false;
            return;
        }
        forging = true;
        Started started;
        try {
            started = next.forge().start();
        } catch (RuntimeException ex) {
            log.warn("meridian-core: queued forge failed", ex);
            started = Started.NOTHING;
        }
        started.outcome().whenComplete((outcome, error) ->
                next.done().complete(outcome != null ? outcome : InteractionOutcome.notSent()));
        // Even an instant chain occupies the tick its packet lands on.
        started.localDone().whenComplete((v, error) -> nextForgeIn(Duration.ofNanos(ForgeRun.TICK_NANOS)));
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
        return Started.of(run);
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
            ctx = ctx.withBlockRotation(placementRotationFor(placedId, itemId, orientFace));
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
        if (entity != null) {
            // Aimed at an entity: the server puts it in the chain's context as the target
            // (InteractionManager: data.entityId, data.hitLocation).
            data.entityId = entity.networkId();
            data.hitLocation = new org.joml.Vector3f(entity.hitLocation());
        }
        ForgeRun.Header header = new ForgeRun.Header(inventory.activeHotbarSlot(),
                inventory.activeUtilitySlot(), inventory.activeToolsSlot(), inventory.itemInHandId(),
                inventory.utilityItemId(), inventory.toolsItemId());
        int forgedId = nat.allocateForged();
        ForgeRun run = new ForgeRun(host, forgedId, header, type,
                new ChainWalk(compiled, ctx, resolver, hitBlockMap).live(forceBody).hold(holdSeconds), data, ctx, hitBlockMap);
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
