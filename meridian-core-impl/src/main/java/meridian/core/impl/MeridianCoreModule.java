package meridian.core.impl;

import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.core.api.WorldState;

/**
 * meridian-core, v0.1.0.
 *
 * <p>First real service: {@link WorldState}. Core observes S2C
 * {@code UpdateBlockTypes} via a MONITOR handler to build server truth, and
 * publishes a live {@code WorldState} into the {@code ServiceRegistry} for
 * Layer-2 consumers (xray, jesus, ...).
 */
public class MeridianCoreModule implements ProxyModule {

    @Override
    public void onEnable(ModuleContext ctx) {
        ctx.getLogger().info("meridian-core v0.1.0 starting");

        WorldStateImpl worldState = new WorldStateImpl(ctx.scheduler());
        ctx.services().provide(WorldState.class, worldState);

        ctx.registerHandler(Direction.S2C, HandlerPosition.MONITOR,
                (direction, session) -> new BlockTypeObserver(worldState));

        ctx.getLogger().info("meridian-core ready (WorldState live — block-type catalog)");
    }
}
