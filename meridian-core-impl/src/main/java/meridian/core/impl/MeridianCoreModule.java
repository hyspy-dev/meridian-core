package meridian.core.impl;

import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.core.api.CameraControl;
import meridian.core.api.EntityTracker;
import meridian.core.api.WorldState;

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

        // --- EntityTracker + CameraControl -----------------------------------
        EntityTrackerImpl entityTracker = new EntityTrackerImpl();
        CameraControlImpl cameraControl = new CameraControlImpl();
        ctx.services().provide(EntityTracker.class, entityTracker);
        ctx.services().provide(CameraControl.class, cameraControl);

        // S2C: learn the local id, track entity transforms, capture the session.
        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new ServerObserver(entityTracker, cameraControl));
        // C2S: track the player's pose and (optionally) auto-grant the freecam key.
        ctx.registerHandler(Direction.C2S, HandlerPosition.NORMAL,
                (direction, session) -> new ClientObserver(entityTracker, cameraControl));

        ctx.getLogger().info("meridian-core ready (WorldState, EntityTracker, CameraControl)");
    }
}
