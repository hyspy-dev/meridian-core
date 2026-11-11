package meridian.core.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import meridian.core.api.BlockView;
import meridian.core.api.WorldChunks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The chunk feed: everything the server says about the shape of the world, passed on to whoever
 * asked for it.
 *
 * <p>Nothing is stored here beyond the id tables. A section is handed to the listeners and
 * forgotten - what to keep and how is the subscriber's business, and holding a second copy of the
 * world inside the proxy for the sake of modules that may not want it would be an expensive way to
 * be helpful.
 *
 * <p>With one exception: what arrives while no world is named - before the first join, and between
 * a cleared chunk stream and the join that names where it went - is held, in order, and handed on
 * right after {@link Listener#enterWorld}. The chunk channel races the join, so without this every
 * subscriber had to park the first burst under a placeholder world itself. Only the references are
 * held: the arrays are the packets' own, which a subscriber keeps anyway.
 *
 * <p>Listeners are held in a copy-on-write list: subscriptions are rare, dispatch is constant, and
 * a module subscribing from its own thread must not interrupt a section already being delivered.
 */
final class WorldChunksImpl implements WorldChunks {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    private final WorldStateImpl worldState;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Fluid names: core's one catalog, which only grows. */
    private final FluidCatalog fluids;
    private final Map<Integer, String> environments = new ConcurrentHashMap<>();

    /**
     * A cleared chunk stream with no world named yet is resolved to the world it was already in
     * after this long - that is a chunk resend, not a world change.
     */
    private static final long RESEND_NANOS = 2_000_000_000L;

    private volatile UUID world;
    /** A join seen before the chunk stream turned over; applied when it does. */
    private UUID pendingJoin;
    /** The chunk stream turned over and is waiting to be told where it is now. */
    private boolean awaitingWorld;
    private long awaitingSince;
    private final Object boundary = new Object();

    /**
     * Whether arrivals are held: no world is named yet, or the stream turned over and the next one
     * is not named. Read without the lock on the hot path; changed only holding {@link #boundary}.
     */
    private volatile boolean holding = true;
    /** What arrived while {@link #holding}, in order. Guarded by {@link #boundary}. */
    private final List<Consumer<Listener>> held = new ArrayList<>();
    /** Past this many held arrivals with no world named, the rest go through unheld. */
    private static final int HOLD_LIMIT = 200_000;

    /** The block-name table and the number of types it was built from. */
    private volatile Map<Integer, String> blocks;
    private volatile int blocksBuiltFrom = -1;

    WorldChunksImpl(WorldStateImpl worldState, FluidCatalog fluids) {
        this.worldState = worldState;
        this.fluids = fluids;
    }

    // ------------------------------------------------------------------
    // The contract
    // ------------------------------------------------------------------

    @Override
    public void subscribe(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            UUID current = world;
            if (current != null) {
                // Subscribing mid-session: tell it where it is, so it need not wait for the next
                // join to find out.
                deliver(listener, l -> l.enterWorld(current));
            }
        }
    }

    @Override
    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public UUID world() {
        return world;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Held rather than rebuilt each time: a subscriber writing chunks asks for this once per
     * column, and the underlying call allocates a wrapper for every block type in the game. It is
     * rebuilt when the server sends more of them, and thrown away on a join.
     */
    @Override
    public Map<Integer, String> blockNames() {
        Map<Integer, String> cached = blocks;
        if (cached != null && blocksBuiltFrom == worldState.blockTypeCount()) {
            return cached;
        }
        int count = worldState.blockTypeCount();
        Map<Integer, String> names = new HashMap<>();
        for (BlockView type : worldState.allBlockTypes()) {
            names.put(type.id(), type.name() == null ? BlockView.UNKNOWN : type.name());
        }
        Map<Integer, String> built = Map.copyOf(names);
        blocks = built;
        blocksBuiltFrom = count;
        return built;
    }

    @Override
    public Map<Integer, String> fluidNames() {
        return fluids.names();
    }

    @Override
    public Map<Integer, String> environmentNames() {
        return Map.copyOf(environments);
    }

    @Override
    public Section decode(byte[] data) {
        return ChunkDecoder.rawSection(data);
    }

    @Override
    public FluidSection decodeFluids(byte[] data) {
        return ChunkDecoder.rawFluids(data);
    }

    // ------------------------------------------------------------------
    // Ingest — from WorldChunksHandler, on the network threads
    // ------------------------------------------------------------------

    /**
     * A join packet: the name of a world, off the connection's main channel.
     *
     * <p>It does not, on its own, mean the chunks have changed over. The server tears the old
     * world down with a packet on the chunk channel, and that packet is the seam - this one races
     * it and usually loses. So unless there is nothing to be confused with, the name waits here
     * until the chunk stream says it has turned over.
     *
     * @param clearing the packet's own word for whether the client is tearing the world down
     */
    void onJoinWorld(UUID id, boolean clearing) {
        synchronized (boundary) {
            if (world == null || !clearing || awaitingWorld) {
                // Nothing to be confused with (the first world), nothing being torn down, or the
                // seam has already gone past and this is the name it was waiting for.
                apply(id);
                return;
            }
            pendingJoin = id;
        }
    }

    /** The chunk stream turned over. Everything after this belongs to wherever we are going. */
    void onChunksCleared() {
        fanOut(Listener::chunksCleared);
        synchronized (boundary) {
            if (pendingJoin != null) {
                apply(pendingJoin);
                return;
            }
            awaitingWorld = true;
            awaitingSince = System.nanoTime();
            holding = true;
        }
        // What came meanwhile is held, so a resend that is over before the wait is up still has
        // to be let through: looked at again once it is.
        meridian.api.module.Scheduler later = scheduler;
        if (later != null) {
            later.schedule(this::resolveIfStale,
                    java.time.Duration.ofNanos(RESEND_NANOS).plusMillis(100));
        }
    }

    /** Where the wait after a cleared stream is checked when nothing more arrives; null = never. */
    private volatile meridian.api.module.Scheduler scheduler;

    void scheduler(meridian.api.module.Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * A cleared stream that no join ever followed was a resend of the world we are already in -
     * the chunk-resend command does exactly that. Checked as chunks arrive, and once more when the
     * wait is up (what arrived meanwhile is held, and the resend may be over by then).
     */
    private void resolveIfStale() {
        if (!awaitingWorld) {
            return;                     // the common case, and free
        }
        synchronized (boundary) {
            if (awaitingWorld && world != null
                    && System.nanoTime() - awaitingSince > RESEND_NANOS) {
                apply(world);
            }
        }
    }

    /** Names the world the chunks now belong to, and says so. Call holding {@link #boundary}. */
    private void apply(UUID id) {
        pendingJoin = null;
        awaitingWorld = false;
        world = id;
        // The id tables are NOT cleared here. They are the connection's asset catalogs and they
        // arrive with the rest of the assets, before the first join - clearing them on a world
        // change emptied them for good, and every fluid then went to disk named "Unknown".
        fanOut(l -> l.enterWorld(id));
        // Then what came while the world had no name - it belongs to this one.
        for (Consumer<Listener> call : held) {
            fanOut(call);
        }
        held.clear();
        holding = false;
    }

    /**
     * Hands an arrival on, or holds it while no world is named (see the class note).
     */
    private void dispatch(Consumer<Listener> call) {
        if (listeners.isEmpty()) {
            return;                     // nobody to hand it to: nothing worth holding either
        }
        if (holding) {
            synchronized (boundary) {
                if (holding) {
                    if (held.size() < HOLD_LIMIT) {
                        held.add(call);
                        return;
                    }
                    log.warn("meridian-core: {} chunk arrivals and still no world named - passing them on",
                            HOLD_LIMIT);
                    for (Consumer<Listener> early : held) {
                        fanOut(early);
                    }
                    held.clear();
                    holding = false;
                }
            }
        }
        fanOut(call);
    }

    void onEnvironmentCatalog(Map<Integer, String> named) {
        environments.putAll(named);
    }

    void onSection(int x, int y, int z, byte[] data, byte[] localLight, byte[] globalLight) {
        resolveIfStale();
        dispatch(l -> l.section(x, y, z, data, localLight, globalLight));
    }

    void onFluids(int x, int y, int z, byte[] data) {
        resolveIfStale();
        dispatch(l -> l.fluids(x, y, z, data));
    }

    void onHeightmap(int x, int z, byte[] data) {
        dispatch(l -> l.heightmap(x, z, data));
    }

    void onTintmap(int x, int z, byte[] data) {
        dispatch(l -> l.tintmap(x, z, data));
    }

    void onEnvironments(int x, int z, byte[] data) {
        dispatch(l -> l.environments(x, z, data));
    }

    void onUnload(int x, int z) {
        dispatch(l -> l.unload(x, z));
    }

    private void fanOut(Consumer<Listener> call) {
        for (Listener listener : listeners) {
            deliver(listener, call);
        }
    }

    /** One listener throwing is that listener's problem, not the world's. */
    private void deliver(Listener listener, Consumer<Listener> call) {
        try {
            call.accept(listener);
        } catch (RuntimeException e) {
            log.warn("meridian-core: chunk listener {} threw",
                    listener.getClass().getName(), e);
        }
    }
}
