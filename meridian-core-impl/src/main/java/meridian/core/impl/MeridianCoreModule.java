package meridian.core.impl;

import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.core.api.CameraControl;
import meridian.core.api.Chat;
import meridian.core.api.MapMarkers;
import meridian.core.api.DebugRender;
import meridian.core.api.EntityTracker;
import meridian.core.api.InteractionControl;
import meridian.core.api.SelectionBus;
import meridian.core.api.World;
import meridian.core.api.ClientAssets;
import meridian.core.api.Hud;
import meridian.core.api.Waypoints;
import meridian.core.api.WorldMap;
import meridian.core.api.WorldMapView;
import meridian.core.api.WorldState;
import meridian.core.impl.interaction.InteractionControlImpl;

/**
 * meridian-core: the game, as something a module can talk to.
 *
 * <p>This is the only module that reads and writes the protocol. Everything it learns from the
 * traffic - the blocks, the entities, the map, the markers, what the client is showing - it
 * publishes as a service, and modules are written against those instead. That is what keeps a
 * module working when the game changes underneath it, and it is also what keeps two modules from
 * fighting: whoever owns a packet here owns it for everyone.
 */
public class MeridianCoreModule implements ProxyModule {

    @Override
    public void onEnable(ModuleContext ctx) {
        ctx.getLogger().info("meridian-core starting");

        // --- WorldState (block-type catalog) ---------------------------------
        WorldStateImpl worldState = new WorldStateImpl(ctx.scheduler());
        ctx.services().provide(WorldState.class, worldState);
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new BlockTypeObserver(worldState));

