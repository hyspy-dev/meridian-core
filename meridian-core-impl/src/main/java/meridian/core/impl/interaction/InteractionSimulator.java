package meridian.core.impl.interaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import meridian.core.impl.WorldStateImpl;
import meridian.protocol.BlockConditionInteraction;
import meridian.protocol.BlockIdMatcher;
import meridian.protocol.BlockMatcher;
import meridian.protocol.BlockPosition;
import meridian.protocol.BlockRotation;
import meridian.protocol.BlockType;
import meridian.protocol.BreakBlockInteraction;
import meridian.protocol.ChainingInteraction;
import meridian.protocol.ChangeStateInteraction;
import meridian.protocol.ChargingInteraction;
import meridian.protocol.ConditionInteraction;
import meridian.protocol.ApplyEffectInteraction;
import meridian.protocol.ChangeStatInteraction;
import meridian.protocol.ClearEntityEffectInteraction;
import meridian.protocol.StatsConditionInteraction;
import meridian.protocol.DamageEntityInteraction;
import meridian.protocol.EffectConditionInteraction;
import meridian.protocol.GameMode;
import meridian.protocol.Interaction;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InteractionType;
import meridian.protocol.MovementStates;
import meridian.protocol.ParallelInteraction;
import meridian.protocol.PlaceBlockInteraction;
import meridian.protocol.ReplaceInteraction;
import meridian.protocol.SelectInteraction;
import meridian.protocol.SelectedHitEntity;
import meridian.protocol.Position;
import meridian.protocol.Direction;
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
 *   <li>{@code UseEntityInteraction} — fails unless the action is aimed at an entity whose
 *       own interactions hold a root for the chain's type; on success the walk enters that
 *       root and returns here when it completes.</li>
 *   <li>{@code SimpleBlockInteraction} (base of the block family) — fails when the
 *       target block is air / missing / out-of-chunk ({@code targetBlockType} is
 *       {@code null}); the whole family inherits this base check.</li>
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
    static final int MAX_STEPS = 256;
    /** Hytale's interaction tick rate — {@code progress} rises ~1/60 per packet. */
    static final float TICK_SECONDS = 1.0f / 60.0f;
    static final int MAX_TICKS = 1800;

    /** Resolves a root id to its flattened form — so the walk can follow a
     * {@code ReplaceInteraction} into the replacement root. */
    @FunctionalInterface
    interface RootResolver {
        /** The compiled replacement root, or {@code null} if unavailable. */
        CompiledInteraction resolve(int rootId);

        default double timeDilation() { return 1; }

        /** The live projectile snapshot; offline walks leave it empty. */
        default void projectile(meridian.protocol.ProjectileInteraction node, InteractionSyncData data, double chargeSeconds) { }

        /** The combo step a {@code ChainingInteraction} is to take - the first, unless told otherwise. */
        default int chainingIndex(ChainingInteraction node, int interactionId) {
            return 0;
        }
    }

    /** The combo step for a chaining op: the resolver's pick, kept within the op's branches. */
    static int chainingStep(FlatOp.Node node, RootResolver resolver) {
        int step = resolver == null ? 0
                : resolver.chainingIndex((ChainingInteraction) node.interaction(), node.interactionId());
        return step >= 0 && step < node.labels().length ? step : 0;
    }

    /** A saved walk position — where a {@code UseBlockInteraction} call returns to. */
    record Frame(CompiledInteraction compiled, int counter) {}

    private InteractionSimulator() {}

    /** Simulates {@code root} under {@code ctx} into the executed operations. */
    static List<InteractionSyncData> simulate(CompiledInteraction root, InteractionContext ctx,
                                              RootResolver resolver) {
        ctx = ctx.withEffects(ctx.effects().copy()); // this walk's own, to apply and clear on
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

            // ReplaceInteraction.tick0 runs context.execute(nextRoot) on success - the
            // server's pushRoot: a call. The replacement root runs from its operation 0, then
            // the chain returns here and goes on with the next operation.
            if (node.interaction() instanceof ReplaceInteraction replace
                    && state != InteractionState.Failed) {
                CompiledInteraction next = switchRoot(replace, compiled, ctx, resolver, visitedRoots);
                if (next != null) {
                    executed.get(executed.size() - 1).enteredRootInteraction = next.rootId();
                    returnStack.push(new Frame(compiled, counter + 1));
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

            // UseEntityInteraction runs the target entity's own root for this type the same way.
            if (node.interaction() instanceof UseEntityInteraction
                    && state != InteractionState.Failed) {
                CompiledInteraction entityRoot = enterEntityRoot(ctx, resolver, visitedRoots);
                if (entityRoot != null) {
                    executed.get(executed.size() - 1).enteredRootInteraction = entityRoot.rootId();
                    returnStack.push(new Frame(compiled, counter + 1));
                    compiled = entityRoot;
                    counter = 0;
                    continue;
                }
            }

            // ChainingInteraction takes the branch the client names - the combo's next step.
            if (node.interaction() instanceof ChainingInteraction
                    && state != InteractionState.Failed && node.labels().length > 0) {
                int combo = chainingStep(node, resolver);
                executed.get(executed.size() - 1).chainingIndex = combo;
                counter = node.labels()[combo].index;
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

            // DamageEntityInteraction jumps to the branch of how the hit went.
            if (node.interaction() instanceof DamageEntityInteraction && node.labels().length > 1) {
                int label = damageLabel(state);
                executed.get(executed.size() - 1).nextLabel = label;
                counter = node.labels()[label].index;
                continue;
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
        // The walk a tick at a time, played out with nothing from the server: a prediction.
        ChainWalk walk = new ChainWalk(root, ctx, resolver, selectHitBlock);
        List<Packet> packets = new ArrayList<>();
        for (Packet p = walk.tick(); p != null; p = walk.tick()) {
            packets.add(p);
        }
        return new TickedResult(packets, List.copyOf(walk.forks()));
    }

    /**
     * Ported per-tick outcome: {@code tickInternal}'s {@code runTime} gate, then
     * the node's {@code tick0} override.
     */
    static InteractionState tickState(Interaction node, InteractionContext ctx,
                                              float opTime) {
        // tickInternal: NotFinished until the elapsed time reaches the runTime.
        InteractionState timed = opTime < node.runTime
                ? InteractionState.NotFinished : InteractionState.Finished;
        if (node instanceof ChargingInteraction charging) {
            // The button is held until the charge reaches its tier, then let go: the server
            // measures the hold from its first sight of this operation.
            return opTime + HOLD_EPSILON < lowestChargeKey(charging)
                    ? InteractionState.NotFinished : InteractionState.Finished;
        }
        if (node instanceof UseEntityInteraction) {
            return usesEntity(ctx) ? InteractionState.Finished : InteractionState.Failed;
        }
        if (node instanceof DamageEntityInteraction) {
            return damages(ctx);
        }
        if (node instanceof EffectConditionInteraction condition) {
            return effectsMatch(condition, ctx) ? timed : InteractionState.Failed;
        }
        if (node instanceof StatsConditionInteraction condition) {
            return ctx.effects().stats().canAfford(condition) ? timed : InteractionState.Failed;
        }
        if (timed == InteractionState.Finished) {
            changeEffects(node, ctx);
        }
        if (node instanceof SimpleBlockInteraction && ctx.targetBlockType() == null) {
            // SimpleBlockInteraction.simulateTick0 base check: the target block is
            // air / missing / out-of-chunk (core resolves no BlockType). The whole
            // block family (UseBlock, BreakBlock, BlockCondition, ChangeBlock, …)
            // fails this before its own logic runs.
            return InteractionState.Failed;
        }
        if (node instanceof UseBlockInteraction) {
            return hasBlockInteraction(ctx.targetBlockType(), ctx.interactionType())
                    ? InteractionState.Finished : InteractionState.Failed;
        }
        if (node instanceof BlockConditionInteraction blockCondition) {
            return blockConditionMatches(blockCondition, ctx)
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
        if (node instanceof ChangeStateInteraction change) {
            return changesState(change, ctx) ? InteractionState.Finished : InteractionState.Failed;
        }
        return timed;
    }

    /**
     * Whether a {@code ChangeStateInteraction} has a state to move the target block to - the
     * server's {@code interactWithBlock}: the block's current state (its key among its family's
     * {@code states}, else {@code default}) must be a key of {@code stateChanges}, and the new
     * state one the block has. Anything else fails (a plain stone hit in Creative, for one).
     */
    private static boolean changesState(ChangeStateInteraction change, InteractionContext ctx) {
        BlockType type = ctx.targetBlockType();
        if (type == null || change.stateChanges == null || change.stateChanges.isEmpty()) {
            return false;
        }
        String current = WorldStateImpl.stateNameForBlock(type, ctx.targetBlockId());
        String next = change.stateChanges.get(current);
        return next != null && type.states != null && type.states.containsKey(next);
    }

    /** Whether {@code op} is a {@code ChargingInteraction} node. */
    static boolean isChargeOp(FlatOp op) {
        return op instanceof FlatOp.Node n && n.interaction() instanceof ChargingInteraction;
    }

    /** The lowest charge threshold of a {@code ChargingInteraction} — its first tier. */
    /** {@code chargeValue} while the button is still held - the server's {@code CHARGING_HELD}. */
    static final float CHARGING_HELD = -1f;
    /** Float slack when comparing the accumulated hold against a charge tier. */
    private static final float HOLD_EPSILON = 1e-4f;

    static float lowestChargeKey(ChargingInteraction charging) {
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
    static CompiledInteraction switchRoot(ReplaceInteraction node,
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
    static CompiledInteraction enterBlockRoot(InteractionContext ctx,
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
     * Whether a {@code UseEntityInteraction} goes through - its {@code firstRun}: the action is
     * aimed at an entity within reach, and that entity's {@code Interactions} hold a root for the
     * chain's type.
     */
    private static boolean usesEntity(InteractionContext ctx) {
        return ctx.entity() != null && ctx.entity().inReach()
                && ctx.entity().rootFor(ctx.interactionType()) != null;
    }

    /**
     * How a {@code DamageEntityInteraction} goes - its {@code tick0}: it fails without a target
     * entity or out of the player's reach, and otherwise lands. Which landing it is (plain, blocked, from an angle, on a body
     * part) the server works out from the fight itself; core walks the plain one.
     */
    private static InteractionState damages(InteractionContext ctx) {
        return ctx.entity() != null && ctx.entity().inReach()
                ? InteractionState.Finished : InteractionState.Failed;
    }

    /**
     * {@code EffectConditionInteraction.firstRun}: fails when the entity it looks at lacks one of
     * the effects ({@code All}) or has one ({@code None}). With no such entity it does not fail.
     */
    private static boolean effectsMatch(EffectConditionInteraction condition, InteractionContext ctx) {
        java.util.Set<Integer> active = ctx.effects().of(condition.entityTarget);
        if (active == null || condition.entityEffects == null) {
            return true;
        }
        for (int effect : condition.entityEffects) {
            boolean has = active.contains(effect);
            if (condition.match == meridian.protocol.Match.None ? has : !has) {
                return false;
            }
        }
        return true;
    }

    /**
     * What {@code ApplyEffectInteraction} / {@code ClearEntityEffectInteraction} do to the walk's
     * effects, and {@code ChangeStatInteraction} to its stats.
     */
    static void changeEffects(Interaction node, InteractionContext ctx) {
        if (node instanceof ApplyEffectInteraction apply) {
            java.util.Set<Integer> on = ctx.effects().of(apply.entityTarget);
            if (on != null) {
                on.add(apply.effectId);
            }
        } else if (node instanceof ClearEntityEffectInteraction clear) {
            java.util.Set<Integer> on = ctx.effects().of(clear.entityTarget);
            if (on != null) {
                on.remove(clear.effectId);
            }
        } else if (node instanceof ChangeStatInteraction change) {
            // The server's ChangeStatInteraction spends and gives on the acting entity's own stats.
            ctx.effects().stats().change(change);
        }
    }

    /** The branch a {@code DamageEntityInteraction} jumps to after {@code state}. */
    static int damageLabel(InteractionState state) {
        return state == InteractionState.Failed
                ? InteractionFlattener.DAMAGE_FAILED : InteractionFlattener.DAMAGE_SUCCESS;
    }

    /** The root a {@code UseEntityInteraction} runs: the target entity's own for this type. */
    static CompiledInteraction enterEntityRoot(InteractionContext ctx, RootResolver resolver,
                                                       Set<Integer> visitedRoots) {
        Integer rootId = ctx.entity() == null ? null : ctx.entity().rootFor(ctx.interactionType());
        return rootId == null ? null : enterRoot(rootId, resolver, visitedRoots);
    }

    /**
     * Resolves and compiles {@code rootId} for the walk to enter — the server's
     * {@code context.execute(root)} / {@code pushRoot}. Guards against
     * re-entering a root already on the walk.
     */
    static CompiledInteraction enterRoot(int rootId, RootResolver resolver,
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
            return usesEntity(ctx) ? InteractionState.Finished : InteractionState.Failed;
        }
        if (node instanceof DamageEntityInteraction) {
            return damages(ctx);
        }
        if (node instanceof EffectConditionInteraction condition) {
            return effectsMatch(condition, ctx) ? InteractionState.Finished : InteractionState.Failed;
        }
        if (node instanceof StatsConditionInteraction condition) {
            return ctx.effects().stats().canAfford(condition) ? InteractionState.Finished : InteractionState.Failed;
        }
        changeEffects(node, ctx);
        if (node instanceof SimpleBlockInteraction && ctx.targetBlockType() == null) {
            // SimpleBlockInteraction.simulateTick0 base check: the target block is
            // air / missing / out-of-chunk (core resolves no BlockType). The whole
            // block family (UseBlock, BreakBlock, BlockCondition, ChangeBlock, …)
            // fails this before its own logic runs.
            return InteractionState.Failed;
        }
        if (node instanceof UseBlockInteraction) {
            // doInteraction: fails unless the target block has an interaction
            // mapped for this interaction type.
            return hasBlockInteraction(ctx.targetBlockType(), ctx.interactionType())
                    ? InteractionState.Finished : InteractionState.Failed;
        }
        if (node instanceof BlockConditionInteraction blockCondition) {
            // doInteraction: the target must match one of the matchers.
            return blockConditionMatches(blockCondition, ctx)
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
        if (node instanceof ChangeStateInteraction change) {
            return changesState(change, ctx) ? InteractionState.Finished : InteractionState.Failed;
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
     * Ported {@code BlockConditionInteraction.doInteraction}: the target matches if
     * <em>any</em> matcher matches. The block id ({@code BlockType.item}), state
     * ({@code BlockType.states} reverse-lookup) and tags ({@code BlockType.tagIndexes})
     * are all reproducible from the tracked block type. The matcher <b>face</b> is
     * still assumed to pass — a dynamic face needs the block's per-position rotation,
     * which the proxy does not track yet. The result only rejects on a criterion it
     * can evaluate, never forging an unjustified {@code Failed}. The Creative
     * place-mode override is treated as the default (no override).
     */
    private static boolean blockConditionMatches(BlockConditionInteraction node, InteractionContext ctx) {
        BlockMatcher[] matchers = node.matchers;
        if (matchers == null || matchers.length == 0) {
            return true; // no matchers — nothing to reject on
        }
        BlockType blockType = ctx.targetBlockType();
        String itemId = blockType != null ? blockType.item : null;
        for (BlockMatcher matcher : matchers) {
            if (matcher == null) {
                continue;
            }
            BlockIdMatcher block = matcher.block;
            if (block != null) {
                // Block id — BlockIdMatcher.id vs the target's item id.
                if (block.id != null && !block.id.isEmpty()
                        && (itemId == null || !block.id.equals(itemId))) {
                    continue;
                }
                // Block state — the matcher's state vs the target's current state name.
                if (block.state != null && !block.state.isEmpty()
                        && !block.state.equals(
                                WorldStateImpl.stateNameForBlock(blockType, ctx.targetBlockId()))) {
                    continue;
                }
                // Block tag — the matcher's tag index must be among the target's tags.
                if (block.tagIndex != Integer.MIN_VALUE && !hasTag(blockType, block.tagIndex)) {
                    continue;
                }
            }
            // matcher.face / staticFace still assumed to pass (needs per-position rotation).
            return true;
        }
        return false; // every matcher failed a criterion the proxy could evaluate
    }

    /** Whether {@code tagIndex} is among the block type's expanded tag indexes. */
    private static boolean hasTag(BlockType blockType, int tagIndex) {
        if (blockType == null || blockType.tagIndexes == null) {
            return false;
        }
        for (int t : blockType.tagIndexes) {
            if (t == tagIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ported {@code ConditionInteraction.tick0}: {@code requiredGameMode} must equal
     * the player's observed game mode, and each set movement flag must equal the
     * matching {@code MovementStates} field. Either input being unobserved
     * ({@code null}) skips its own check — conservative, matching the server's
     * {@code success = true} default — but the two are independent, so a game-mode
     * mismatch fails even with no movement snapshot.
     */
    private static boolean conditionHolds(ConditionInteraction c, InteractionContext ctx) {
        GameMode mode = ctx.gameMode();
        if (c.requiredGameMode != null && mode != null && c.requiredGameMode != mode) {
            return false;
        }
        MovementStates m = ctx.movementStates();
        if (m == null) {
            // No movement reported yet (a headless client that has only acknowledged its
            // teleport): standing still - not crouching, not jumping, not running. Letting every
            // movement condition pass took the crouch branch of a watering can.
            m = new MovementStates();
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
    static InteractionSyncData syncDataFor(FlatOp.Node node, int counter, int rootId,
                                                   InteractionState state, InteractionContext ctx) {
        InteractionSyncData data = new InteractionSyncData();
        data.operationCounter = counter;
        data.rootInteraction = rootId;
        data.state = state;

        if (node.interaction() instanceof ChainingInteraction) {
            // The server reads this to pick the branch; the walk sets the combo step it took.
            data.chainingIndex = 0;
        }

        EntityTarget entity = ctx.entity();
        if (entity != null && node.interaction() instanceof SelectInteraction) {
            // A player's SelectInteraction takes its entity hits from the client
            // (ClientSourcedSelector): the entity it hit, and where both stood - the damage that
            // forks off reads its angles and knockback from these.
            data.hitEntities = new SelectedHitEntity[] {entity.hit()};
            data.attackerPos = new Position(entity.attackerPos().x, entity.attackerPos().y, entity.attackerPos().z);
            data.attackerRot = new Direction(entity.attackerRot().yaw, entity.attackerRot().pitch, entity.attackerRot().roll);
        } else if (entity != null && node.interaction() instanceof UseEntityInteraction) {
            // UseEntityInteraction finds its target by the id the client names.
            data.entityId = entity.networkId();
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
            // Some blocks (logs / beams) orient by blockRotation, not blockFace;
            // the context carries the rotation learned from the player's own
            // placement for this face, or null → a default (no-rotation) one.
            data.blockRotation = ctx.blockRotation() != null
                    ? ctx.blockRotation().clone() : new BlockRotation();
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
