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
    /** The player's last observed game mode — what {@code ConditionInteraction.requiredGameMode} tests. */
    private volatile GameMode gameMode;
    /** Player's last observed look yaw (radians) — drives FacingPlayer placement. */
    private volatile float lookYaw;
    private volatile boolean haveLook;
    /** Chain-id NAT: keeps forged chain ids out of the client's counter space. */
    private final ChainIdNat nat = new ChainIdNat();
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
    /**
     * Forged dig chains awaiting the server's own area forks. The shovel's
     * {@code SelectInteraction} runs <em>server-side</em>: when we forge the main
     * dig chain through it, the server runs its area selector and spawns one
     * break fork per block, announcing them back to us (S2C). We answer each
     * with {@link #replyToFork} so the fork's {@code WaitForDataFrom.Client} gate
     * opens and the server breaks its own selected block — no client dig, no
     * hard-coded {@code HitBlock} root.
     */
    private final Set<Integer> reactiveDigChains = ConcurrentHashMap.newKeySet();
    /** (forged chainId, forkedId) already answered — reply once per server fork. */
    private final Set<Long> repliedForks = ConcurrentHashMap.newKeySet();
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
                DataKind.BLOCK, key.item(), BlockFace.Up);
        List<InteractionSimulator.Packet> ticked = InteractionSimulator.simulateTicked(
                compiled, ctx, this::compileRoot, selectHitBlock).packets();

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
        return dataKind == DataKind.PLACEMENT
                ? InteractionContext.ofPlacement(type, target, blockType, blockId, face,
                        null, movement, mode, vars)
                : InteractionContext.ofBlock(type, target, blockType, blockId, face,
                        movement, mode, vars);
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
    public CompletableFuture<Void> digSwing(BlockPos aim) {
        if (aim == null) {
            return CompletableFuture.completedFuture(null);
        }
        // One swing: forge the held tool's Primary main chain through its
        // SelectInteraction. We do NOT pre-compute break forks — the server runs
        // the SelectInteraction's area selector itself and spawns the forks,
        // which we answer reactively (onServerForkAnnounce). For a tool without a
        // SelectInteraction (a pickaxe) the main chain just breaks the aimed block.
        return enqueueForge(() -> forgeDigSwing(aim));
    }

    /**
     * Forges the held tool's {@code Primary} main chain and marks it so the
     * server's area forks for this swing are answered reactively. Returns the
     * chain's tick-duration.
     */
    private int forgeDigSwing(BlockPos pos) {
        ProxySession s = session;
        if (s == null) {
            log.warn("meridian-core: dig swing requested but no session yet");
            return 0;
        }
        String itemId = inventory.itemInHandId();
        // Empty hit-block map: the simulator emits the SelectInteraction op but
        // spawns no forks — the server owns the area selection.
        int[] forgedOut = new int[] {-1};
        SyncInteractionChains[] forged = buildFromVm(pos, InteractionType.Primary, DataKind.BLOCK,
                itemId, BlockFace.Up, null, Map.of(), forgedOut, BlockFace.Up);
        if (forged == null) {
            log.warn("meridian-core: dig swing — no Primary root for item {}", itemId);
            return 0;
        }
        if (forgedOut[0] != -1) {
            reactiveDigChains.add(forgedOut[0]);
            log.info("meridian-core: forged reactive dig swing chain {} (item {}) — "
                    + "awaiting server area forks", forgedOut[0], itemId);
        }
        return sendPacedVm(forged, InteractionType.Primary, pos);
    }

    /**
     * Answers the server's area forks for one of our reactive dig swings. When we
     * forge the main dig chain, the server runs the {@code SelectInteraction}'s
     * selector and spawns a break fork per block, echoing them to us — on a
     * fork's first sync the packet carries its {@code HitBlock} root in
     * {@code overrideRootInteraction} (the server hands us the link the wire
     * otherwise drops). We reply per fork so its {@code WaitForDataFrom.Client}
     * break op can run; the server breaks its own selected block (it ignores the
     * block we send), so the swing clears exactly the real shovel area.
     */
    void onServerForkAnnounce(SyncInteractionChain serverChain) {
        if (serverChain == null || !reactiveDigChains.contains(serverChain.chainId)) {
            return;
        }
        if (serverChain.newForks != null) {
            for (SyncInteractionChain fork : serverChain.newForks) {
                if (fork != null && fork.forkedId != null) {
                    replyToFork(serverChain.chainId, fork);
                }
            }
        }
        // A fork can also arrive as its own sync packet (same chainId, forkedId set).
        if (serverChain.forkedId != null) {
            replyToFork(serverChain.chainId, serverChain);
        }
    }

    /** Builds and sends the C2S fork sync that unblocks one server break fork. */
    private void replyToFork(int forgedChainId, SyncInteractionChain serverFork) {
        ForkedChainId forkedId = serverFork.forkedId;
        long key = ((long) forgedChainId << 32) ^ forkKey(forkedId);
        if (!repliedForks.add(key)) {
            return; // already answered this fork
        }
        int forkRoot = serverFork.overrideRootInteraction;
        if (forkRoot > 0) {
            // Learn it from the server's own announcement (no manual dig needed).
            if (selectHitBlock.putIfAbsent(forkedId.entryIndex, forkRoot) == null) {
                log.info("meridian-core: learned HitBlock fork root {} for SelectInteraction "
                        + "op {} (from server announce)", forkRoot, forkedId.entryIndex);
            }
        } else {
            Integer cached = selectHitBlock.get(forkedId.entryIndex);
            if (cached == null) {
                log.warn("meridian-core: server fork {} (chain {}) carries no root and none "
                        + "cached — cannot answer", forkedId, forgedChainId);
                return;
            }
            forkRoot = cached;
        }
        CompiledInteraction compiled = compileRoot(forkRoot);
        if (compiled == null) {
            log.warn("meridian-core: server fork root {} not in registry", forkRoot);
            return;
        }
        BlockPosition block = serverFork.data != null ? serverFork.data.blockPosition : null;
        InteractionContext ctx = contextFor(InteractionType.Primary, block, DataKind.BLOCK,
                inventory.itemInHandId(), BlockFace.Up);
        if (block != null) {
            ctx = ctx.withTarget(block);
        }
        List<InteractionSimulator.Packet> fp = InteractionSimulator.simulateTicked(
                compiled, ctx, this::compileRoot, Map.of()).packets();
        if (fp.isEmpty()) {
            return;
        }
        log.info("meridian-core: answering server fork {} (chain {}, root {}, {} packet(s))",
                forkedId, forgedChainId, forkRoot, fp.size());
        sendForkReply(forgedChainId, forkedId, block, fp);
    }

    /**
     * Sends the fork's per-tick ops back to the server, each wrapped in a parent
     * chain's {@code newForks} (the path {@code InteractionManager.sync} routes to
     * {@code syncFork}). The server matches the fork by {@code forkedId} and feeds
     * our ops in as its client state.
     */
    private void sendForkReply(int forgedChainId, ForkedChainId forkedId, BlockPosition block,
                               List<InteractionSimulator.Packet> fp) {
        BlockPos bp = block != null ? new BlockPos(block.x, block.y, block.z)
                : new BlockPos(0, 0, 0);
        for (int i = 0; i < fp.size(); i++) {
            InteractionSimulator.Packet p = fp.get(i);
            boolean last = i == fp.size() - 1;
            SyncInteractionChain fork = vmChain(bp, block, InteractionType.Primary, forgedChainId,
                    forkedId, false, last, p.baseIndex(),
                    p.ops().toArray(new InteractionSyncData[0]));
            SyncInteractionChain parent = forkParent(forgedChainId, fork);
            SyncInteractionChains scs = new SyncInteractionChains();
            scs.updates = new SyncInteractionChain[] {parent};
            dumpChain("FORK-REPLY", parent);
            long delayMs = i * 1000L / 60L;
            if (delayMs == 0L) {
                sendChains(scs);
            } else {
                scheduler.schedule(() -> sendChains(scs), Duration.ofMillis(delayMs));
            }
        }
    }

    /** A parent chain that carries a single fork sync in its {@code newForks}. */
    private SyncInteractionChain forkParent(int forgedChainId, SyncInteractionChain fork) {
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
        c.interactionType = InteractionType.Primary;
        c.equipSlot = inventory.activeHotbarSlot();
        c.chainId = forgedChainId;
        c.forkedId = null;
        c.state = InteractionState.NotFinished;
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

    private static long forkKey(ForkedChainId f) {
        return ((long) f.entryIndex << 20) ^ (f.subIndex & 0xFFFFFL);
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
    public CompletableFuture<Void> placeOnBlock(BlockPos pos) {
        return placeOnBlock(pos, Face.UP);
    }

    @Override
    public CompletableFuture<Void> placeOnBlock(BlockPos target, Face face) {
        return placeOnBlock(target, face, face);
    }

    @Override
    public CompletableFuture<Void> placeOnBlock(BlockPos target, Face face, Face orient) {
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
        try {
            scheduler.schedule(() -> {
                // The chain has played out: complete the caller's future (which may
                // enqueue follow-up forges via .thenRun), then start the next one.
                next.done().complete(null);
                runNextForge();
            }, Duration.ofMillis(delayMs));
        } catch (RejectedExecutionException terminated) {
            // The module was torn down (e.g. after a disconnect) while a stale UI
            // still holds this control — the scheduler is shut down. Complete the
            // future and stop draining; never propagate to the caller (a Swing
            // button handler) as an unhandled exception.
            log.warn("meridian-core: forge scheduler is shut down (module torn down?) — "
                    + "dropping {} queued forge(s)", forgeQueue.size() + 1);
            forging = false;
            forgeQueue.clear();
            next.done().complete(null);
        }
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
        return forge(pos, type, dataKind, BlockFace.Up, null);
    }

    private int forge(BlockPos pos, InteractionType type, DataKind dataKind, BlockFace face) {
        return forge(pos, type, dataKind, face, null, face);
    }

    /**
     * Forge variant carrying the placement face (ignored for non-placement) and,
     * for an area dig, the set of blocks each {@code SelectInteraction} fork breaks.
     */
    private int forge(BlockPos pos, InteractionType type, DataKind dataKind, BlockFace face,
                      List<BlockPos> hitBlocks) {
        return forge(pos, type, dataKind, face, hitBlocks, face);
    }

    /**
     * Forge variant taking a separate {@code orientFace} for the placed block's
     * rotation, independent of the geometry {@code face} it attaches against.
     */
    private int forge(BlockPos pos, InteractionType type, DataKind dataKind, BlockFace face,
                      List<BlockPos> hitBlocks, BlockFace orientFace) {
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
        SyncInteractionChains[] forged = buildFromVm(pos, type, dataKind, itemId, face, hitBlocks,
                selectHitBlock, null, orientFace);
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
                                               DataKind dataKind, String itemId, BlockFace face,
                                               List<BlockPos> hitBlocks,
                                               Map<Integer, Integer> hitBlockMap, int[] forgedOut,
                                               BlockFace orientFace) {
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
                new BlockPosition(pos.x(), pos.y(), pos.z()), dataKind, itemId, face);
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
        InteractionSimulator.TickedResult main = InteractionSimulator.simulateTicked(
                compiled, ctx, this::compileRoot, hitBlockMap);
        List<InteractionSimulator.Packet> mainPackets = main.packets();
        if (mainPackets.isEmpty()) {
            return null;
        }
        int forgedId = nat.allocateForged();
        if (forgedOut != null) {
            forgedOut[0] = forgedId;
        }

        // Simulate each ParallelInteraction fork root into its own packet list.
        List<ForkBuild> forks = new ArrayList<>();
        for (InteractionSimulator.Fork fork : main.forks()) {
            CompiledInteraction forkRoot = compileRoot(fork.rootId());
            if (forkRoot == null) {
                log.warn("meridian-core: fork root {} not in registry — skipped", fork.rootId());
                continue;
            }
            // A dig HitBlock fork breaks its own block, so simulate it with a
            // context pointed at that block (its BreakBlock op carries it).
            InteractionContext forkCtx = fork.block() != null ? ctx.withTarget(fork.block()) : ctx;
            InteractionSimulator.TickedResult fr = InteractionSimulator.simulateTicked(
                    forkRoot, forkCtx, this::compileRoot, hitBlockMap);
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
                SyncInteractionChain mc = vmChain(pos, null, type, forgedId, null, t == 0,
                        t == mainPackets.size() - 1, p.baseIndex(),
                        p.ops().toArray(new InteractionSyncData[0]));
                if (t == announceIdx && !forks.isEmpty()) {
                    SyncInteractionChain[] announce =
                            new SyncInteractionChain[forks.size()];
                    for (int k = 0; k < forks.size(); k++) {
                        InteractionSimulator.Fork f = forks.get(k).fork();
                        announce[k] = vmChain(pos, f.block(), type, forgedId,
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
                    chains.add(vmChain(pos, f.block(), type, forgedId,
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
    private SyncInteractionChain vmChain(BlockPos pos, BlockPosition dataBlock,
                                         InteractionType type, int forgedId,
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
        // A dig fork carries the block it breaks; the main chain carries the
        // clicked block. Forks report targetSlot=0 (matching the client).
        data.blockPosition = dataBlock != null
                ? dataBlock : new BlockPosition(pos.x(), pos.y(), pos.z());
        data.targetSlot = forkedId != null ? 0 : Integer.MIN_VALUE;
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
