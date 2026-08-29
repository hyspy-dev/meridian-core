package meridian.core.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import meridian.api.module.Scheduler;
import meridian.api.packet.PacketHandler.Action;
import meridian.api.session.ProxySession;
import meridian.core.api.Chat;
import meridian.core.api.MapMarkers;
import meridian.core.api.Marker;
import meridian.core.api.MarkerCategory;
import meridian.core.api.Vec3;
import meridian.protocol.packets.player.RemoveMapMarker;
import meridian.protocol.packets.worldmap.CreateUserMarker;
import meridian.protocol.packets.worldmap.MapMarker;
import meridian.protocol.packets.worldmap.UpdateWorldMap;
import org.slf4j.Logger;

/**
 * The marker engine: what the server says, what we remember, and what the client is shown.
 *
 * <p>Three ideas hold it together.
 *
 * <p><b>What is known outlives what is shown.</b> The server sends markers for what is nearby and
 * who is online, and takes them away again when either changes. Those are not deletions, so a
 * marker that goes away is kept and marked offline. That is what lets the map remember a base
 * from an hour ago, or where a player was when they logged off.
 *
 * <p><b>The client is told a story we control.</b> Every marker update the server sends is
 * rewritten on the way through: hidden markers are struck out, and our own — the local ones and
 * the last-seen ghosts, which no server would ever send — are put back in. What the client
 * believes is tracked exactly, because the only way to take a marker off its map is to name an
 * id it currently has.
 *
 * <p><b>Silence means no.</b> Creating and deleting are requests, and the server answers only
 * the ones it grants: a marker too far away, too many already, a name too long simply never
 * appear. So every request is given a few seconds, and when nothing comes back the marker is
 * made, or hidden, locally instead - the player gets what they asked for either way.
 */
final class MapMarkersImpl implements MapMarkers {

    /** Marker updates arrive several times a second; five seconds is a long silence. */
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(5);
    /** The server echoes back the coordinates we sent, so matching them closely is enough. */
    private static final double SAME_SPOT = 0.5;
    /** The server pins user markers at this height, whatever was asked for. */
    private static final double MARKER_HEIGHT = 100;

    private final Logger log;
    private final MarkerStoreImpl store;
    private final Scheduler scheduler;
    private final Chat chat;
    /** The map channel: forged marker updates have to go out the way real ones come in. */
    private final SessionHolder mapSession;
    /** The default channel: where a request to the server goes. */
    private final SessionHolder defaultSession;

    private volatile String worldId = "";
    /** The markers we believe the client is showing, ours included. */
    private final Set<String> onClient = ConcurrentHashMap.newKeySet();
    /**
     * Set when the client's map has been wiped - a new world, a reset. Our own markers are gone
     * from it and have to be put back on the next update the server sends.
     */
    private volatile boolean needsRedraw;

    /** Who we are, learned from the first marker the server accepts from us. */
    private volatile UUID selfId;
    private volatile String selfName;

    private volatile boolean localOnly;
    private volatile boolean ghosts = true;

    /** Kept off the map by policy rather than by the player - see {@link #suppress}. */
    private final Set<String> suppressed = ConcurrentHashMap.newKeySet();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private final ConcurrentLinkedQueue<PendingCreate> creates = new ConcurrentLinkedQueue<>();
    private final Map<String, PendingRemove> removes = new ConcurrentHashMap<>();

    MapMarkersImpl(Logger log, MarkerStoreImpl store, Scheduler scheduler, Chat chat,
                   SessionHolder mapSession, SessionHolder defaultSession) {
        this.log = log;
        this.store = store;
        this.scheduler = scheduler;
        this.chat = chat;
        this.mapSession = mapSession;
        this.defaultSession = defaultSession;
    }

    // ==================================================================
    // What the server says
    // ==================================================================

