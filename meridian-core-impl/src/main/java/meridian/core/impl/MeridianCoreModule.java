package meridian.core.impl;

import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.BuilderSelection;
import meridian.core.api.CameraControl;
import meridian.core.api.Chat;
import meridian.core.api.ChunkView;
import meridian.core.api.Containers;
import meridian.core.api.PlayerState;
import meridian.core.api.MapMarkers;
import meridian.core.api.NoClip;
import meridian.core.api.PastePreview;
import meridian.core.api.MarkerArchive;
import meridian.core.api.DebugRender;
import meridian.core.api.EntityTracker;
import meridian.core.api.InteractionControl;
import meridian.core.api.SelectionBus;
import meridian.core.api.World;
import meridian.core.api.ClientAssets;
import meridian.core.api.Hud;
import meridian.core.api.Waypoints;
import meridian.core.api.WorldChunks;
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
        // Truth is read first (EARLY), then the client's view is written into the very packet
        // the server sent (NORMAL): what a type looks like has to be settled while the world
        // loads, because that is when the client binds it to its textures.
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new BlockTypeObserver(worldState));
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new BlockTypeRewriter(worldState));

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
        // Files that must simply be there: announced to the client with the server's own
        // (S2C WorldSettings), then served by the proxy when the client asks for them (C2S
        // RequestAssets), so the server's rebuild indexes them before the block types bind.
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new ConnectAssetHandler(clientAssets));
        ctx.registerHandler(Direction.C2S, HandlerPosition.EARLY,
                (direction, session) -> new ConnectAssetHandler(clientAssets));

        WorldMapImpl worldMap = new WorldMapImpl();
        // The view is built further down (it needs the World facade); the observer reaches it
        // through this holder so a world change can reset the client's map either way round.
        WorldMapViewImpl[] viewRef = new WorldMapViewImpl[1];
        MapMarkersImpl[] markerRef = new MapMarkersImpl[1];
        // No map file. What core keeps is the session's working map - the thing that answers
        // "what colour is it there" and lets the view redraw ground the client has forgotten -
        // and a working map that is also an archive is just an archive that grows forever.
        // Anything that wants the map to outlive the session keeps its own copy; the world
        // downloader does exactly that.
        retireOldMapFile(ctx);
        ctx.services().provide(WorldMap.class, worldMap);
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
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

        // ChunkView: what the client is allowed to forget. Sits at NORMAL because it drops, and
        // downstream of the feed above, which has already seen the unload.
        ChunkViewImpl chunkView = new ChunkViewImpl();
        ctx.services().provide(ChunkView.class, chunkView);
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new ChunkViewHandler(chunkView));

        // Containers: what the player sees inside a chest. The server tells nobody what is in
        // one until it is opened, so this is the only way it can be known - and the block it
        // belongs to is matched from the player's own reach, which the server never repeats.
        ContainersImpl containers = new ContainersImpl();
        ctx.services().provide(Containers.class, containers);
        ctx.registerHandler(Direction.BOTH, HandlerPosition.MONITOR,
                (direction, session) -> new ContainerHandler(containers));

        // PlayerState: the player as the server keeps them - where, how, and carrying what.
        // Who they are comes from the token they log in with, which is what the server goes by.
        PlayerStateImpl playerState = new PlayerStateImpl(entityTracker);
        ctx.services().provide(PlayerState.class, playerState);
        ctx.registerHandler(Direction.BOTH, HandlerPosition.MONITOR,
                (direction, session) -> new PlayerStateHandler(playerState));

        // WorldChunks: the raw feed, for modules that want the world itself rather than an answer
        // about it. EARLY, so a module dropping one of these packets on its way to the client
        // cannot cost a subscriber the event.
        WorldChunksImpl worldChunks = new WorldChunksImpl(worldState);
        ctx.services().provide(WorldChunks.class, worldChunks);
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new WorldChunksHandler(worldChunks));

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
        MarkerStoreImpl markerStore = new MarkerStoreImpl(ctx.getLogger());
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
        // Core remembers markers; it no longer writes them down. The markers module keeps the
        // file, hands back what it kept at startup and takes core's memory as the session goes -
        // so markers survive a restart when it is installed, and last the session when it is not.
        ctx.services().provide(MarkerArchive.class, new MarkerArchiveImpl(markerStore));
        retireOldMarkerFile(ctx);

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
        // Both ways: what the map is allowed to do comes down, what the player asks of it goes up.
        ctx.registerHandler(Direction.C2S, HandlerPosition.NORMAL,
                (direction, session) -> new WorldMapViewHandler(worldMapView));
        ctx.scheduler().scheduleAtFixedRate(() -> {
            worldMapView.tick();
            waypoints.project();   // walking changes which waypoints are the nearest
        }, java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(250));

        // --- SelectionBus: cross-module "user picked this target" pub/sub ----
        // Lets ESP's nearest-* lists drive interaction-test's X/Y/Z fields
        // (and any future consumer) without either module knowing the other.
        ctx.services().provide(SelectionBus.class, new SelectionBusImpl());

        // --- PastePreview: the game's ghost-block overlay, forged for one client ----
        // Both-line packets on the Default channel, so this is offered on every line.
        ctx.services().provide(PastePreview.class, new PastePreviewImpl(sessionHolder));

        // --- 0.6+ only: features whose packets an older protocol does not carry ------
        // Everything in this block references packets absent from the 0.5.9 line. On that line
        // these files are dropped and this block is removed, so the services are simply never
        // provided and a consumer that asks with get() gets nothing - which is the gate.
        NoClipImpl noClip = new NoClipImpl(sessionHolder);
        ctx.services().provide(NoClip.class, noClip);
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new NoClipHandler(noClip));

        BuilderSelectionImpl builderSelection = new BuilderSelectionImpl();
        ctx.services().provide(BuilderSelection.class, builderSelection);
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new BuilderSelectionHandler(builderSelection));
        ctx.registerHandler(Direction.C2S, HandlerPosition.NORMAL,
                (direction, session) -> new BuilderSelectionHandler(builderSelection));

        // No-clip's toggle lives here, in core, because that is where the whole feature lives on
        // this line. A single switch, and a line that says what it is - client-side only, held on
        // against a server that is authoritative for the real thing.
        ctx.registerSettings(SettingsSpec.builder()
                .bool("noclip", "No-clip (client-side; server still collides)", false,
                        noClip::setEnabled)
                .liveText("No-clip", () -> noClip.isEnabled() ? "on (client-side)" : "off")
                .build());
        // --- end 0.6+ only ----------------------------------------------------------

        ctx.getLogger().info("meridian-core ready (WorldState, EntityTracker, CameraControl, "
                + "InteractionRegistry, InventoryTracker, ChunkTracker, ItemRegistry, "
                + "InteractionControl, World, MapMarkers, Waypoints, Chat, SelectionBus)");
    }

    /** Writes the map, never letting a storage problem take the session down with it. */
    /** Writes the markers, never letting a storage problem take the session down with it. */
    /**
     * Says where the markers core used to write have gone, and leaves the file alone.
     *
     * <p>The markers module reads it once and keeps its own from then on, so the old file is
     * neither lost nor needed here.
     */
    private static void retireOldMarkerFile(ModuleContext ctx) {
        java.nio.file.Path old = ctx.getDataDir().resolve("markers.json");
        if (java.nio.file.Files.isRegularFile(old)) {
            ctx.getLogger().info("meridian-core: {} is left over from when core kept the markers; "
                    + "meridian-markers keeps them now and reads this one once", old);
        }
    }

    /**
     * Says something about the map file older versions of core used to keep, and leaves it alone.
     *
     * <p>It was an archive nothing ever pruned, and on a well-explored server it grew to hundreds
     * of megabytes. Core does not write one any more - but it is the player's exploration, so it
     * is reported rather than deleted.
     */
    private static void retireOldMapFile(ModuleContext ctx) {
        java.nio.file.Path old = ctx.getDataDir().resolve("worldmap.dat");
        try {
            if (java.nio.file.Files.isRegularFile(old)) {
                ctx.getLogger().info("meridian-core: {} ({} MB) is left over from when core kept "
                        + "the map on disk; nothing reads it now and it is safe to delete",
                        old, java.nio.file.Files.size(old) / 1048576);
            }
        } catch (java.io.IOException e) {
            // Knowing about an old file is a courtesy, not a reason to fail a session.
        }
    }


}
