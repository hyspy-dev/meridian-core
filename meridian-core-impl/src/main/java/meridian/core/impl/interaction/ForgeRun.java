package meridian.core.impl.interaction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import meridian.api.module.Scheduler;
import meridian.core.api.InteractionOutcome;
import meridian.protocol.ForkedChainId;
import meridian.protocol.InteractionChainData;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InteractionType;
import meridian.protocol.packets.interaction.SyncInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChains;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One forged chain, played live against the server the way the client plays it: a packet a tick,
 * with what the server says coming back in between.
 *
 * <p>The chain and each fork it has are a {@link Lane}: a {@link ChainWalk} and what the server
 * said about it. The server echoes every chain the player runs, operation by operation: the
 * path it took (which operation of which root at each operation index) and how each ended. A
 * lane feeds that to its walk:
 * <ul>
 *   <li>what the walk has not reached yet, it takes when it gets there, instead of a guess - and an
 *       operation that waits for the server ({@code waitForDataFrom = Server}) stands until it
 *       comes;</li>
 *   <li>where the server's path parts from the walk's - an operation index the two ran different
 *       operations at, or one only the server ran - the walk mispredicted an operation before it.
 *       The lane goes back to the first operation the server ran otherwise, replays from there the
 *       server's way, and sends the replay flagged {@code desync}. That is the client's
 *       revert-and-replay (the server's {@code InteractionManager} describes it). An outcome that
 *       differs while the path does not is left alone, as the client leaves it: the server takes
 *       its own, and nothing after it depends on ours.</li>
 * </ul>
 *
 * <p>The run is done locally once every lane of its own has sent its last packet - the forge queue
 * moves on then, as the client starts its next chain. It is settled once the server said how the
 * chain and its forks ended, or cancelled them, or kept silent too long: then {@link #outcome()}
 * completes.
 *
 * <p>Ticks run on core's scheduler, the server's words on the network thread; the run is
 * guarded by its own monitor.
 */
final class ForgeRun {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** The 60&nbsp;Hz interaction tick. */
    static final long TICK_NANOS = 1_000_000_000L / 60L;
    /** How long the server has, once the chain is sent, to say how it ended. */
    private static final long SETTLE_NANOS = 5_000_000_000L;
    /** How long a run may go at all. */
    private static final long MAX_NANOS = 60_000_000_000L;
    /** How often one operation index may be replayed before the lane gives up on it. */
    private static final int MAX_REPLAYS_AT = 2;

    /** What a run needs from the control it belongs to. */
    interface Host {
        /** Sends one tick's packet to the server. */
        void send(SyncInteractionChains packet);

        /** Wraps the sync of a server-made fork into the parent chain it rides in. */
        SyncInteractionChain wrap(int chainId, SyncInteractionChain fork, InteractionType type);

        /** A root, flattened; {@code null} when the registry lacks it. */
        CompiledInteraction compile(int rootId);

        InteractionSimulator.RootResolver resolver();

        Scheduler scheduler();

        /** The run is settled and done; it may be forgotten. */
        void finished(ForgeRun run);
    }

    /** The held-item state the chain reports, as it was when the chain started. */
    record Header(int hotbarSlot, int utilitySlot, int toolsSlot, String item, String utilityItem,
                  String toolsItem) {}

    /** A chain of the run - the run's own, or one of its forks - and what the server said of it. */
    final class Lane {
        final ForkedChainId forkedId;
        final String key;
        final InteractionType type;
        final ChainWalk walk;
        /** The chain data each packet carries. */
        final InteractionChainData data;
        /** A reply to a fork the server made: it rides in its parent chain's {@code newForks}. */
        final boolean wrapped;
        /** The run tick it starts sending on. */
        final int startTick;
        /** The server's data by operation index - the latest it sent. */
        final TreeMap<Integer, InteractionSyncData> server = new TreeMap<>();
        final Map<Integer, Integer> replays = new java.util.HashMap<>();
        InteractionState serverState = InteractionState.NotFinished;
        boolean initialSent;
        /** The walk is done and its last packet is out. */
        boolean sentAll;
        boolean cancelled;
        /** No longer played: given up on, or forked on a path the walk replayed away from. */
        boolean abandoned;
        /** The next packet is a replay. */
        boolean desync;
        /** Walk forks already made lanes (the main lane only). */
        int forksKnown;
        /** Which of the main walk's forks this lane plays; -1 for the main lane and server forks. */
        int forkIndex = -1;

        Lane(ForkedChainId forkedId, InteractionType type, ChainWalk walk, InteractionChainData data,
             boolean wrapped, int startTick) {
            this.forkedId = forkedId;
            this.key = path(forkedId);
            this.type = type;
            this.walk = walk;
            this.data = data;
            this.wrapped = wrapped;
            this.startTick = startTick;
        }

        boolean playing() {
            return live() && !sentAll;
        }

        /** Neither given up on nor cancelled: what the server says of it still counts. */
        boolean live() {
            return !abandoned && !cancelled;
        }

        boolean settled() {
            return abandoned || cancelled || (sentAll && serverState != InteractionState.NotFinished);
        }
    }

    private final Host host;
    private final int chainId;
    private final Header header;
    /** How a fork the walk spawns is set up: its context, from the chain's. */
    private final InteractionContext ctx;
    private final Map<Integer, Integer> selectHitBlock;
    private final Map<String, Lane> lanes = new LinkedHashMap<>();
    private final Lane main;
    private final CompletableFuture<Void> localDone = new CompletableFuture<>();
    private final CompletableFuture<InteractionOutcome> outcome = new CompletableFuture<>();
    private int ticks;
    private long baseNanos;
    private long startNanos;
    private long localDoneNanos;
    private int corrections;
    private boolean looping;
    /** The outcome is decided (its future completes a moment later, on the scheduler). */
    private boolean settled;

    ForgeRun(Host host, int chainId, Header header, InteractionType type, ChainWalk walk,
             InteractionChainData data, InteractionContext ctx, Map<Integer, Integer> selectHitBlock) {
        this.host = host;
        this.chainId = chainId;
        this.header = header;
        this.ctx = ctx;
        this.selectHitBlock = selectHitBlock;
        this.main = new Lane(null, type, walk, data, false, 0);
        lanes.put(main.key, main);
    }

    /**
     * A run that plays nothing - a chain sent some other way (a captured chain replayed) - and only
     * hears how the server ran it. Done locally once {@link #replayedOver} says so.
     */
    static ForgeRun passive(Host host, int chainId, InteractionType type) {
        InteractionChainData none = new InteractionChainData();
        return new ForgeRun(host, chainId, new Header(0, 0, 0, null, null, null), type, null, none,
                null, Map.of());
    }

    /** A passive run's chain goes out over {@code span}: done locally then, and heard from now on. */
    void replayedOver(Duration span) {
        start();
        try {
            host.scheduler().schedule(() -> {
                synchronized (this) {
                    main.sentAll = true;
                    finishLocally();
                    settle(System.nanoTime());
                }
            }, span);
        } catch (RejectedExecutionException shutDown) {
            synchronized (this) {
                main.sentAll = true;
                finishLocally();
                complete(InteractionOutcome.Status.TIMED_OUT);
            }
        }
    }

    int chainId() {
        return chainId;
    }

    /** Completes once every lane of the run's own has sent its last packet. */
    CompletableFuture<Void> localDone() {
        return localDone;
    }

    /** Completes once the server has said how the chain ended (or cancelled it, or kept silent). */
    CompletableFuture<InteractionOutcome> outcome() {
        return outcome;
    }

    /** Sends the first tick now and the rest at 60&nbsp;Hz. */
    void start() {
        synchronized (this) {
            startNanos = System.nanoTime();
            baseNanos = startNanos;
            looping = true;
        }
        loop();
    }

    // ------------------------------------------------------------------
    // What the server says
    // ------------------------------------------------------------------

    /** The server's echo of a chain of this run: its path and states, its chain state. */
    void onServer(SyncInteractionChain s) {
        boolean resume;
        synchronized (this) {
            Lane lane = lanes.get(path(s.forkedId));
            if (lane == null) {
                return;
            }
            if (s.interactionData != null) {
                for (int i = 0; i < s.interactionData.length; i++) {
                    InteractionSyncData d = s.interactionData[i];
                    if (d != null) {
                        lane.server.put(s.operationBaseIndex + i, d);
                        lane.walk.verdict(s.operationBaseIndex + i, d);
                    }
                }
            }
            if (s.state != InteractionState.NotFinished) {
                lane.serverState = s.state;
            }
            check(lane);
            settle(System.nanoTime());
            resume = needsLoop();
        }
        if (resume) {
            ensureLooping();
        }
    }

    /** The server cancelled one chain of this run. */
    void onCancel(ForkedChainId forkedId) {
        synchronized (this) {
            Lane lane = lanes.get(path(forkedId));
            if (lane == null) {
                return;
            }
            lane.cancelled = true;
            if (lane == main) {
                // A cancelled chain takes its forks with it.
                lanes.values().forEach(l -> l.cancelled = true);
            }
            log.info("meridian-core: server cancelled forged chain {} {}", chainId,
                    lane == main ? "" : "fork " + lane.key);
            settle(System.nanoTime());
        }
    }

    /**
     * Answers a fork the server made of this chain (a hit's, an area dig's): played as one more
     * lane, wrapped in its parent. {@code false} when it is answered already.
     */
    boolean answerFork(ForkedChainId forkedId, InteractionType type, ChainWalk walk, InteractionChainData data) {
        boolean resume;
        synchronized (this) {
            String key = path(forkedId);
            if (lanes.containsKey(key)) {
                return false;
            }
            lanes.put(key, new Lane(forkedId, type, walk, data, true, ticks));
            resume = needsLoop();
        }
        if (resume) {
            ensureLooping();
        }
        return true;
    }

    /** {@code true} when the server already knows this fork as ours to answer. */
    synchronized boolean hasLane(ForkedChainId forkedId) {
        return lanes.containsKey(path(forkedId));
    }

    // ------------------------------------------------------------------
    // Playing
    // ------------------------------------------------------------------

    private void loop() {
        long delay;
        synchronized (this) {
            try {
                tick();
            } catch (RuntimeException ex) {
                log.warn("meridian-core: forged chain {} failed to play", chainId, ex);
                lanes.values().forEach(l -> l.abandoned = true);
                finishLocally();
                complete(InteractionOutcome.Status.TIMED_OUT);
            }
            if (!needsLoop()) {
                looping = false;
                return;
            }
            delay = Math.max(0L, baseNanos + ticks * TICK_NANOS - System.nanoTime());
        }
        try {
            host.scheduler().schedule(this::loop, Duration.ofNanos(delay));
        } catch (RejectedExecutionException shutDown) {
            synchronized (this) {
                // The module was torn down mid-chain.
                looping = false;
                finishLocally();
                complete(InteractionOutcome.Status.TIMED_OUT);
            }
        }
    }

    private void ensureLooping() {
        synchronized (this) {
            if (looping) {
                return;
            }
            looping = true;
            baseNanos = System.nanoTime() - ticks * TICK_NANOS; // a fresh clock: no burst to catch up
        }
        loop();
    }

    /** Whether there is anything to send, or to wait for. */
    private boolean needsLoop() {
        if (!settled) {
            return true;
        }
        for (Lane lane : lanes.values()) {
            if (lane.playing()) {
                return true;
            }
        }
        return false;
    }

    /** One tick: each playing lane's packet, sent as one. */
    private void tick() {
        List<SyncInteractionChain> out = new ArrayList<>();
        for (Lane lane : List.copyOf(lanes.values())) {
            if (!lane.playing() || ticks < lane.startTick || lane.walk == null) {
                continue;
            }
            InteractionSimulator.Packet p = lane.walk.tick();
            if (p == null) {
                lane.sentAll = true;
                continue;
            }
            boolean last = lane.walk.done();
            SyncInteractionChain c = chain(lane, lane == main && !lane.initialSent,
                    last ? InteractionState.Finished : InteractionState.NotFinished,
                    p.baseIndex(), p.ops().toArray(new InteractionSyncData[0]));
            if (lane.desync) {
                c.desync = true;
                lane.desync = false;
            }
            lane.initialSent = true;
            if (lane == main) {
                announceForks(c);
            }
            lane.sentAll = last;
            out.add(lane.wrapped ? host.wrap(chainId, c, lane.type) : c);
            // The server may have spoken already of what the walk just ran.
            check(lane);
        }
        if (!out.isEmpty()) {
            SyncInteractionChains packet = new SyncInteractionChains();
            packet.updates = out.toArray(new SyncInteractionChain[0]);
            host.send(packet);
        }
        ticks++;
        long now = System.nanoTime();
        if (!localDone.isDone() && ownLanesSent()) {
            finishLocally();
        }
        settle(now);
    }

    /** Lanes the run plays of its own accord - not the answers to server forks - have all been sent. */
    private boolean ownLanesSent() {
        for (Lane lane : lanes.values()) {
            if (!lane.wrapped && lane.playing()) {
                return false;
            }
        }
        return true;
    }

    private void finishLocally() {
        if (!localDone.isDone()) {
            localDoneNanos = System.nanoTime();
            localDone.complete(null);
        }
    }

    /**
     * Forks the main walk spawned this tick: announced on its packet ({@code newForks}), each played
     * from the next tick on as a lane of its own.
     */
    private void announceForks(SyncInteractionChain mainChain) {
        if (main.walk == null) {
            return;
        }
        List<InteractionSimulator.Fork> forks = main.walk.forks();
        if (main.forksKnown >= forks.size()) {
            return;
        }
        List<SyncInteractionChain> announce = new ArrayList<>();
        for (int i = main.forksKnown; i < forks.size(); i++) {
            InteractionSimulator.Fork f = forks.get(i);
            CompiledInteraction root = host.compile(f.rootId());
            if (root == null) {
                log.warn("meridian-core: fork root {} not in registry - skipped", f.rootId());
                continue;
            }
            // A fork breaking a block of its own is aimed at that block.
            InteractionContext forkCtx = f.block() != null ? ctx.withTarget(f.block()) : ctx;
            ForkedChainId id = new ForkedChainId(f.entryIndex(), f.subIndex(), null);
            InteractionChainData data = main.data.clone();
            data.targetSlot = 0; // forks report targetSlot 0, as the client does
            if (f.block() != null) {
                data.blockPosition = f.block().clone();
            }
            Lane lane = new Lane(id, main.type, new ChainWalk(root, forkCtx, host.resolver(), selectHitBlock),
                    data, false, ticks + 1);
            lane.forkIndex = i;
            lanes.put(lane.key, lane);
            announce.add(chain(lane, true, InteractionState.NotFinished, 0, null));
        }
        main.forksKnown = forks.size();
        if (!announce.isEmpty()) {
            mainChain.newForks = announce.toArray(new SyncInteractionChain[0]);
        }
    }

    private SyncInteractionChain chain(Lane lane, boolean initial, InteractionState state, int base,
                                       InteractionSyncData[] ops) {
        SyncInteractionChain c = new SyncInteractionChain();
        c.activeHotbarSlot = header.hotbarSlot();
        c.activeUtilitySlot = header.utilitySlot();
        c.activeToolsSlot = header.toolsSlot();
        c.itemInHandId = header.item();
        c.utilityItemId = header.utilityItem();
        c.toolsItemId = header.toolsItem();
        c.equipSlot = header.hotbarSlot();
        c.initial = initial;
        c.desync = false;
        c.overrideRootInteraction = Integer.MIN_VALUE;
        c.interactionType = lane.type;
        c.chainId = chainId;
        c.forkedId = lane.forkedId == null ? null : lane.forkedId.clone();
        c.state = state;
        c.newForks = null;
        // The server reads each op's index as operationBaseIndex + i.
        c.operationBaseIndex = base;
        c.data = lane.data.clone();
        c.interactionData = ops;
        return c;
    }

    // ------------------------------------------------------------------
    // Where the server parts from the walk
    // ------------------------------------------------------------------

    /**
     * Looks for the first operation index where the server's path is not the walk's, and replays
     * the walk from the first operation the server ran otherwise before it.
     */
    private void check(Lane lane) {
        if (!lane.live() || lane.walk == null || lane.server.isEmpty()) {
            return;
        }
        Integer parted = null;
        for (Map.Entry<Integer, InteractionSyncData> e : lane.server.entrySet()) {
            int idx = e.getKey();
            InteractionSyncData d = e.getValue();
            InteractionSyncData ours = lane.walk.resolved(idx);
            if (ours != null) {
                if (ours.operationCounter != d.operationCounter || ours.rootInteraction != d.rootInteraction) {
                    parted = idx;
                    break;
                }
            } else if (lane.walk.done() && idx >= lane.walk.position()) {
                // The server ran an operation the walk never did: it went on where the walk ended.
                parted = idx;
                break;
            }
        }
        if (parted == null && lane.serverState != InteractionState.NotFinished && !lane.walk.done()
                && lane.walk.position() > lastServerIndex(lane)) {
            // The server ended, and the walk is beyond the last operation it ran: it ended earlier.
            parted = lane.walk.position();
        }
        if (parted != null) {
            replay(lane, parted);
        }
    }

    private static int lastServerIndex(Lane lane) {
        return lane.server.isEmpty() ? -1 : lane.server.lastKey();
    }

    /** Goes back to the first operation before {@code parted} the server ran otherwise, and on from there. */
    private void replay(Lane lane, int parted) {
        int at = -1;
        for (Map.Entry<Integer, InteractionSyncData> e : lane.server.headMap(parted, false).entrySet()) {
            InteractionSyncData ours = lane.walk.resolved(e.getKey());
            if (ours != null && endsOtherwise(ours, e.getValue())) {
                at = e.getKey();
                break;
            }
        }
        int replays = lane.replays.merge(at >= 0 ? at : parted, 1, Integer::sum);
        boolean back;
        String why;
        if (replays > MAX_REPLAYS_AT) {
            back = false;
            why = "replayed there " + (replays - 1) + " times already";
        } else if (at >= 0) {
            back = lane.walk.rewind(at);
            why = "op " + at + " ended " + lane.server.get(at).state + " on the server";
        } else {
            InteractionSyncData d = lane.server.get(parted);
            back = d != null && lane.walk.rewindTo(parted, d.operationCounter, d.rootInteraction);
            why = "the server ran op " + parted + " as " + (d == null ? "nothing" : d.rootInteraction + ":" + d.operationCounter);
        }
        if (!back) {
            log.warn("meridian-core: forged chain {}{} parted from the server at op {} and cannot follow ({}) - "
                    + "left to the server", chainId, lane == main ? "" : " fork " + lane.key, parted, why);
            lane.abandoned = true;
            return;
        }
        corrections++;
        lane.desync = true;
        lane.sentAll = false;
        log.info("meridian-core: forged chain {}{} parted from the server at op {} ({}) - replaying from op {}",
                chainId, lane == main ? "" : " fork " + lane.key, parted, why, lane.walk.position());
        if (lane == main) {
            // Forks spawned on the path replayed away from are no longer the walk's.
            int keep = main.walk.forks().size();
            for (Lane l : lanes.values()) {
                if (l.forkIndex >= 0 && l.forkIndex >= keep) {
                    l.abandoned = true;
                }
            }
            main.forksKnown = Math.min(main.forksKnown, keep);
        }
    }

    /** Whether the server ran an operation to another end than the walk: state, branch or root entered. */
    private static boolean endsOtherwise(InteractionSyncData ours, InteractionSyncData server) {
        if (server.state == InteractionState.NotFinished) {
            return false;
        }
        return ours.state != server.state
                || ours.nextLabel != server.nextLabel
                || (server.enteredRootInteraction >= 0
                    && server.enteredRootInteraction != ours.enteredRootInteraction);
    }

    // ------------------------------------------------------------------
    // Settling
    // ------------------------------------------------------------------

    private void settle(long now) {
        if (settled) {
            return;
        }
        if (main.cancelled) {
            complete(InteractionOutcome.Status.CANCELLED);
            return;
        }
        boolean all = true;
        for (Lane lane : lanes.values()) {
            all &= lane.settled();
        }
        if (all) {
            complete(main.serverState == InteractionState.Failed
                    ? InteractionOutcome.Status.FAILED : InteractionOutcome.Status.FINISHED);
            return;
        }
        boolean late = (localDone.isDone() && now - localDoneNanos > SETTLE_NANOS) || now - startNanos > MAX_NANOS;
        if (late) {
            if (!localDone.isDone()) {
                // Played too long without an end - stop playing it.
                lanes.values().forEach(l -> l.abandoned = true);
                finishLocally();
            }
            complete(main.settled() && !main.abandoned
                    ? (main.serverState == InteractionState.Failed
                        ? InteractionOutcome.Status.FAILED : InteractionOutcome.Status.FINISHED)
                    : InteractionOutcome.Status.TIMED_OUT);
        }
    }

    private void complete(InteractionOutcome.Status status) {
        if (settled) {
            return;
        }
        settled = true;
        List<InteractionOutcome.Step> steps = new ArrayList<>();
        for (Map.Entry<Integer, InteractionSyncData> e : main.server.entrySet()) {
            InteractionSyncData d = e.getValue();
            steps.add(new InteractionOutcome.Step(e.getKey(), describe(d),
                    d.state == InteractionState.Failed));
        }
        Map<String, InteractionOutcome.Status> forks = new LinkedHashMap<>();
        for (Lane lane : lanes.values()) {
            if (lane == main) {
                continue;
            }
            forks.put(lane.key, lane.cancelled ? InteractionOutcome.Status.CANCELLED
                    : lane.serverState == InteractionState.Finished ? InteractionOutcome.Status.FINISHED
                    : lane.serverState == InteractionState.Failed ? InteractionOutcome.Status.FAILED
                    : InteractionOutcome.Status.TIMED_OUT);
        }
        InteractionOutcome result = new InteractionOutcome(status, steps, forks, corrections);
        log.info("meridian-core: forged chain {} {} ({} op(s), {} fork(s), {} correction(s))", chainId, status,
                steps.size(), forks.size(), corrections);
        // Callbacks run on the scheduler, never under this run's monitor.
        try {
            host.scheduler().schedule(() -> outcome.complete(result), Duration.ZERO);
        } catch (RejectedExecutionException shutDown) {
            outcome.complete(result);
        }
        host.finished(this);
    }

    /** An operation as the server ran it: the interaction's kind and id, or {@code jump}. */
    private String describe(InteractionSyncData d) {
        CompiledInteraction root = host.compile(d.rootInteraction);
        FlatOp op = root == null ? null : root.op(d.operationCounter);
        if (op instanceof FlatOp.Node n) {
            return n.interaction().getClass().getSimpleName() + "#" + n.interactionId();
        }
        return op instanceof FlatOp.Jump ? "jump" : "op " + d.rootInteraction + ":" + d.operationCounter;
    }

    /** A fork's path, outermost first ({@code "5.0/2.1"}); empty for the chain itself. */
    static String path(ForkedChainId f) {
        StringBuilder sb = new StringBuilder();
        for (ForkedChainId at = f; at != null; at = at.forkedId) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(at.entryIndex).append('.').append(at.subIndex);
        }
        return sb.toString();
    }
}
