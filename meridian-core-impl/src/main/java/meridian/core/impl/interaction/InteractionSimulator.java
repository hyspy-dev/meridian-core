package meridian.core.impl.interaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import meridian.protocol.BlockPosition;
import meridian.protocol.BlockRotation;
import meridian.protocol.BlockType;
import meridian.protocol.BreakBlockInteraction;
import meridian.protocol.ChainingInteraction;
import meridian.protocol.ChargingInteraction;
import meridian.protocol.ConditionInteraction;
import meridian.protocol.Interaction;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InteractionType;
import meridian.protocol.MovementStates;
import meridian.protocol.ParallelInteraction;
import meridian.protocol.PlaceBlockInteraction;
import meridian.protocol.ReplaceInteraction;
import meridian.protocol.SelectInteraction;
import meridian.protocol.SimpleBlockInteraction;
import meridian.protocol.SimpleInteraction;
import meridian.protocol.UseBlockInteraction;
import meridian.protocol.UseEntityInteraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks a {@link CompiledInteraction} and produces the {@code InteractionSyncData[]}
 * a forged {@code SyncInteractionChain} carries — the proxy-side port of the
 * server's interaction tick loop.
 *
 * <p>Per {@code Interaction.simulateTick}, each operation resolves to a state:
 * a {@code Finished} operation falls through to the next; a {@code Failed}
 * operation with a branch label jumps to it (the server's
 * {@code SimpleInteraction.tick0}: {@code if Failed && hasLabels jump(label0)}).
 * Each visited operation is recorded as one {@code InteractionSyncData} whose
 * {@code operationCounter} is its index in the flattened list.
 *
 * <p><b>Ported node logic.</b>
 * <ul>
 *   <li>{@code UseEntityInteraction} — fails (no entity target).</li>
 *   <li>{@code UseBlockInteraction} — fails unless the target block type has an
 *       interaction for the chain's type; on success the walk enters that
 *       block-interaction root and returns here when it completes.</li>
 *   <li>{@code ChainingInteraction} — picks branch 0 and jumps to it.</li>
 *   <li>{@code ParallelInteraction} — enters {@code interactions[0]} on the
 *       main chain and spawns {@code interactions[1..]} as fork chains.</li>
 *   <li>{@code ConditionInteraction} — fails unless the player's
 *       {@code MovementStates} match every set flag (server's {@code tick0}).</li>
 *   <li>{@code ReplaceInteraction} — finishes, then switches the walk to
 *       operation 0 of the replacement root ({@code context.execute(nextRoot)}).</li>
 *   <li>{@code ChargingInteraction} — finishes, carrying a {@code chargeValue}.</li>
 *   <li>{@code PlaceBlockInteraction} — finishes, carrying the placed block.</li>
 *   <li>everything else — finishes.</li>
 * </ul>
 */
final class InteractionSimulator {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");
    private static final int MAX_STEPS = 256;
    /** Hytale's interaction tick rate — {@code progress} rises ~1/60 per packet. */
    private static final float TICK_SECONDS = 1.0f / 60.0f;
    private static final int MAX_TICKS = 1800;

    /** Resolves a root id to its flattened form — so the walk can follow a
     * {@code ReplaceInteraction} into the replacement root. */
    @FunctionalInterface
    interface RootResolver {
        /** The compiled replacement root, or {@code null} if unavailable. */
        CompiledInteraction resolve(int rootId);
    }

    /** A saved walk position — where a {@code UseBlockInteraction} call returns to. */
    private record Frame(CompiledInteraction compiled, int counter) {}

    private InteractionSimulator() {}

    /** Simulates {@code root} under {@code ctx} into the executed operations. */
    static List<InteractionSyncData> simulate(CompiledInteraction root, InteractionContext ctx,
                                              RootResolver resolver) {
        List<InteractionSyncData> executed = new ArrayList<>();
        CompiledInteraction compiled = root;
        Set<Integer> visitedRoots = new HashSet<>();
        visitedRoots.add(compiled.rootId());
        Deque<Frame> returnStack = new ArrayDeque<>();
        int counter = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            FlatOp op = compiled.op(counter);
            if (op == null) {
                if (returnStack.isEmpty()) {
                    break; // walked off the end — chain complete
                }
                Frame frame = returnStack.pop(); // return from a UseBlockInteraction call
                compiled = frame.compiled();
                counter = frame.counter();
                continue;
            }
            if (op instanceof FlatOp.Jump jump) {
                counter = jump.target().index;
                if (counter == OpLabel.UNRESOLVED) {
                    log.warn("meridian-core: interaction sim hit an unresolved jump (root {})",
                            compiled.rootId());
                    break;
                }
                continue;
            }
            FlatOp.Node node = (FlatOp.Node) op;
            InteractionState state = evaluateState(node.interaction(), ctx);
            executed.add(syncDataFor(node, counter, compiled.rootId(), state, ctx));

            // ReplaceInteraction.tick0 runs context.execute(nextRoot) on success —
            // the chain continues from operation 0 of the replacement root.
            if (node.interaction() instanceof ReplaceInteraction replace
                    && state != InteractionState.Failed) {
                CompiledInteraction next = switchRoot(replace, compiled, ctx, resolver, visitedRoots);
                if (next != null) {
                    compiled = next;
                    counter = 0;
                    continue;
                }
                break; // replacement root unknown — cannot honestly continue
            }

            // UseBlockInteraction.doInteraction enters the target block's own
            // interaction root, then control returns to finish this chain.
            if (node.interaction() instanceof UseBlockInteraction
                    && state != InteractionState.Failed) {
                CompiledInteraction blockRoot = enterBlockRoot(ctx, resolver, visitedRoots);
                if (blockRoot != null) {
                    returnStack.push(new Frame(compiled, counter + 1));
                    compiled = blockRoot;
                    counter = 0;
                    continue;
                }
                // block-interaction root unresolvable — fall through
            }

            // ChainingInteraction picks a branch; the proxy picks branch 0
            // (the server trusts the client's chainingIndex).
            if (node.interaction() instanceof ChainingInteraction
                    && state != InteractionState.Failed && node.labels().length > 0) {
                counter = node.labels()[0].index;
                continue;
            }

            // ParallelInteraction.tick0 executes interactions[0] on the main
            // chain (call/return); interactions[1..] fork off — not yet modelled.
            if (node.interaction() instanceof ParallelInteraction parallel
                    && state != InteractionState.Failed
                    && parallel.next != null && parallel.next.length > 0) {
                CompiledInteraction sub = enterRoot(parallel.next[0], resolver, visitedRoots);
                if (sub != null) {
                    returnStack.push(new Frame(compiled, counter + 1));
                    compiled = sub;
                    counter = 0;
                    continue;
                }
            }

            if (state == InteractionState.Failed && node.labels().length > 0) {
                counter = node.labels()[0].index; // failed branch
            } else {
                counter++; // fall through to the primary child
            }
        }
        if (executed.size() >= MAX_STEPS) {
            log.warn("meridian-core: interaction sim hit the step cap (root {})", compiled.rootId());
        }
        return executed;
    }

    /**
     * Tick-accurate simulation — the proxy port of the server's per-tick
     * {@code Interaction.tick} loop. Runs the chain at 60&nbsp;Hz: each operation
     * accumulates elapsed time, {@code tickInternal} marks it {@code NotFinished}
     * while {@code time < runTime}, the node's own {@code tick0} may override,
     * and one packet is emitted per tick (the operations resolved that tick plus
     * the one still running). This is the honest model behind {@link #splitPackets}
     * — it reproduces a charge / hold playing out over many ticks.
     */
    static TickedResult simulateTicked(CompiledInteraction root, InteractionContext ctx,
                                       RootResolver resolver,
                                       Map<Integer, Integer> selectHitBlock) {
        List<Packet> packets = new ArrayList<>();
        List<Fork> forks = new ArrayList<>();
        CompiledInteraction compiled = root;
        Set<Integer> visitedRoots = new HashSet<>();
        visitedRoots.add(compiled.rootId());
        Deque<Frame> returnStack = new ArrayDeque<>();
        int counter = 0;
        int flatIndex = 0;
        float opTime = 0.0f;
        List<InteractionSyncData> packet = new ArrayList<>();
        int packetBase = 0;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            boolean running = false;
            for (int step = 0; step < MAX_STEPS; step++) {
                FlatOp op = compiled.op(counter);
                if (op == null) {
                    if (returnStack.isEmpty()) {
                        if (!packet.isEmpty()) {
                            packets.add(new Packet(packetBase, packet));
                        }
                        return new TickedResult(packets, forks); // walked off the end — chain complete
                    }
                    Frame frame = returnStack.pop(); // return from a UseBlockInteraction call
                    compiled = frame.compiled();
                    counter = frame.counter();
                    continue;
                }
                if (op instanceof FlatOp.Jump jump) {
                    // A jump the walk lands on (by fall-through) is an executed
                    // operation — the server records it; jumps a branch skips
                    // over are not reached and not recorded.
                    InteractionSyncData jd = new InteractionSyncData();
                    jd.operationCounter = counter;
                    jd.rootInteraction = compiled.rootId();
                    jd.state = InteractionState.Finished;
                    jd.progress = 0.0f;
                    packet.add(jd);
                    flatIndex++;
                    counter = jump.target().index;
                    if (counter == OpLabel.UNRESOLVED) {
                        log.warn("meridian-core: ticked sim hit an unresolved jump (root {})",
                                compiled.rootId());
                        if (!packet.isEmpty()) {
                            packets.add(new Packet(packetBase, packet));
                        }
                        return new TickedResult(packets, forks);
                    }
                    continue;
                }
                FlatOp.Node node = (FlatOp.Node) op;
                InteractionState state = tickState(node.interaction(), ctx, opTime);
                // Tap/hold gate: a plain SimpleInteraction whose failed branch
                // enters a ChargingInteraction. Its Finished branch is the inert
                // "tap"; a forged interaction holds, so it takes the hold branch.
                if (state == InteractionState.Finished
                        && node.interaction().getClass() == SimpleInteraction.class
                        && node.labels().length > 0
                        && isChargeOp(compiled.op(node.labels()[0].index))) {
                    state = InteractionState.Failed;
                }
                InteractionSyncData sd = syncDataFor(node, counter, compiled.rootId(), state, ctx);
                sd.progress = opTime;
                packet.add(sd);

                if (state == InteractionState.NotFinished) {
                    // Operation still running — the packet ends here this tick;
                    // the running operation keeps its flat index for the next.
                    packets.add(new Packet(packetBase, packet));
                    packet = new ArrayList<>();
                    packetBase = flatIndex;
                    opTime += TICK_SECONDS;
                    running = true;
                    break;
                }

                // Operation resolved this tick.
                if (node.interaction() instanceof ReplaceInteraction replace
                        && state != InteractionState.Failed) {
                    CompiledInteraction next = switchRoot(replace, compiled, ctx, resolver,
                            visitedRoots);
                    if (next == null) {
                        if (!packet.isEmpty()) {
                            packets.add(new Packet(packetBase, packet));
                        }
                        return new TickedResult(packets, forks);
                    }
                    compiled = next;
                    counter = 0;
                } else if (node.interaction() instanceof UseBlockInteraction
                        && state != InteractionState.Failed) {
                    // UseBlockInteraction.doInteraction enters the target
                    // block's own interaction root, then control returns here
                    // to finish the parent chain.
                    CompiledInteraction blockRoot = enterBlockRoot(ctx, resolver, visitedRoots);
                    if (blockRoot != null) {
                        returnStack.push(new Frame(compiled, counter + 1));
                        compiled = blockRoot;
                        counter = 0;
                    } else {
                        counter++;
                    }
                } else if (node.interaction() instanceof ChainingInteraction
                        && state != InteractionState.Failed && node.labels().length > 0) {
                    // ChainingInteraction picks a branch; the proxy picks
                    // branch 0 (the server trusts the client's chainingIndex).
                    counter = node.labels()[0].index;
                } else if (node.interaction() instanceof ParallelInteraction parallel
                        && state != InteractionState.Failed
                        && parallel.next != null && parallel.next.length > 0) {
                    // interactions[0] runs on the main chain (call/return);
                    // interactions[1..] spawn parallel fork chains — recorded
                    // here, flatIndex being this op's index (the fork entryIndex).
                    for (int f = 1; f < parallel.next.length; f++) {
                        forks.add(new Fork(flatIndex, f - 1, parallel.next[f], null));
                    }
                    CompiledInteraction sub = enterRoot(parallel.next[0], resolver,
                            visitedRoots);
                    if (sub != null) {
                        returnStack.push(new Frame(compiled, counter + 1));
                        compiled = sub;
                        counter = 0;
                    } else {
                        counter++;
                    }
                } else if (node.interaction() instanceof SelectInteraction
                        && state != InteractionState.Failed) {
                    // SelectInteraction picks blocks in an area and spawns a fork
                    // running its HitBlock interaction PER block — the shovel dig
                    // swing. The HitBlock root isn't on the wire (the server
                    // resolves it), so we learn it (selectHitBlock). We spawn one
                    // fork per target block (ctx.hitBlocks for an area dig, else
                    // the single target); each fork's BreakBlock breaks its block.
                    Integer hitBlock = selectHitBlock.get(node.interactionId());
                    if (hitBlock != null && hitBlock > 0) {
                        List<BlockPosition> targets = ctx.hitBlocks();
                        if (targets == null || targets.isEmpty()) {
                            if (ctx.targetBlock() != null) {
                                forks.add(new Fork(flatIndex, 0, hitBlock, ctx.targetBlock()));
                            }
                        } else {
                            int sub = 0;
                            for (BlockPosition b : targets) {
                                forks.add(new Fork(flatIndex, sub++, hitBlock, b));
                            }
                        }
                    }
                    counter++;
                } else if (node.interaction() instanceof ChargingInteraction
                        && state == InteractionState.Finished && node.labels().length > 0) {
                    // ChargingInteraction.jumpToChargeValue: a finished charge
                    // routes to a charge-tier label — the basic tier (label 0)
                    // for a minimal hold.
                    counter = node.labels()[0].index;
                } else if (state == InteractionState.Failed && node.labels().length > 0) {
                    counter = node.labels()[0].index;
                } else {
                    counter++;
                }
                opTime = 0.0f;
                flatIndex++;
            }
            if (!running) {
                log.warn("meridian-core: ticked sim hit the step cap (root {})", compiled.rootId());
                if (!packet.isEmpty()) {
                    packets.add(new Packet(packetBase, packet));
                }
                return new TickedResult(packets, forks);
            }
        }
        log.warn("meridian-core: ticked sim hit the tick cap (root {})", compiled.rootId());
        if (!packet.isEmpty()) {
            packets.add(new Packet(packetBase, packet));
        }
        return new TickedResult(packets, forks);
    }

    /**
     * Ported per-tick outcome: {@code tickInternal}'s {@code runTime} gate, then
     * the node's {@code tick0} override.
     */
    private static InteractionState tickState(Interaction node, InteractionContext ctx,
                                              float opTime) {
        // tickInternal: NotFinished until the elapsed time reaches the runTime.
        InteractionState timed = opTime < node.runTime
                ? InteractionState.NotFinished : InteractionState.Finished;
        if (node instanceof UseEntityInteraction) {
            return InteractionState.Failed;
        }
        if (node instanceof UseBlockInteraction) {
            return hasBlockInteraction(ctx.targetBlockType(), ctx.interactionType())
                    ? InteractionState.Finished : InteractionState.Failed;
        }
        if (node instanceof ConditionInteraction condition) {
            return conditionHolds(condition, ctx)
                    ? InteractionState.Finished : InteractionState.Failed;
        }
        if (node instanceof PlaceBlockInteraction) {
            // simulateTick0: NotFinished on the first run, Finished once confirmed.
            return opTime <= 0.0f ? InteractionState.NotFinished : InteractionState.Finished;
        }
        return timed;
    }

    /** Whether {@code op} is a {@code ChargingInteraction} node. */
    private static boolean isChargeOp(FlatOp op) {
        return op instanceof FlatOp.Node n && n.interaction() instanceof ChargingInteraction;
    }

    /** The lowest charge threshold of a {@code ChargingInteraction} — its first tier. */
    private static float lowestChargeKey(ChargingInteraction charging) {
        if (charging.chargedNext == null || charging.chargedNext.isEmpty()) {
            return 0.0f;
        }
        float min = Float.MAX_VALUE;
        for (Float key : charging.chargedNext.keySet()) {
            if (key != null && key < min) {
                min = key;
            }
        }
        return min == Float.MAX_VALUE ? 0.0f : min;
    }

    /**
     * Resolves the {@code ReplaceInteraction} target root — the server's
     * {@code doReplace}: {@code interactionVars.get(var)}, else {@code defaultValue}.
     * Guards against cycles.
     */
    private static CompiledInteraction switchRoot(ReplaceInteraction node,
                                                  CompiledInteraction current,
                                                  InteractionContext ctx, RootResolver resolver,
                                                  Set<Integer> visitedRoots) {
        Map<String, Integer> vars = ctx.interactionVars();
        Integer replacement = (vars != null && node.variable != null)
                ? vars.get(node.variable) : null;
        if (replacement == null) {
            replacement = node.defaultValue; // server's doReplace fallback
        }
        if (resolver == null) {
            log.warn("meridian-core: ReplaceInteraction in root {} but no root resolver",
                    current.rootId());
            return null;
        }
        if (!visitedRoots.add(replacement)) {
            log.warn("meridian-core: ReplaceInteraction would re-enter root {} — stopping",
                    replacement);
            return null;
        }
        CompiledInteraction next = resolver.resolve(replacement);
        if (next == null) {
            log.warn("meridian-core: ReplaceInteraction target root {} not in registry",
                    replacement);
        }
        return next;
    }

    /**
     * Resolves the root a {@code UseBlockInteraction} chains into — the server's
     * {@code doInteraction}: the interaction the target block type maps for the
     * chain's {@code InteractionType} (e.g. a chest's {@code Open_Container}).
     * Guards against re-entering a root already on the walk.
     */
    private static CompiledInteraction enterBlockRoot(InteractionContext ctx,
                                                      RootResolver resolver,
                                                      Set<Integer> visitedRoots) {
        BlockType blockType = ctx.targetBlockType();
        if (blockType == null || blockType.interactions == null) {
            return null;
        }
        Integer rootId = blockType.interactions.get(ctx.interactionType());
        if (rootId == null) {
            return null;
        }
        return enterRoot(rootId, resolver, visitedRoots);
    }

    /**
     * Resolves and compiles {@code rootId} for the walk to enter — the server's
     * {@code context.execute(root)} / {@code pushRoot}. Guards against
     * re-entering a root already on the walk.
     */
    private static CompiledInteraction enterRoot(int rootId, RootResolver resolver,
                                                 Set<Integer> visitedRoots) {
        if (resolver == null) {
            log.warn("meridian-core: cannot enter root {} — no root resolver", rootId);
            return null;
        }
        if (!visitedRoots.add(rootId)) {
            log.warn("meridian-core: walk would re-enter root {} — stopping", rootId);
            return null;
        }
        CompiledInteraction root = resolver.resolve(rootId);
        if (root == null) {
            log.warn("meridian-core: interaction root {} not in registry", rootId);
        }
        return root;
    }

    /** Ported {@code simulateTick0} outcome for one node. */
    private static InteractionState evaluateState(Interaction node, InteractionContext ctx) {
        if (node instanceof UseEntityInteraction) {
            // Forged chains never target an entity (data.entityId = -1).
            return InteractionState.Failed;
        }
        if (node instanceof UseBlockInteraction) {
            // doInteraction: fails unless the target block has an interaction
            // mapped for this interaction type.
            return hasBlockInteraction(ctx.targetBlockType(), ctx.interactionType())
                    ? InteractionState.Finished : InteractionState.Failed;
        }
        if (node instanceof ConditionInteraction condition) {
            // ConditionInteraction.tick0: success unless a set flag mismatches
            // the player's movement state.
            return conditionHolds(condition, ctx)
                    ? InteractionState.Finished : InteractionState.Failed;
        }
        if (node instanceof PlaceBlockInteraction) {
            // PlaceBlockInteraction.simulateTick0 never finishes the op on its
            // first run — the server must place the block and confirm it. The
            // op stays NotFinished until a continuation packet (see splitPackets).
            return InteractionState.NotFinished;
        }
        return InteractionState.Finished;
    }

    /**
     * One C2S packet of a forged chain: its operations and the flat operation
     * index they start at. The server reads {@code operationBaseIndex + i} as
     * each operation's position in the chain, so a continuation packet must
     * carry the index it resumes from — not 0.
     *
     * @param baseIndex the chain operation index of {@code ops[0]}
     * @param ops       the operations carried by this packet
     */
    record Packet(int baseIndex, List<InteractionSyncData> ops) {}

    /**
     * A parallel fork spawned by a {@code ParallelInteraction} — one of its
     * {@code interactions[1..]} roots. {@code entryIndex} is the flat operation
     * index of the spawning {@code ParallelInteraction} (the fork's
     * {@code ForkedChainId.entryIndex}); {@code subIndex} is the fork's 0-based
     * position among that node's forks.
     */
    /** A spawned fork: its structural id, root, and (for a dig HitBlock fork) the block it breaks. */
    record Fork(int entryIndex, int subIndex, int rootId, BlockPosition block) {}

    /** A ticked simulation: the chain's packets and the forks it spawned. */
    record TickedResult(List<Packet> packets, List<Fork> forks) {}

    /**
     * Splits a walk into the C2S packets a real client sends. An operation that
     * a node leaves {@code NotFinished} (a {@code PlaceBlockInteraction}, a
     * water charge) ends a packet; the next packet re-sends that operation as
     * {@code Finished} — the server's {@code NotFinished} &rarr; {@code Finished}
     * handshake — and carries on with the operations after it. Each packet
     * records the flat operation index it begins at.
     */
    static List<Packet> splitPackets(List<InteractionSyncData> walk) {
        List<Packet> packets = new ArrayList<>();
        List<InteractionSyncData> current = new ArrayList<>();
        int packetStart = 0;
        for (int j = 0; j < walk.size(); j++) {
            InteractionSyncData op = walk.get(j);
            current.add(op);
            if (op.state == InteractionState.NotFinished) {
                packets.add(new Packet(packetStart, current));
                // The continuation re-sends this operation (flat index j) as Finished.
                current = new ArrayList<>();
                InteractionSyncData confirmed = op.clone();
                confirmed.state = InteractionState.Finished;
                current.add(confirmed);
                packetStart = j;
            }
        }
        packets.add(new Packet(packetStart, current));
        return packets;
    }

    private static boolean hasBlockInteraction(BlockType blockType, InteractionType type) {
        return blockType != null && blockType.interactions != null
                && blockType.interactions.containsKey(type);
    }

    /**
     * Ported {@code ConditionInteraction.tick0}: each set flag must equal the
     * matching {@code MovementStates} field. {@code requiredGameMode} is not
     * tracked proxy-side and is treated as satisfied. With no movement snapshot
     * the condition is assumed to hold (the server's {@code success = true} default).
     */
    private static boolean conditionHolds(ConditionInteraction c, InteractionContext ctx) {
        MovementStates m = ctx.movementStates();
        if (m == null) {
            return true;
        }
        if (c.jumping != null && c.jumping != m.jumping) {
            return false;
        }
        if (c.swimming != null && c.swimming != m.swimming) {
            return false;
        }
        if (c.crouching != null && c.crouching != m.crouching) {
            return false;
        }
        if (c.running != null && c.running != m.running) {
            return false;
        }
        return c.flying == null || c.flying == m.flying;
    }

    /** Builds the {@code InteractionSyncData} for one executed operation. */
    private static InteractionSyncData syncDataFor(FlatOp.Node node, int counter, int rootId,
                                                   InteractionState state, InteractionContext ctx) {
        InteractionSyncData data = new InteractionSyncData();
        data.operationCounter = counter;
        data.rootInteraction = rootId;
        data.state = state;

        if (node.interaction() instanceof ChainingInteraction) {
            // The server reads this to pick the branch; the proxy always
            // takes branch 0 (see the walk in simulate / simulateTicked).
            data.chainingIndex = 0;
        }

        if (node.interaction() instanceof ChargingInteraction charging) {
            // ChargingInteraction.tick0 reads chargeValue and jumpToChargeValue
            // routes by it — the lowest charge tier (a minimal hold) into the
            // first charge label.
            data.chargeValue = lowestChargeKey(charging);
        } else if (node.interaction() instanceof PlaceBlockInteraction place && ctx.placeBlock() != null) {
            // PlaceBlockInteraction.tick0 requires a non-null blockPosition +
            // blockRotation and reads blockFace; placedBlockId is the node's
            // own block (server falls back to the held item's block at -1).
            data.blockPosition = ctx.placeBlock();
            data.blockRotation = new BlockRotation();
            data.blockFace = ctx.blockFace();
            data.placedBlockId = place.blockId;
        } else if (node.interaction() instanceof SimpleBlockInteraction && ctx.targetBlock() != null) {
            // SimpleBlockInteraction.simulateTick0 records the target block and
            // an Up face — BlockConditionInteraction's matchers test that face.
            data.blockPosition = ctx.targetBlock();
            data.blockFace = ctx.blockFace();
        } else if (node.interaction() instanceof BreakBlockInteraction && ctx.targetBlock() != null) {
            // BreakBlockInteraction breaks the block it carries — the dig HitBlock
            // fork reports the target block + the hit face here.
            data.blockPosition = ctx.targetBlock();
            data.blockFace = ctx.blockFace();
        }
        return data;
    }
}