        // --- WorldMap (the server's own map, collected per world) ------------
        // Two observers: tiles arrive on the WorldMap channel, world changes on Default.
        // One session holder and one asset pipeline for everything that forges: a second push
        // of a hash the client is already downloading disconnects it.
        // A session belongs to one channel. Interface, chat and asset traffic ride Default;
        // the map rides its own, and a map packet sent down the wrong one simply never arrives.
        SessionHolder sessionHolder = new SessionHolder();
        SessionHolder mapSession = new SessionHolder();
        ClientAssetsImpl clientAssets = new ClientAssetsImpl(sessionHolder);
        ctx.services().provide(ClientAssets.class, clientAssets);
        // Drawing on the client: the command vocabulary without the packets, so a module can
        // build an overlay or a page and stay protocol-neutral.
        ctx.services().provide(Hud.class, new HudImpl(sessionHolder));
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new AssetDedupGuard(clientAssets));

        WorldMapImpl worldMap = new WorldMapImpl();
        // The view is built further down (it needs the World facade); the observer reaches it
        // through this holder so a world change can reset the client's map either way round.
        WorldMapViewImpl[] viewRef = new WorldMapViewImpl[1];
        MapMarkersImpl[] markerRef = new MapMarkersImpl[1];
        // The data dir is already per-server, so one file here can never mix two servers'
        // maps; worlds are separated inside it.
        WorldMapStore worldMapStore = new WorldMapStore(ctx.getDataDir().resolve("worldmap.dat"));
        worldMap.restore(worldMapStore.load());
        // Saved periodically as well as at shutdown: a crashed session should cost minutes of
        // exploration, not all of it. Both run off the network thread — the map is snapshotted.
        ctx.scheduler().scheduleAtFixedRate(
                () -> ctx.offloadExecutor().execute(() -> saveMap(ctx, worldMap, worldMapStore)),
                java.time.Duration.ofMinutes(2), java.time.Duration.ofMinutes(2));
        ctx.onShutdown(() -> saveMap(ctx, worldMap, worldMapStore));
        ctx.services().provide(WorldMap.class, worldMap);
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new WorldMapObserver(worldMap));
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new WorldMapWorldObserver(
                        worldMap, sessionHolder, clientAssets, () -> viewRef[0],
                        () -> markerRef[0]));

        // --- EntityTracker + CameraControl + DebugRender ---------------------
        EntityTrackerImpl entityTracker = new EntityTrackerImpl();
        CameraControlImpl cameraControl = new CameraControlImpl();
        DebugRenderImpl debugRender = new DebugRenderImpl(entityTracker);
        // MovementControl: forges the player's own position by rewriting outgoing
        // ClientMovement (the server trusts absolute coords). Backs Player.teleport.
        MovementControlImpl movementControl = new MovementControlImpl(ctx.scheduler());
        ctx.services().provide(EntityTracker.class, entityTracker);
        ctx.services().provide(CameraControl.class, cameraControl);
        ctx.services().provide(DebugRender.class, debugRender);

        // S2C: learn the local id, track entity transforms, capture the session.
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new ServerObserver(entityTracker, cameraControl, debugRender));
        // C2S: track the player's pose, forge movement when armed, and
        // (optionally) auto-grant the freecam key.
        ctx.registerHandler(Direction.C2S, HandlerPosition.NORMAL,
                (direction, session) -> new ClientObserver(entityTracker, cameraControl, debugRender, movementControl));

        // --- Interaction-chain forging foundation ----------------------------
        // InteractionRegistry: server's interaction catalog (UpdateRootInteractions
        // / UpdateInteractions). InventoryTracker: held items + active slots.
        InteractionRegistry interactionRegistry = new InteractionRegistry();
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new InteractionRegistryObserver(interactionRegistry));

        InventoryTracker inventoryTracker = new InventoryTracker();
        ctx.registerHandler(Direction.BOTH, HandlerPosition.MONITOR,
                (direction, session) -> new InventoryObserver(inventoryTracker));

        // ChunkTracker: live block-id mirror of the world (SetChunk + edits).
        ChunkTracker chunkTracker = new ChunkTracker();
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new ChunkObserver(chunkTracker));

        // ItemRegistry: item asset catalog — interaction roots + interactionVars.
        ItemRegistry itemRegistry = new ItemRegistry();
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new ItemRegistryObserver(itemRegistry));

        // InteractionControl: forges interaction chains via the registry + VM.
        // The observer runs at NORMAL in BOTH directions — it not only observes
        // but rewrites interaction chain ids (the chain-id NAT), so forged
        // chains never collide with the player's own counter.
        InteractionControlImpl interactionControl = new InteractionControlImpl(
                interactionRegistry, inventoryTracker, chunkTracker, worldState, itemRegistry,
                ctx.scheduler());
        ctx.services().provide(InteractionControl.class, interactionControl);
        ctx.registerHandler(Direction.BOTH, HandlerPosition.NORMAL,
                (direction, session) -> interactionControl.newObserver());

        // --- World: position-addressed facade over the trackers --------------
        // Block / Player building blocks — a Layer-2 module queries and acts,
        // writing its own logic.
        WorldImpl world = new WorldImpl(
                chunkTracker, worldState, entityTracker, inventoryTracker, interactionControl,
                movementControl);
        ctx.services().provide(World.class, world);

        // --- Chat: telling the player what happened ---------------------------
        ChatImpl chat = new ChatImpl(sessionHolder);
        ctx.services().provide(Chat.class, chat);

        // --- MapMarkers: every marker on the map, and what the client sees of it
        // One owner for the marker traffic. Two modules forging markers separately would each
        // undo the other's work, since neither can see what the other put on the client.
        java.nio.file.Path markerFile = ctx.getDataDir().resolve("markers.json");
        MarkerStoreImpl markerStore = new MarkerStoreImpl(ctx.getLogger());
        markerStore.load(markerFile);
        MapMarkersImpl mapMarkers = new MapMarkersImpl(ctx.getLogger(), markerStore,
                ctx.scheduler(), chat, mapSession, sessionHolder);
        ctx.services().provide(MapMarkers.class, mapMarkers);
        // Early, ahead of the window that trims the same packet: the window decides whether an
        // update is worth forwarding by what is left in it, markers included.
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new MarkerChannelHandler(mapMarkers, mapSession));
        ctx.registerHandler(Direction.C2S, HandlerPosition.NORMAL,
                (direction, session) -> new MarkerRequestHandler(mapMarkers));
        markerRef[0] = mapMarkers;
        ctx.onShutdown(() -> saveMarkers(ctx, markerStore, markerFile));
        ctx.scheduler().scheduleAtFixedRate(
                () -> ctx.offloadExecutor().execute(() -> saveMarkers(ctx, markerStore, markerFile)),
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(30));

        // --- Waypoints: saved places, as a plain list -------------------------
        // A waypoint is a local marker; this is the view of them that a module wanting to drop a
        // pin should need, without knowing what a marker category is.
        WaypointsImpl waypoints = new WaypointsImpl(mapMarkers, world);
        ctx.services().provide(Waypoints.class, waypoints);

        // --- WorldMapView: what the client is shown of the remembered map -----
        // The store is unbounded, the client's map is not: the view feeds a window around the
        // player, in batches, and takes back what leaves it. Pushing the whole store is what
        // killed the client the last time this was tried.
        WorldMapViewImpl worldMapView = new WorldMapViewImpl(worldMap, world::player, mapSession::get);
        viewRef[0] = worldMapView;
        ctx.services().provide(WorldMapView.class, worldMapView);
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new WorldMapViewHandler(worldMapView));
        ctx.scheduler().scheduleAtFixedRate(() -> {
            worldMapView.tick();
            waypoints.project();   // walking changes which waypoints are the nearest
        }, java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(250));

        // --- SelectionBus: cross-module "user picked this target" pub/sub ----
        // Lets ESP's nearest-* lists drive interaction-test's X/Y/Z fields
        // (and any future consumer) without either module knowing the other.
        ctx.services().provide(SelectionBus.class, new SelectionBusImpl());

        ctx.getLogger().info("meridian-core ready (WorldState, EntityTracker, CameraControl, "
                + "InteractionRegistry, InventoryTracker, ChunkTracker, ItemRegistry, "
                + "InteractionControl, World, MapMarkers, Waypoints, Chat, SelectionBus)");
    }

    /** Writes the map, never letting a storage problem take the session down with it. */
    /** Writes the markers, never letting a storage problem take the session down with it. */
    private static void saveMarkers(ModuleContext ctx, MarkerStoreImpl store,
                                    java.nio.file.Path file) {
        try {
            store.saveIfDirty(file);
        } catch (RuntimeException e) {
            ctx.getLogger().warn("meridian-core: could not save the markers: {}", e.toString());
        }
    }

    private static void saveMap(ModuleContext ctx, WorldMapImpl map, WorldMapStore store) {
        try {
            map.persist(store);
        } catch (RuntimeException e) {
            ctx.getLogger().warn("meridian-core: could not save the world map: {}", e.toString());
        }
    }


}