    /**
     * Every marker update, on its way to the client, rewritten to say what we want it to say.
     */
    Action onUpdate(UpdateWorldMap m) {
        String world = worldId;
        long now = System.currentTimeMillis();
        boolean rewritten = false;
        List<MapMarker> adds = new ArrayList<>();
        List<String> removals = new ArrayList<>();
        if (m.removedMarkers != null) {
            Collections.addAll(removals, m.removedMarkers);
        }

        if (m.addedMarkers != null) {
            for (MapMarker incoming : m.addedMarkers) {
                if (incoming == null || incoming.id == null) {
                    continue;
                }
                MarkerState state = store.upsert(world, incoming);
                rewritten |= confirmCreate(state, removals);
                if (state.category == MarkerCategory.PLAYER && state.player != null
                        && onClient.remove(state.ghostId())) {
                    // They are back. Their last-seen marker has nothing left to say.
                    removals.add(state.ghostId());
                    rewritten = true;
                }
                if (state.removedLocally || !visible(state)) {
                    onClient.remove(incoming.id);
                    rewritten = true;   // struck out of the update the client receives
                    continue;
                }
                adds.add(incoming);
                onClient.add(incoming.id);
            }
        }

        if (m.removedMarkers != null) {
            for (String id : m.removedMarkers) {
                if (id == null) {
                    continue;
                }
                boolean weAsked = confirmRemove(id);
                onClient.remove(id);
                MarkerState state = store.get(world, id);
                if (state == null) {
                    continue;
                }
                if (weAsked && state.category != MarkerCategory.PLAYER) {
                    // We asked and the server did it, so this one really is gone.
                    store.remove(world, id);
                    continue;
                }
                // Otherwise it only stopped being shown: the ground scrolled away, someone else
                // deleted it, a player logged out. Keep it, and note when it was last seen.
                state.online = false;
                state.lastSeenMillis = now;
                store.markDirty();
                if (state.category == MarkerCategory.PLAYER && state.player != null
                        && ghosts && visible(state)) {
                    adds.add(MarkerCodec.writeGhost(state));
                    onClient.add(state.ghostId());
                    rewritten = true;
                }
            }
        }

        if (needsRedraw) {
            needsRedraw = false;
            for (MarkerState state : store.markers(world)) {
                rewritten |= redraw(state, adds);
            }
        }

        changed();
        if (!rewritten) {
            return Action.FORWARD;
        }
        m.addedMarkers = adds.isEmpty() ? null : adds.toArray(MapMarker[]::new);
        m.removedMarkers = removals.isEmpty() ? null : removals.toArray(String[]::new);
        return Action.MODIFIED;
    }

    /**
     * Puts back what only we can draw. Local markers and ghosts have no source but us, so a map
     * the client has just cleared has lost them for good unless we send them again.
     */
    private boolean redraw(MarkerState state, List<MapMarker> adds) {
        if (state.category == MarkerCategory.LOCAL) {
            if (visible(state) && onClient.add(state.id)) {
                adds.add(MarkerCodec.writeLocal(state));
                return true;
            }
        } else if (state.category == MarkerCategory.PLAYER && state.player != null
                && !state.online && ghosts && visible(state)
                && onClient.add(state.ghostId())) {
            adds.add(MarkerCodec.writeGhost(state));
            return true;
        }
        return false;
    }

    /** The server wiped the client's map. Everything we put there went with it. */
    void onCleared() {
        onClient.clear();
        needsRedraw = true;
        long now = System.currentTimeMillis();
        for (MarkerState state : store.markers(worldId)) {
            if (state.online) {
                state.online = false;
                state.lastSeenMillis = now;
            }
        }
        store.markDirty();
        changed();
    }

