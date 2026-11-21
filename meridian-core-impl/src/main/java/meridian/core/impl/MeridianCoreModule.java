package meridian.core.impl;

import java.util.UUID;
import java.util.function.Consumer;
import meridian.api.event.BackendDisconnectedEvent;
import meridian.api.event.EventPriority;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.core.api.Pages;
import meridian.core.api.CameraControl;
import meridian.core.api.Chat;
import meridian.core.api.ChunkView;
import meridian.core.api.ClientSpoof;
import meridian.core.api.Containers;
import meridian.core.api.PlayerState;
import meridian.core.api.MapMarkers;
import meridian.core.api.PastePreview;
import meridian.core.api.MarkerArchive;
import meridian.core.api.DebugRender;
import meridian.core.api.EntityTracker;
import meridian.core.api.InteractionControl;
import meridian.core.api.SelectionBus;
import meridian.core.api.ServerAssets;
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
        // Windows of our own - interactive pages the server never hears about. The C2S side
        // takes their events and the acknowledgements they earn out of the stream before the
        // server, which counts acknowledgements strictly, can see one too many.
        PagesImpl pages = new PagesImpl(sessionHolder);
        ctx.services().provide(Pages.class, pages);
        ctx.registerHandler(Direction.C2S, HandlerPosition.EARLY,
                (direction, session) -> new PagesHandler(pages));
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new PagesHandler(pages));
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new AssetDedupGuard(clientAssets));

        // ServerAssets: what the server pushes at the client that is not the world - UI documents,
        // the pictures beside them, and the commands it builds a page out of. Nothing is kept
        // unless somebody subscribes; EARLY, so the guard above cannot cost the feed an event.
        ServerAssetsImpl serverAssets = new ServerAssetsImpl();
        ctx.services().provide(ServerAssets.class, serverAssets);
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new ServerAssetsHandler(serverAssets));

        // GameAssets: the game's own files, read from this machine - the installed build that speaks
        // our protocol, and what this server announced of its own (as the game downloaded it).
        // No module carries a copy of any.
        java.util.Optional<java.nio.file.Path> gameDir = java.util.Optional.empty();
        java.util.Optional<java.nio.file.Path> userDir = java.util.Optional.empty();
        try {
            gameDir = ctx.gameDirectory();
            userDir = ctx.gameUserDirectory();
        } catch (NoSuchMethodError olderProxy) {
            // a proxy from before the game's location was passed on: core looks for it itself
        }
        meridian.core.impl.assets.GameAssetsImpl gameAssets =
                new meridian.core.impl.assets.GameAssetsImpl(gameDir, userDir, ctx.offloadExecutor());
        final java.util.Optional<java.nio.file.Path> userDirFinal = userDir;
        ctx.services().provide(meridian.core.api.GameAssets.class, gameAssets);
        // The one block property the server never sends - how a block folds a mirror - read from
        // the same files, in the background, once per installation.
        worldState.flips(meridian.core.impl.assets.BlockFlips.lookup(gameAssets, ctx.offloadExecutor()));
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new meridian.core.impl.assets.GameAssetsObserver(gameAssets));
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
        // The join names the world on Default; the map stream still carries the old world's tail
        // after it, so tiles and markers move only when that stream says so (MapStreamWorld).
        MapStreamWorld streamWorld = new MapStreamWorld();
        Consumer<UUID> drawWorld = w -> {
            worldMap.setMapWorld(w);
            MapMarkersImpl liveMarkers = markerRef[0];
            if (liveMarkers != null) {
                liveMarkers.setWorld(w);
            }
        };
        ctx.events().subscribe(BackendDisconnectedEvent.class, EventPriority.MONITOR,
                e -> streamWorld.reset());
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new WorldMapObserver(
                        worldMap, streamWorld, drawWorld, () -> viewRef[0]));
        // Both ways: the join comes down, the client's ready for it goes up.
        ctx.registerHandler(Direction.BOTH, HandlerPosition.MONITOR,
                (direction, session) -> new WorldMapWorldObserver(
                        worldMap, streamWorld, drawWorld, sessionHolder, clientAssets,
                        () -> viewRef[0]));

        // --- EntityTracker + CameraControl + DebugRender ---------------------
        EntityTrackerImpl entityTracker = new EntityTrackerImpl();
        CameraControlImpl cameraControl = new CameraControlImpl();
        DebugRenderImpl debugRender = new DebugRenderImpl(entityTracker);
        // MovementControl: forges the player's own position by rewriting outgoing
        // ClientMovement (the server trusts absolute coords). Backs Player.teleport.
        // Whose every teleport the game is sent is - the server's or core's - so the game's
        // acknowledgements of core's stop at the proxy (the server ends the connection on those).
        meridian.core.impl.movement.ClientTeleports clientTeleports = new meridian.core.impl.movement.ClientTeleports();
        MovementControlImpl movementControl = new MovementControlImpl(ctx.scheduler(), clientTeleports);
        ctx.services().provide(EntityTracker.class, entityTracker);
        ctx.services().provide(CameraControl.class, cameraControl);
        ctx.services().provide(DebugRender.class, debugRender);
        // PacketTap: packets as bytes, for a module that records them (what the server sent, before
        // the proxy changes it; what went to the server, as it went).
        PacketTapImpl packetTap = new PacketTapImpl(ctx.getLogger());
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY, (direction, session) -> packetTap.fromServer());
        ctx.registerHandler(Direction.C2S, HandlerPosition.MONITOR, (direction, session) -> packetTap.toServer());
        ctx.services().provide(meridian.core.api.PacketTap.class, packetTap);
        // EntityLabels: a short text over an entity, drawn over walls (the game's pop-up text layer).
        EntityLabelsImpl entityLabels = new EntityLabelsImpl(sessionHolder, ctx.scheduler());
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR, (direction, session) -> entityLabels);
        ctx.services().provide(meridian.core.api.EntityLabels.class, entityLabels);

        // PlayerState: the player as the server keeps them - where, how, and carrying what.
        // Who they are comes from the token they log in with, which is what the server goes by.
        PlayerStateImpl playerState = new PlayerStateImpl(entityTracker);
        // Every entity's shared stats (a mob's health) are read against the same catalog as ours.
        entityTracker.statCatalog(playerState.statCatalog());

        // ClientSpoof: what a module wants the client shown, and the server told of its movement.
        // Applied by core's own rewriters (movement below, ClientSpoofHandler further down) after
        // the truth is read, so no service of core's ever sees the spoof. The server's movement
        // settings are core movement's, built further down.
        meridian.core.impl.movement.MovementImpl[] movementRef = new meridian.core.impl.movement.MovementImpl[1];
        ClientSpoofImpl clientSpoof = new ClientSpoofImpl(sessionHolder, entityTracker, playerState,
                () -> movementRef[0] == null ? null : movementRef[0].serverSettings());
        ctx.services().provide(ClientSpoof.class, clientSpoof);

        // Combat: the hits the player takes and the attacks others begin, as the server tells any
        // game - whom to fight is a module's call.
        CombatImpl combat = new CombatImpl(entityTracker);
        ctx.services().provide(meridian.core.api.Combat.class, combat);
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR, (direction, session) -> new CombatHandler(combat));

        // S2C: learn the local id, track entity transforms, capture the session.
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new ServerObserver(entityTracker, cameraControl, debugRender));
        // C2S: track the player's pose, forge movement when armed - the position, the spoofed
        // flags - and (optionally) auto-grant the freecam key.
        ctx.registerHandler(Direction.C2S, HandlerPosition.NORMAL,
                (direction, session) -> new ClientObserver(entityTracker, cameraControl, debugRender,
                        movementControl, clientSpoof));

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
        //
        // EARLY, and it has to be. What this mirrors is what the server said, so it must see the
        // packet before anything can drop it or rewrite it - and both happen: ChunkView below
        // drops the server's unloads to keep chunks drawn, and modules that recolour the world
        // rewrite sections in flight. Sitting downstream of either one costs the mirror the
        // truth: at MONITOR it never saw an unload while chunks were held, so nothing was ever
        // let go of and a session grew by 128 KB a section until the heap was gone.
        // The fluid catalog is read here once, for the mirror, the raw feed and the body alike.
        FluidCatalog fluidCatalog = new FluidCatalog();
        ChunkTracker chunkTracker = new ChunkTracker(fluidCatalog);
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new ChunkObserver(chunkTracker, fluidCatalog));

        // Movement: the headless player's body - what the ground allows (the blocks' own collision
        // shapes) and moves along it, reported tick by tick as the client reports them.
        meridian.core.impl.movement.BlockHitboxes blockHitboxes = new meridian.core.impl.movement.BlockHitboxes();
        meridian.core.impl.movement.Terrain terrain =
                new meridian.core.impl.movement.Terrain(chunkTracker, worldState, blockHitboxes);
        meridian.core.impl.movement.MovementImpl movement = new meridian.core.impl.movement.MovementImpl(
                terrain, blockHitboxes, fluidCatalog, ctx.scheduler(),
                () -> sessionHolder.get().map(HeadlessClientHandler::headlessOf).orElse(null),
                () -> entityTracker.localPosition().orElse(null),
                entityTracker::localEntityId, entityTracker::effectsOf, clientTeleports);
        ctx.services().provide(meridian.core.api.Movement.class, movement);
        // A trip flies only with flight switched on (client-control's Fly): in creative it walks.
        movement.flightOn(clientSpoof::flight);
        movementRef[0] = movement;
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY, (direction, session) -> movement.observer());
        // Stop old body reports before the headless handler queues a teleport ACK. Both are
        // early, but their order matters: sendAsClient queues onto the connection's event loop.
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new HeadlessClientHandler(entityTracker, userDirFinal));
        // Last on the client side: what actually goes out - a teleport's answer, core's own teleport.
        ctx.registerHandler(Direction.C2S, HandlerPosition.MONITOR, (direction, session) -> movement.clientObserver());
        // First on the client side: a game's movement, held back while core holds its body, before
        // any observer takes the game's stale pose for the player's.
        ctx.registerHandler(Direction.C2S, HandlerPosition.EARLY, (direction, session) -> movement.gameGate());
        // Core's own teleport of a game's body core holds moves the body.
        movementControl.heldBody(movement::teleportHeld);
        // A death gives a game's body core holds back to the game: the server opens its death window.
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY, (direction, session) -> new meridian.api.packet.PacketHandler() {
            @Override
            public Action handleS2C(io.netty.channel.ChannelHandlerContext c, meridian.api.packet.Packet packet,
                                    meridian.api.session.ProxySession s) {
                if (packet instanceof meridian.protocol.packets.interface_.CustomPage page
                        && ClientSpoofImpl.RESPAWN_PAGE_KEY.equals(page.key)) {
                    movement.died();
                }
                return Action.FORWARD;
            }
        });

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

        // The server's word on the player is read EARLY, before anything rewrites it on the way
        // to the client; what the client sends, at MONITOR - what actually went out.
        ctx.services().provide(PlayerState.class, playerState);
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new PlayerStateHandler(playerState));
        ctx.registerHandler(Direction.C2S, HandlerPosition.MONITOR,
                (direction, session) -> new PlayerStateHandler(playerState));
        // What the client is shown: after the pages handler, which still hears the server's death
        // window when the spoof keeps it from the client.
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new ClientSpoofHandler(clientSpoof));

        // WorldChunks: the raw feed, for modules that want the world itself rather than an answer
        // about it. EARLY, so a module dropping one of these packets on its way to the client
        // cannot cost a subscriber the event.
        WorldChunksImpl worldChunks = new WorldChunksImpl(worldState, fluidCatalog);
        worldChunks.scheduler(ctx.scheduler());
        ctx.services().provide(WorldChunks.class, worldChunks);
        ctx.registerHandler(Direction.S2C, HandlerPosition.EARLY,
                (direction, session) -> new WorldChunksHandler(worldChunks));

        // ItemRegistry: item asset catalog — interaction roots + interactionVars.
        ItemRegistry itemRegistry = new ItemRegistry();
        // A block's kind is its item's categories (Blocks.Ores, Blocks.Rocks...), a mod's included.
        worldState.itemCategories(item -> itemRegistry.item(item)
                .map(i -> i.categories == null ? java.util.List.<String>of()
                        : java.util.Arrays.stream(i.categories).filter(java.util.Objects::nonNull).toList())
                .orElse(java.util.List.of()));
        // A new item catalog: rules that tell blocks apart by kind run again.
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new ItemRegistryObserver(itemRegistry, worldState::itemsArrived));

        // InteractionControl: forges interaction chains via the registry + VM.
        // The observer runs at NORMAL in BOTH directions — it not only observes
        // but rewrites interaction chain ids (the chain-id NAT), so forged
        // chains never collide with the player's own counter.
        InteractionControlImpl interactionControl = new InteractionControlImpl(
                interactionRegistry, inventoryTracker, chunkTracker, worldState, itemRegistry,
                ctx.scheduler(), new meridian.core.impl.interaction.EntityLookup() {
                    @Override
                    public meridian.core.impl.interaction.EntityTarget target(int id) {
                        return entityTracker.entityTarget(id, movement.eyeHeight());
                    }

                    @Override
                    public java.util.Map<meridian.protocol.InteractionType, Integer> selfInteractions() {
                        java.util.OptionalInt me = entityTracker.localEntityId();
                        if (me.isEmpty()) {
                            return java.util.Map.of();
                        }
                        java.util.Map<meridian.protocol.InteractionType, Integer> m =
                                entityTracker.interactionsOf(me.getAsInt());
                        return m == null ? java.util.Map.of() : m;
                    }

                    @Override
                    public java.util.Set<Integer> selfEffects() {
                        return entityTracker.selfEffects();
                    }

                    @Override
                    public meridian.core.impl.interaction.StatSheet selfStats() {
                        return playerState.sheet();
                    }

                    @Override
                    public meridian.protocol.GameMode selfGameMode() {
                        return playerState.gameMode();
                    }
                }, movement::eyeHeight);
        ctx.services().provide(InteractionControl.class, interactionControl);
        // What an item does in a fight: the effects it applies (core's catalog of them, kept by
        // movement) and the stats they change, by name.
        interactionControl.fightLookups(movement::effect, name -> playerState.statIndex(name).orElse(-1));
        // A repair as a player makes it: a kit used, the server's list of worn items answered.
        Repairs repairs = new Repairs(sessionHolder, inventoryTracker, itemRegistry, playerState);
        repairs.hands(interactionControl);
        interactionControl.repairer(repairs::repair);
        ctx.registerHandler(Direction.BOTH, HandlerPosition.MONITOR, (direction, session) -> repairs.handler());
        // A forged ApplyForce pushes the headless body, as the client's own push would.
        interactionControl.forceBody(movement.forceBody());
        // A loop of swings at a block waits out an urgent hold taken after it began.
        interactionControl.urgency(movement.urgency());
        // The reports of a game's body core moves skip the client-side chain: core's own observers of
        // it hear them here, in the chain's order - the pose as it is, then the spoof, then the forges.
        movement.gameObservers(m -> {
            entityTracker.onClientMovement(m);
            clientSpoof.rewrite(m);
            interactionControl.ownMovement(m);
        });
        ctx.registerHandler(Direction.BOTH, HandlerPosition.NORMAL,
                (direction, session) -> interactionControl.newObserver());

        RangedCombatImpl ranged = new RangedCombatImpl(entityTracker, inventoryTracker, playerState,
                interactionControl, movement, terrain, ctx.scheduler(), ctx.offloadExecutor());
        interactionControl.projectiles(ranged);
        ctx.services().provide(meridian.core.api.RangedCombat.class, ranged);
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR, (direction, session) -> {
            return new meridian.api.packet.PacketHandler() {
                @Override public Action handleS2C(io.netty.channel.ChannelHandlerContext channel,
                        meridian.api.packet.Packet packet, meridian.api.session.ProxySession connection) {
                    if (packet instanceof meridian.protocol.packets.setup.WorldSettings) ranged.reset();
                    if (packet instanceof meridian.protocol.packets.player.JoinWorld) ranged.worldChanged();
                    if (packet instanceof meridian.protocol.packets.assets.UpdateProjectileConfigs configs) ranged.update(configs);
                    if (packet instanceof meridian.protocol.packets.setup.SetTimeDilation time) ranged.timeDilation(time.timeDilation);
                    if (packet instanceof meridian.protocol.packets.entities.EntityUpdates) ranged.observeMotion();
                    return Action.FORWARD;
                }
            };
        });

        // --- World: position-addressed facade over the trackers --------------
        // Block / Player building blocks — a Layer-2 module queries and acts,
        // writing its own logic.
        WorldImpl world = new WorldImpl(
                chunkTracker, worldState, terrain, entityTracker, inventoryTracker, interactionControl,
                movementControl, ctx.scheduler(), movement::eyeHeight, itemRegistry);
        ctx.services().provide(World.class, world);

        // --- Chat: telling the player what happened ---------------------------
        ChatImpl chat = new ChatImpl(sessionHolder);
        ctx.services().provide(Chat.class, chat);

        // --- MapMarkers: every marker on the map, and what the client sees of it
        // One owner for the marker traffic. Two modules forging markers separately would each
        // undo the other's work, since neither can see what the other put on the client.
        MarkerStoreImpl markerStore = new MarkerStoreImpl(ctx.getLogger());
        MapMarkersImpl mapMarkers = new MapMarkersImpl(ctx.getLogger(), markerStore,
                ctx.scheduler(), chat, mapSession, sessionHolder, gameAssets);
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
        // The server's prefab files, and the world taken into one - no packets, only the format.
        ctx.services().provide(meridian.core.api.Prefabs.class, new PrefabsImpl(world, worldState));

        // --- Navigator and Worker: going somewhere, and working on blocks there -------------
        // On core's own services only - movement, the world, the hands - for any module that
        // takes the player somewhere or puts them to work (a miner, a builder).
        meridian.core.impl.navigation.Walker walker = new meridian.core.impl.navigation.Walker(
                movement, world, interactionControl,
                () -> playerState.stats().getOrDefault("Stamina", Double.NaN), ctx.offloadExecutor(), ctx.getLogger());
        ctx.services().provide(meridian.core.api.Navigator.class, walker);
        meridian.core.impl.navigation.WorkerImpl worker = new meridian.core.impl.navigation.WorkerImpl(
                movement, world, interactionControl, entityTracker, walker, containers, playerState,
                () -> sessionHolder.get().orElse(null), ctx.offloadExecutor(), ctx.getLogger());
        worker.items(itemRegistry::item);
        ctx.services().provide(meridian.core.api.Worker.class, worker);
        meridian.core.impl.navigation.NavigationPanel navigation =
                new meridian.core.impl.navigation.NavigationPanel(walker, worker, movement, interactionControl);
        navigation.entities(entityTracker::positionOf);
        navigation.register(ctx);
        meridian.api.settings.SettingsSpec.Builder settings = meridian.api.settings.SettingsSpec.builder();

        // Players' names starred in what reaches the game (registered last, below).
        Anonymizer anonymizer = new Anonymizer(playerState::name, entityTracker::isPlayer);
        settings.bool("anonymize", "Anonymise players (names as stars, own included)", false,
                anonymizer::setEnabled);
        settings.persistent("anonymize");

        settings.section("Go to a point", navigation.section());
        ctx.registerSettings(settings.build());

        // The services above on the proxy's command channel: core <conn> ..., for driving and
        // checking the forged chains from outside the game.
        CoreCommands.register(ctx, sessionHolder, anonymizer, entityLabels);

        // Last of core's handlers, at MONITOR: core's readers - the entity tracker among them, which
        // watches at MONITOR - have seen the real names by then, and only what goes on to the game
        // is starred. A module's MONITOR handler, registered after core, sees the stars.
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR, (direction, session) -> anonymizer.handler());

        ctx.getLogger().info("meridian-core ready (WorldState, EntityTracker, CameraControl, "
                + "InteractionRegistry, InventoryTracker, ChunkTracker, ItemRegistry, "
                + "InteractionControl, World, MapMarkers, Waypoints, Chat, SelectionBus, ClientSpoof, "
                + "Navigator, Worker)");
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
