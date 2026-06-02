package meridian.core.impl;

import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.core.api.CameraControl;
import meridian.core.api.DebugRender;
import meridian.core.api.EntityTracker;
import meridian.core.api.InteractionControl;
import meridian.core.api.SelectionBus;
import meridian.core.api.World;
import meridian.core.api.WorldState;
import meridian.core.impl.interaction.InteractionControlImpl;

/**
 * meridian-core, v0.2.0.
 *
 * <p>Layer-1 module — headless Hytale game state living inside the proxy.
 * Observes server/client traffic and publishes neutral services into the
 * {@code ServiceRegistry} for Layer-2 consumers (xray, camera-tweaks, ...).
 *
 * <ul>
 *   <li>{@link WorldState} — block-type catalog (v0.1.0).</li>
 *   <li>{@link EntityTracker} — live entity positions + local player pose,
 *       built from {@code EntityUpdates} / {@code ClientMovement}.</li>
 *   <li>{@link CameraControl} — drives the client camera via the same packets
 *       Hytale's own server commands use.</li>
 * </ul>
 */
public class MeridianCoreModule implements ProxyModule {

    @Override
    public void onEnable(ModuleContext ctx) {
        ctx.getLogger().info("meridian-core v0.2.0 starting");

        // --- WorldState (block-type catalog) ---------------------------------
        WorldStateImpl worldState = new WorldStateImpl(ctx.scheduler());
        ctx.services().provide(WorldState.class, worldState);
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new BlockTypeObserver(worldState));

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
        ctx.services().provide(World.class, new WorldImpl(
                chunkTracker, worldState, entityTracker, inventoryTracker, interactionControl,
                movementControl));

        // --- SelectionBus: cross-module "user picked this target" pub/sub ----
        // Lets ESP's nearest-* lists drive interaction-test's X/Y/Z fields
        // (and any future consumer) without either module knowing the other.
        ctx.services().provide(SelectionBus.class, new SelectionBusImpl());

        ctx.getLogger().info("meridian-core ready (WorldState, EntityTracker, CameraControl, "
                + "InteractionRegistry, InventoryTracker, ChunkTracker, ItemRegistry, "
                + "InteractionControl, World, SelectionBus)");
    }
}
