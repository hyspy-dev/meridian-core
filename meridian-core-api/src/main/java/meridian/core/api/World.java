package meridian.core.api;

import java.util.Optional;

/**
 * The live world — the proxy's mirror of blocks and the local player.
 *
 * <p>A meridian-core Layer-1 service and a building block, not a feature: a
 * Layer-2 module queries it and acts through it, writing its own logic. Core
 * only removes the packet plumbing — it does not encapsulate behaviour.
 *
 * <pre>{@code
 * World world = ctx.services().require(World.class);
 * Vec3 p = world.player().orElseThrow().position();
 * for (int dx = -4; dx <= 4; dx++) {
 *     for (int dz = -4; dz <= 4; dz++) {
 *         Block b = world.blockAt((int) p.x() + dx, (int) p.y(), (int) p.z() + dz);
 *         if (b.type() != null && b.type().name().contains("Flower")) {
 *             b.use();
 *         }
 *     }
 * }
 * }</pre>
 */
public interface World {

    /**
     * The block at the given world coordinates. Never {@code null}; a block in
     * an unloaded chunk reports {@link Block#isAir()} and a {@code null} type.
     */
    Block blockAt(int x, int y, int z);

    /** The local player, or empty until a client session is live. */
    Optional<Player> player();
}
