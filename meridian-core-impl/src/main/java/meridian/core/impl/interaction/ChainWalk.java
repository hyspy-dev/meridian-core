package meridian.core.impl.interaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import meridian.protocol.ChainingInteraction;
import meridian.protocol.ChargingInteraction;
import meridian.protocol.DamageEntityInteraction;
import meridian.protocol.DragPlaceBlockInteraction;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.ParallelInteraction;
import meridian.protocol.PlaceModeSelectInteraction;
import meridian.protocol.ReplaceInteraction;
import meridian.protocol.SelectInteraction;
import meridian.protocol.SimpleInteraction;
import meridian.protocol.UseBlockInteraction;
import meridian.protocol.UseEntityInteraction;
import meridian.protocol.WaitForDataFrom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One chain walked a tick at a time, the way the client runs it - and corrected the way the client
 * corrects it.
 *
 * <p>Each {@link #tick()} plays one 60&nbsp;Hz tick: the operations that resolve in it, plus the one
 * still running, which is the packet the client sends that tick. The walk is the port of the
 * server's interaction loop ({@link InteractionSimulator}'s node logic), so on its own it predicts
 * every operation.
 *
 * <p>The server has the last word, and the walk takes it ({@link #verdict}):
 * <ul>
 *   <li>An operation that waits for the server ({@code waitForDataFrom = Server}) stays running
 *       until the server's outcome for it comes in. Then it ends as the server says.</li>
 *   <li>The server's outcome for an operation the walk has not resolved yet is used when the walk
 *       gets there, instead of a guess.</li>
 *   <li>An operation the walk resolved differently (another state, another branch, another root
 *       entered) is a misprediction. {@link #rewind} goes back to where the walk first reached it,
 *       and from there the walk runs on with the server's outcome. This is the client's
 *       revert-and-replay; the caller resends from that operation.</li>
 * </ul>
 * The server's outcome counts only where its path is the walk's: the same operation of the same
 * root at that operation index.
 *
 * <p>Not thread-safe; its {@link ForgeRun} owns it.
 */
final class ChainWalk {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** Where the walk stood when it first reached an operation - what a rewind returns to. */
    private record Snapshot(CompiledInteraction compiled, int counter, List<InteractionSimulator.Frame> stack,
                            Set<Integer> visited, WalkEffects effects, int forks) {}

    private final InteractionSimulator.RootResolver resolver;
    private final Map<Integer, Integer> selectHitBlock;
    private InteractionContext ctx;
    private CompiledInteraction compiled;
    private Set<Integer> visitedRoots = new HashSet<>();
    private Deque<InteractionSimulator.Frame> returnStack = new ArrayDeque<>();
    private int counter;
    /** The operation index: rises by one per operation run, across every root the walk enters. */
    private int flatIndex;
    private float opTime;
    private final List<InteractionSimulator.Fork> forks = new ArrayList<>();
    private boolean done;
    private int ticks;
    private boolean awaiting;
    private InteractionState lastState = InteractionState.Finished;
    /**
     * How long the button is held, in seconds from the chain's start; {@code NaN} for the default
     * hold - each charge just long enough for its first tier.
     */
    private float hold = Float.NaN;

    private final Map<Integer, Snapshot> snapshots = new HashMap<>();
    /** What each resolved operation was sent as, by operation index. */
    private final Map<Integer, InteractionSyncData> resolved = new HashMap<>();
    /** The server's data for each operation, by operation index - the latest it sent. */
    private final Map<Integer, InteractionSyncData> verdicts = new HashMap<>();

    ChainWalk(CompiledInteraction root, InteractionContext ctx, InteractionSimulator.RootResolver resolver,
              Map<Integer, Integer> selectHitBlock) {
        this.resolver = resolver;
        this.selectHitBlock = selectHitBlock;
        this.ctx = ctx.withEffects(ctx.effects().copy()); // this walk's own, to apply and clear on
        this.compiled = root;
        visitedRoots.add(root.rootId());
    }

    /**
     * Holds the button {@code seconds} from the chain's start, then lets go - a bow's draw, a
     * charged swing. A charge reached while it is held runs until the hold ends and takes the tier
     * its time reaches; one reached after is let go at once. A tap is a hold of 0.
     */
    ChainWalk hold(float seconds) {
        this.hold = seconds;
        return this;
    }

    /** Whether the button is still down at {@code now}, seconds into the chain. */
    private boolean held(float now) {
        return Float.isNaN(hold) || now + InteractionSimulator.TICK_SECONDS / 2 < hold;
    }

    /** {@code true} once the walk has run off the end of its root. */
    boolean done() {
        return done;
    }

    /** {@code true} while the operation the walk stands on waits for the server's outcome. */
    boolean awaiting() {
        return awaiting;
    }

    /** The operation index the walk stands on. */
    int position() {
        return flatIndex;
    }

    /** The state the last resolved operation ended in. */
    InteractionState lastState() {
        return lastState;
    }

    /** The forks the walk has spawned so far, in order. */
    List<InteractionSimulator.Fork> forks() {
        return forks;
    }

    /** What the walk resolved operation {@code index} to, or {@code null} while it has not. */
    InteractionSyncData resolved(int index) {
        return resolved.get(index);
    }

    /** The server's data for operation {@code index}, kept for when the walk reaches it. */
    void verdict(int index, InteractionSyncData server) {
        verdicts.put(index, server);
    }

    /**
     * Goes back to where the walk first reached operation {@code index}. Everything after is
     * forgotten - resolved operations, effects and stats spent, forks spawned - and the walk runs
     * on from there. {@code false} when the walk never reached it.
     */
    boolean rewind(int index) {
        Snapshot s = snapshots.get(index);
        if (s == null) {
            return false;
        }
        compiled = s.compiled();
        counter = s.counter();
        returnStack = new ArrayDeque<>(s.stack());
        visitedRoots = new HashSet<>(s.visited());
        ctx = ctx.withEffects(s.effects().copy());
        while (forks.size() > s.forks()) {
            forks.remove(forks.size() - 1);
        }
        flatIndex = index;
        opTime = 0.0f;
        done = false;
        awaiting = false;
        snapshots.keySet().removeIf(k -> k > index);
        resolved.keySet().removeIf(k -> k >= index);
        return true;
    }

    /**
     * Goes back to operation {@code index} and moves to the operation the server ran there:
     * {@code counter} of {@code rootId}. Possible only within the root the walk was in at that
     * point; {@code false} otherwise.
     */
    boolean rewindTo(int index, int serverCounter, int rootId) {
        Snapshot s = snapshots.get(index);
        if (s == null || s.compiled().rootId() != rootId || s.compiled().op(serverCounter) == null) {
            return false;
        }
        rewind(index);
        counter = serverCounter;
        return true;
    }

    /**
     * Plays one tick: the packet's operations, starting at {@link InteractionSimulator.Packet#baseIndex}.
     * {@code null} once the walk is done and there is nothing more to send.
     */
    InteractionSimulator.Packet tick() {
        if (done) {
            return null;
        }
        if (++ticks > InteractionSimulator.MAX_TICKS) {
            log.warn("meridian-core: ticked sim hit the tick cap (root {})", compiled.rootId());
            done = true;
            return null;
        }
        awaiting = false;
        float now = (ticks - 1) * InteractionSimulator.TICK_SECONDS; // seconds since the press
        List<InteractionSyncData> ops = new ArrayList<>();
        int base = flatIndex;
        for (int step = 0; step < InteractionSimulator.MAX_STEPS; step++) {
            FlatOp op = compiled.op(counter);
            if (op == null) {
                if (returnStack.isEmpty()) {
                    done = true; // walked off the end - chain complete
                    return ops.isEmpty() ? null : new InteractionSimulator.Packet(base, ops);
                }
                InteractionSimulator.Frame frame = returnStack.pop(); // return from a call
                compiled = frame.compiled();
                counter = frame.counter();
                continue;
            }
            snapshots.computeIfAbsent(flatIndex, i -> snapshot());
            if (op instanceof FlatOp.Jump jump) {
                // A jump the walk lands on (by fall-through) is an executed operation - the server
                // records it; jumps a branch skips over are not reached and not recorded.
                InteractionSyncData jd = new InteractionSyncData();
                jd.operationCounter = counter;
                jd.rootInteraction = compiled.rootId();
                jd.state = InteractionState.Finished;
                jd.progress = 0.0f;
                ops.add(jd);
                resolved.put(flatIndex, jd);
                flatIndex++;
                counter = jump.target().index;
                if (counter == OpLabel.UNRESOLVED) {
                    log.warn("meridian-core: ticked sim hit an unresolved jump (root {})", compiled.rootId());
                    done = true;
                    return new InteractionSimulator.Packet(base, ops);
                }
                continue;
            }
            FlatOp.Node node = (FlatOp.Node) op;
            InteractionSyncData verdict = verdictFor(flatIndex);
            InteractionState state;
            if (verdict != null) {
                // The server ran it already: its outcome, not a guess.
                state = verdict.state;
                if (state == InteractionState.Finished) {
                    InteractionSimulator.changeEffects(node.interaction(), ctx);
                }
            } else if (node.interaction().waitForDataFrom == WaitForDataFrom.Server) {
                // Decided by the server alone: the client stands on it until the server says how it went.
                state = InteractionState.NotFinished;
                awaiting = true;
            } else if (node.interaction() instanceof ChargingInteraction && !Float.isNaN(hold)) {
                // Held for as long as the button is down; let go when it comes up.
                state = held(now) ? InteractionState.NotFinished : InteractionState.Finished;
            } else {
                state = InteractionSimulator.tickState(node.interaction(), ctx, opTime);
                // Tap/hold gate: a plain SimpleInteraction whose failed branch enters a
                // ChargingInteraction. Its Finished branch is the inert "tap"; the button still
                // down when it ends takes the hold branch - by default a forged action holds.
                if (state == InteractionState.Finished
                        && node.interaction().getClass() == SimpleInteraction.class
                        && node.labels().length > 0
                        && InteractionSimulator.isChargeOp(compiled.op(node.labels()[0].index))
                        && held(now)) {
                    state = InteractionState.Failed;
                }
            }
            InteractionSyncData sd = InteractionSimulator.syncDataFor(node, counter, compiled.rootId(), state, ctx);
            sd.progress = opTime;
            if (node.interaction() instanceof ChargingInteraction charging) {
                // Held: the client says so every tick (CHARGING_HELD), and on release how long
                // it was held - which the server checks against its own clock.
                sd.chargeValue = state == InteractionState.NotFinished ? InteractionSimulator.CHARGING_HELD
                        : Float.isNaN(hold) ? InteractionSimulator.lowestChargeKey(charging) : opTime;
            }
            ops.add(sd);

            if (state == InteractionState.NotFinished) {
                // Still running - the packet ends here this tick; the running operation keeps
                // its index for the next.
                opTime += InteractionSimulator.TICK_SECONDS;
                return new InteractionSimulator.Packet(base, ops);
            }
            resolved.put(flatIndex, sd);
            lastState = state;

            // Operation resolved this tick.
            if (node.interaction() instanceof ReplaceInteraction replace && state != InteractionState.Failed) {
                CompiledInteraction next = entered(verdict);
                if (next == null) {
                    next = InteractionSimulator.switchRoot(replace, compiled, ctx, resolver, visitedRoots);
                }
                if (next == null) {
                    done = true;
                    return new InteractionSimulator.Packet(base, ops);
                }
                // The server's pushRoot: a call into the replacement root, returning to the
                // operation after this one when it is done. The op says which root it entered
                // (context.execute sets enteredRootInteraction).
                sd.enteredRootInteraction = next.rootId();
                call(next);
            } else if (node.interaction() instanceof UseBlockInteraction && state != InteractionState.Failed) {
                // UseBlockInteraction.doInteraction enters the target block's own interaction
                // root, then control returns here to finish the parent chain.
                CompiledInteraction blockRoot = entered(verdict);
                if (blockRoot == null) {
                    blockRoot = InteractionSimulator.enterBlockRoot(ctx, resolver, visitedRoots);
                }
                if (blockRoot != null) {
                    sd.enteredRootInteraction = blockRoot.rootId();
                    call(blockRoot);
                } else {
                    counter++;
                }
            } else if (node.interaction() instanceof UseEntityInteraction && state != InteractionState.Failed) {
                // UseEntityInteraction.firstRun runs the target entity's own root for this type -
                // context.execute, a call that returns here.
                CompiledInteraction entityRoot = entered(verdict);
                if (entityRoot == null) {
                    entityRoot = InteractionSimulator.enterEntityRoot(ctx, resolver, visitedRoots);
                }
                if (entityRoot != null) {
                    sd.enteredRootInteraction = entityRoot.rootId();
                    call(entityRoot);
                } else {
                    counter++;
                }
            } else if (node.interaction() instanceof ChainingInteraction
                    && state != InteractionState.Failed && node.labels().length > 0) {
                // ChainingInteraction takes the branch the client names - the combo's next step,
                // as the server counts it.
                int combo = InteractionSimulator.chainingStep(node, resolver);
                sd.chainingIndex = combo;
                counter = node.labels()[combo].index;
            } else if (node.interaction() instanceof ParallelInteraction parallel
                    && state != InteractionState.Failed
                    && parallel.next != null && parallel.next.length > 0) {
                // interactions[0] runs on the main chain (call/return); interactions[1..] spawn
                // parallel fork chains - recorded here, flatIndex being this op's index (the fork
                // entryIndex).
                for (int f = 1; f < parallel.next.length; f++) {
                    forks.add(new InteractionSimulator.Fork(flatIndex, f - 1, parallel.next[f], null));
                }
                CompiledInteraction sub = InteractionSimulator.enterRoot(parallel.next[0], resolver, visitedRoots);
                if (sub != null) {
                    call(sub);
                } else {
                    counter++;
                }
            } else if (node.interaction() instanceof SelectInteraction && state != InteractionState.Failed) {
                // SelectInteraction picks blocks in an area and spawns a fork running its HitBlock
                // interaction per block. Without a learned HitBlock root (selectHitBlock) the
                // server spawns them itself, and they are answered as announced.
                Integer hitBlock = selectHitBlock.get(node.interactionId());
                if (hitBlock != null && hitBlock > 0) {
                    var targets = ctx.hitBlocks();
                    if (targets == null || targets.isEmpty()) {
                        if (ctx.targetBlock() != null) {
                            forks.add(new InteractionSimulator.Fork(flatIndex, 0, hitBlock, ctx.targetBlock()));
                        }
                    } else {
                        int sub = 0;
                        for (var b : targets) {
                            forks.add(new InteractionSimulator.Fork(flatIndex, sub++, hitBlock, b));
                        }
                    }
                }
                counter++;
            } else if (node.interaction() instanceof ChargingInteraction charging
                    && state == InteractionState.Finished && node.labels().length > 0
                    && charging.chargedNext != null && !charging.chargedNext.isEmpty()) {
                // ChargingInteraction.jumpToChargeValue: a finished charge routes to the tier of
                // the highest key it reached. Short of the lowest there is no jump, nor without
                // tiers (a spear's block, a WieldingInteraction): the walk goes on.
                int tier = tierOf(charging, sd.chargeValue);
                if (tier >= 0 && tier < node.labels().length) {
                    counter = node.labels()[tier].index;
                } else {
                    counter++;
                }
            } else if (node.interaction() instanceof DragPlaceBlockInteraction drag
                    && state != InteractionState.Failed) {
                // A placement gesture (0.8): the client commits blocks and predicts one fork of
                // forkInteractions per block, each placing its own. One click is a gesture of one
                // block - forkCounts says so, and the fork is ours to send.
                sd.forkCounts = Map.of(ctx.interactionType(), 1);
                if (drag.forkInteractions != Integer.MIN_VALUE) {
                    forks.add(new InteractionSimulator.Fork(flatIndex, 0, drag.forkInteractions, null));
                }
                counter++;
            } else if (node.interaction() instanceof DamageEntityInteraction && node.labels().length > 1) {
                // Jumps to the branch of how the hit went, and says which (nextLabel). Which one it
                // is, the server works out from the fight; its word wins.
                int label = verdict != null && verdict.nextLabel >= 0 && verdict.nextLabel < node.labels().length
                        ? verdict.nextLabel : InteractionSimulator.damageLabel(state);
                sd.nextLabel = label;
                counter = node.labels()[label].index;
            } else if (node.interaction() instanceof PlaceModeSelectInteraction && node.labels().length > 0) {
                // Jumps to its place mode's branch and says which (nextLabel): Default (label 0)
                // unless the server picked another - no mode is set here, and outside Creative the
                // server always takes Default.
                int label = verdict != null && verdict.nextLabel >= 0 && verdict.nextLabel < node.labels().length
                        ? verdict.nextLabel : 0;
                sd.nextLabel = label;
                counter = node.labels()[label].index;
            } else if (state == InteractionState.Failed && node.labels().length > 0) {
                counter = node.labels()[0].index; // failed branch
            } else {
                counter++;
            }
            opTime = 0.0f;
            flatIndex++;
        }
        log.warn("meridian-core: ticked sim hit the step cap (root {})", compiled.rootId());
        done = true;
        return ops.isEmpty() ? null : new InteractionSimulator.Packet(base, ops);
    }

    /**
     * The tier a charge of {@code value} seconds takes - the server's {@code jumpToChargeValue}: the
     * index, among the keys sorted up, of the highest one not above it; -1 below them all.
     */
    private static int tierOf(ChargingInteraction charging, float value) {
        List<Float> keys = charging.chargedNext.keySet().stream().sorted().toList();
        int tier = -1;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) != null && value >= keys.get(i)) {
                tier = i;
            }
        }
        return tier;
    }

    /** The server's final outcome for the operation at {@code index}, where its path is the walk's. */
    private InteractionSyncData verdictFor(int index) {
        InteractionSyncData v = verdicts.get(index);
        if (v == null || v.state == InteractionState.NotFinished
                || v.operationCounter != counter || v.rootInteraction != compiled.rootId()) {
            return null;
        }
        return v;
    }

    /** The root the server entered at this operation, when it said so; {@code null} otherwise. */
    private CompiledInteraction entered(InteractionSyncData verdict) {
        if (verdict == null || verdict.enteredRootInteraction == Integer.MIN_VALUE
                || verdict.enteredRootInteraction < 0) {
            return null;
        }
        return InteractionSimulator.enterRoot(verdict.enteredRootInteraction, resolver, visitedRoots);
    }

    /** A call into {@code root}, returning to the operation after this one. */
    private void call(CompiledInteraction root) {
        returnStack.push(new InteractionSimulator.Frame(compiled, counter + 1));
        compiled = root;
        counter = 0;
    }

    private Snapshot snapshot() {
        return new Snapshot(compiled, counter, new ArrayList<>(returnStack), new HashSet<>(visitedRoots),
                ctx.effects().copy(), forks.size());
    }
}