    /** A different world means a different map, and a client that has been emptied. */
    void setWorld(UUID world) {
        String id = world == null ? "" : world.toString();
        if (id.equals(worldId)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (MarkerState state : store.markers(worldId)) {
            if (state.online) {
                state.online = false;
                state.lastSeenMillis = now;
            }
        }
        store.markDirty();
        worldId = id;
        onClient.clear();
        needsRedraw = true;
        changed();
    }

    // ==================================================================
    // What the client asks for
    // ==================================================================

    /** The player placed a marker in game. */
    Action onClientCreate(CreateUserMarker request) {
        Marker made = makeLocal(request.name, request.x, MARKER_HEIGHT, request.z,
                request.markerImage, MarkerCodec.rgb(request.tintColor));
        if (localOnly) {
            chat.send("[Markers] '" + made.displayName() + "' saved on this map only.");
            return Action.DROP;
        }
        // Drawn before the server has said anything, and it may never say anything: a marker it
        // will not take - the player already has as many as it allows, or it is too far away -
        // is refused by silence. Waiting that silence out is the difference between a marker
        // appearing as it is placed and appearing five seconds later. If the server does take
        // it, this one is swapped for the real thing in the same breath.
        await(new PendingCreate(request, null, true, made.id()));
        return Action.FORWARD;
    }

    /** The player deleted a marker in game. */
    Action onClientRemove(String id) {
        if (id == null || id.isEmpty()) {
            return Action.FORWARD;
        }
        if (MarkerCodec.isOurs(id)) {
            // The server has never heard of this one, so there is nobody to ask.
            if (MarkerCodec.isGhost(id)) {
                forgetGhost(id);
                chat.send("[Markers] Last-seen marker removed.");
            } else {
                store.remove(worldId, id);
                chat.send("[Markers] Marker removed.");
            }
            unshow(id);
            changed();
            return Action.DROP;
        }
        if (localOnly) {
            hideForGood(id);
            chat.send("[Markers] Marker hidden on this map only.");
            changed();
            return Action.DROP;
        }
        awaitRemoval(id);
        return Action.FORWARD;
    }

    /** Teleporting works only to markers the server knows about. */
    Action onClientTeleport(String id) {
        if (MarkerCodec.isOurs(id)) {
            chat.send("[Markers] That marker is only on your map - the server cannot take you there.");
            return Action.DROP;
        }
        return Action.FORWARD;
    }

    // ==================================================================
    // The service
    // ==================================================================

    @Override
    public List<Marker> all() {
        List<Marker> out = new ArrayList<>();
        for (MarkerState state : store.markers(worldId)) {
            out.add(state.snapshot());
        }
        return out;
    }

    @Override
    public List<Marker> byCategory(MarkerCategory category) {
        List<Marker> out = new ArrayList<>();
        for (MarkerState state : store.markers(worldId)) {
            if (state.category == category) {
                out.add(state.snapshot());
            }
        }
        return out;
    }

    @Override
    public Optional<Marker> get(String id) {
        MarkerState state = store.get(worldId, id);
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    @Override
    public void hide(String id) {
        if (store.hiddenIn(worldId).add(id)) {
            store.markDirty();
            apply();
        }
    }

    @Override
    public void show(String id) {
        boolean unhidden = store.hiddenIn(worldId).remove(id);
        MarkerState state = store.get(worldId, id);
        if (state != null && state.removedLocally) {
            state.removedLocally = false;
            unhidden = true;
        }
        if (unhidden) {
            store.markDirty();
            apply();
        }
    }

    @Override
    public void hide(java.util.Collection<String> ids) {
        if (store.hiddenIn(worldId).addAll(ids)) {
            store.markDirty();
            apply();
        }
    }

    @Override
    public void show(java.util.Collection<String> ids) {
        boolean changed = store.hiddenIn(worldId).removeAll(ids);
        for (String id : ids) {
            MarkerState state = store.get(worldId, id);
            if (state != null && state.removedLocally) {
                state.removedLocally = false;
                changed = true;
            }
        }
        if (changed) {
            store.markDirty();
            apply();
        }
    }

    @Override
    public boolean isHidden(String id) {
        MarkerState state = store.get(worldId, id);
        return store.hiddenIn(worldId).contains(id)
                || suppressed.contains(id)
                || (state != null && state.removedLocally);
    }

    @Override
    public Set<String> hidden() {
        return Set.copyOf(store.hiddenIn(worldId));
    }

    @Override
    public void showAll() {
        store.hiddenIn(worldId).clear();
        for (MarkerState state : store.markers(worldId)) {
            state.removedLocally = false;
        }
        store.markDirty();
        apply();
    }

    @Override
    public Marker createLocal(String name, Vec3 position, String icon, int colourRgb) {
        return makeLocal(name, position.x(), position.y(), position.z(), icon, colourRgb);
    }

    @Override
    public CompletableFuture<Marker> create(String name, Vec3 position, String icon,
                                            int colourRgb, boolean shared) {
        if (localOnly) {
            return CompletableFuture.completedFuture(
                    createLocal(name, position, icon, colourRgb));
        }
        ProxySession live = defaultSession.get().orElse(null);
        if (live == null) {
            return CompletableFuture.completedFuture(
                    createLocal(name, position, icon, colourRgb));
        }
        CreateUserMarker request = new CreateUserMarker(
                (float) position.x(), (float) position.z(),
                name == null || name.isEmpty() ? null : name,
                icon == null || icon.isEmpty() ? MarkerCodec.DEFAULT_ICON : icon,
                colourRgb >= 0 ? MarkerCodec.colour(colourRgb) : null,
                shared);
        CompletableFuture<Marker> answer = new CompletableFuture<>();
        Marker provisional = createLocal(name, position, icon, colourRgb);
        await(new PendingCreate(request, answer, false, provisional.id()));
        // Straight down the pipe: sending it back through the handlers would land on our own
        // intercept and start the whole dance again.
        live.sendToServer(request);
        return answer;
    }

    @Override
    public boolean remove(String id) {
        MarkerState state = store.get(worldId, id);
        if (state == null) {
            return false;
        }
        if (state.category == MarkerCategory.PLAYER) {
            // Nobody can delete a player. While they are online the marker is a fact, so the
            // most that can be done is stop looking at it; once they are gone, forgetting where
            // they were is a real deletion.
            if (state.online) {
                hide(id);
            } else {
                store.remove(worldId, id);
                unshow(state.ghostId());
                changed();
            }
            return true;
        }
        if (MarkerCodec.isOurs(id)) {
            store.remove(worldId, id);
            unshow(id);
            changed();
            return true;
        }
        ProxySession live = localOnly ? null : defaultSession.get().orElse(null);
        if (live == null) {
            // Either we are not asking the server, or there is nobody to ask. Off the map it goes.
            hideForGood(id);
            changed();
            return true;
        }
        awaitRemoval(id);
        // Straight down the pipe, past our own intercept, which would arm a second wait.
        live.sendToServer(new RemoveMapMarker(id));
        return true;
    }

    @Override
    public void setLocalOnly(boolean value) {
        localOnly = value;
    }

    @Override
    public boolean localOnly() {
        return localOnly;
    }

    @Override
    public void setPlayerGhosts(boolean value) {
        if (ghosts != value) {
            ghosts = value;
            apply();
        }
    }

    @Override
    public boolean playerGhosts() {
        return ghosts;
    }

    @Override
    public void onChanged(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ==================================================================
    // For the rest of core
    // ==================================================================

    /**
     * Sets which markers core itself is keeping off the map, for reasons of its own rather than
     * the player's - the way waypoints draw only the nearest few. Replaces the whole set at once,
     * because the answer changes with every step the player takes and one update is enough.
     *
     * <p>Kept apart from {@link #hide} so the two never overrule each other: whoever wants a
     * marker gone, it goes.
     */
    void setSuppressed(Set<String> ids) {
        if (suppressed.equals(ids)) {
            return;
        }
        suppressed.clear();
        suppressed.addAll(ids);
        apply();
    }

    /** The world the client is in, as markers are keyed by it. */
    String world() {
        return worldId;
    }

    // ==================================================================

    private Marker makeLocal(String name, double x, double y, double z, String icon,
                             int colourRgb) {
        MarkerState state = new MarkerState();
        state.id = MarkerCodec.newLocalId();
        state.worldId = worldId;
        state.name = name == null ? "" : name;
        state.icon = icon == null || icon.isEmpty() ? MarkerCodec.DEFAULT_ICON : icon;
        state.x = x;
        state.y = y;
        state.z = z;
        state.colourRgb = colourRgb;
        state.category = MarkerCategory.LOCAL;
        state.owner = selfId;
        state.ownerName = selfName;
        state.lastSeenMillis = System.currentTimeMillis();
        state.online = true;
        store.put(state);
        if (visible(state)) {
            if (send(List.of(MarkerCodec.writeLocal(state)), null)) {
                onClient.add(state.id);
            } else {
                needsRedraw = true;   // no map channel yet; the next update carries it
            }
        }
        changed();
        return state.snapshot();
    }

    private void await(PendingCreate pending) {
        creates.add(pending);
        pending.timer = scheduler.schedule(() -> giveUpOnCreate(pending), ANSWER_TIMEOUT);
    }

    private void giveUpOnCreate(PendingCreate pending) {
        if (!creates.remove(pending)) {
            return;   // the server answered while we were waiting
        }
        // The marker has been on the map since it was placed. All that is settled here is that
        // it is staying ours.
        Marker made = get(pending.provisionalId).orElseGet(() ->
                makeLocal(pending.name, pending.x, MARKER_HEIGHT, pending.z,
                        pending.icon, pending.colourRgb));
        if (pending.fromClient) {
            chat.send("[Markers] The server would not take '" + made.displayName()
                    + "' - saved on your map instead.");
        }
        log.info("meridian-core: no answer in {}s for marker '{}' at ({}, {}) - kept local",
                ANSWER_TIMEOUT.toSeconds(), made.displayName(), (int) pending.x, (int) pending.z);
        if (pending.answer != null) {
            pending.answer.complete(made);
        }
    }

    /**
     * Matches an arriving marker against what we asked for. The server does not echo a request
     * id, so the only thing to go on is a user marker turning up where one was asked for.
     */
    private boolean confirmCreate(MarkerState state, List<String> removals) {
        if (state.category != MarkerCategory.USER_SHARED
                && state.category != MarkerCategory.USER_PRIVATE) {
            return false;
        }
        boolean shared = state.category == MarkerCategory.USER_SHARED;
        for (PendingCreate pending : creates) {
            if (pending.shared == shared
                    && Math.abs(pending.x - state.x) < SAME_SPOT
                    && Math.abs(pending.z - state.z) < SAME_SPOT
                    && creates.remove(pending)) {
                cancel(pending.timer);
                if (state.owner != null) {
                    // The server just told us who we are, by naming us as the one who placed it.
                    selfId = state.owner;
                    selfName = state.ownerName;
                }
                if (pending.answer != null) {
                    pending.answer.complete(state.snapshot());
                }
                // The real marker is arriving in this very update, so ours leaves in it too -
                // one packet, and nothing the player could catch in between.
                boolean swapped = false;
                if (pending.provisionalId != null) {
                    store.remove(worldId, pending.provisionalId);
                    if (onClient.remove(pending.provisionalId)) {
                        removals.add(pending.provisionalId);
                        swapped = true;
                    }
                }
                return swapped;
            }
        }
        return false;
    }

    private void awaitRemoval(String id) {
        PendingRemove pending = new PendingRemove(id);
        removes.put(id, pending);
        pending.timer = scheduler.schedule(() -> giveUpOnRemoval(pending), ANSWER_TIMEOUT);
    }

    private void giveUpOnRemoval(PendingRemove pending) {
        if (!removes.remove(pending.id, pending)) {
            return;
        }
        hideForGood(pending.id);
        chat.send("[Markers] The server would not delete that marker - hidden on your map instead.");
        log.info("meridian-core: no answer in {}s for the removal of '{}' - hidden instead",
                ANSWER_TIMEOUT.toSeconds(), pending.id);
        changed();
    }

    private boolean confirmRemove(String id) {
        PendingRemove pending = removes.remove(id);
        if (pending == null) {
            return false;
        }
        cancel(pending.timer);
        return true;
    }

    /**
     * Marks a marker as one the player is done with. The server keeps sending it, and every
     * arrival is struck out again, so it stays gone until {@link #showAll}.
     */
    private void hideForGood(String id) {
        MarkerState state = store.get(worldId, id);
        if (state != null) {
            state.removedLocally = true;
            store.markDirty();
        }
        unshow(id);
    }

    private void forgetGhost(String ghostId) {
        for (MarkerState state : store.markers(worldId)) {
            if (state.player != null && ghostId.equals(state.ghostId())) {
                store.remove(worldId, state.id);
                return;
            }
        }
    }

    private boolean visible(MarkerState state) {
        return !state.removedLocally
                && !store.hiddenIn(worldId).contains(state.id)
                && !suppressed.contains(state.id);
    }

    /**
     * Brings the client's map back in line with what should be on it, sending only the
     * difference. Called whenever something that decides visibility moves.
     */
    private void apply() {
        List<MapMarker> adds = new ArrayList<>();
        List<String> removals = new ArrayList<>();
        for (MarkerState state : store.markers(worldId)) {
            if (state.category == MarkerCategory.PLAYER && state.player != null) {
                diff(state.id, state.online && visible(state), () -> state.live, adds, removals);
                diff(state.ghostId(), !state.online && ghosts && visible(state),
                        () -> MarkerCodec.writeGhost(state), adds, removals);
            } else if (state.category == MarkerCategory.LOCAL) {
                diff(state.id, visible(state), () -> MarkerCodec.writeLocal(state), adds, removals);
            } else {
                // The server's own: it can only be put back while the server is still sending it.
                diff(state.id, state.online && visible(state), () -> state.live, adds, removals);
            }
        }
        if (!adds.isEmpty() || !removals.isEmpty()) {
            send(adds, removals);
        }
        changed();
    }

    private void diff(String id, boolean wanted, Supplier<MapMarker> build,
                      List<MapMarker> adds, List<String> removals) {
        boolean shown = onClient.contains(id);
        if (wanted && !shown) {
            MapMarker marker = build.get();
            if (marker != null) {
                adds.add(marker);
                onClient.add(id);
            }
        } else if (!wanted && shown) {
            removals.add(id);
            onClient.remove(id);
        }
    }

    private void unshow(String id) {
        onClient.remove(id);
        send(null, List.of(id));
    }

    private boolean send(List<MapMarker> adds, List<String> removals) {
        ProxySession live = mapSession.get().orElse(null);
        if (live == null) {
            return false;
        }
        live.sendToClient(new UpdateWorldMap(null,
                adds == null || adds.isEmpty() ? null : adds.toArray(new MapMarker[0]),
                removals == null || removals.isEmpty() ? null : removals.toArray(new String[0])));
        return true;
    }

    private void changed() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("meridian-core: a marker listener failed", e);
            }
        }
    }

    private static void cancel(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }

    /** A marker asked for and not yet answered. */
    private static final class PendingCreate {
        final float x;
        final float z;
        final boolean shared;
        final String name;
        final String icon;
        final int colourRgb;
        /** Completed with whatever the player ends up with, when someone is waiting. */
        final CompletableFuture<Marker> answer;
        /** Whether the player asked in game, and so should be told what happened. */
        final boolean fromClient;
        /** The marker drawn straight away, kept if the server never answers, dropped if it does. */
        final String provisionalId;
        volatile ScheduledFuture<?> timer;

        PendingCreate(CreateUserMarker request, CompletableFuture<Marker> answer,
                      boolean fromClient, String provisionalId) {
            this.x = request.x;
            this.z = request.z;
            this.shared = request.shared;
            this.name = request.name;
            this.icon = request.markerImage;
            this.colourRgb = MarkerCodec.rgb(request.tintColor);
            this.answer = answer;
            this.fromClient = fromClient;
            this.provisionalId = provisionalId;
        }
    }

    /** A deletion asked for and not yet answered. */
    private static final class PendingRemove {
        final String id;
        volatile ScheduledFuture<?> timer;

        PendingRemove(String id) {
            this.id = id;
        }
    }
}
