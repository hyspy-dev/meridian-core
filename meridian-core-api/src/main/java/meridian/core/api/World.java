package meridian.core.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

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
     * an unloaded chunk reports {@link Block#isAir()} and a {@code null} type -
     * as air does, so ask {@link #known} to tell the two apart.
     */
    Block blockAt(int x, int y, int z);

    /**
     * Whether core has been sent the blocks of this cell: its section came, or the server said the
     * section holds nothing (an older server, which does not say so, leaves such a section out of a
     * column it sends). Where this is false, {@link #blockAt} reads air because nothing is known
     * there, not because the cell is empty.
     *
     * <p>A core too old to tell answers true only where a block stands, the one thing that proves
     * the chunk was sent.
     */
    default boolean known(int x, int y, int z) {
        return blockAt(x, y, z).type() != null;
    }

    /**
     * The blocks within {@code radius} of {@code from} whose type {@code type} accepts, nearest
     * first by the distance from {@code from} to the middle of the block, at most {@code limit} of
     * them.
     *
     * <p>Only what core has been sent is searched. Air is never offered to {@code type}, nor a block
     * whose type the server has not described yet. A block bigger than one cell - a door, a bed -
     * is found once, at the cell it stands on ({@link Block#base()}), never at the cells it only
     * fills. {@code type} is asked once per kind of block in a call, not once per block, so it can
     * afford to look at names and categories:
     *
     * <pre>{@code
     * List<Block> ores = world.find(player.position(), 32,
     *         t -> t.categories().contains("Blocks.Ores"), 10);
     * }</pre>
     *
     * <p>A core too old to search answers with an empty list.
     *
     * @param from   where distances are measured from - the feet, the eyes
     * @param radius how far out to look, in blocks
     * @param type   which kinds of block count
     * @param limit  the most blocks to return
     * @return the blocks found, nearest first; empty when none are, or {@code limit} is under 1
     */
    default List<Block> find(Vec3 from, double radius, Predicate<BlockView> type, int limit) {
        return List.of();
    }

    /**
     * Waits until the block at {@code pos} is one {@code until} accepts - a block the server breaks,
     * places or turns, or a chunk that arrives.
     *
     * <p>{@code until} is asked at once, then each time the server changes that cell or sends the
     * chunk it is in; there is no polling. It is asked on the thread the change arrives on, so it
     * should only look at the block it is given. The future completes on core's scheduler thread,
     * or at once when the block is already one {@code until} accepts.
     *
     * <pre>{@code
     * world.awaitBlock(pos, Block::isAir, Duration.ofSeconds(2))
     *      .thenAccept(b -> log.info(b.isPresent() ? "gone" : "still there"));
     * }</pre>
     *
     * <p>A core too old to wait answers at once, from the block as it is now.
     *
     * @param pos     the cell to watch
     * @param until   what the block has to be
     * @param timeout how long to wait at most
     * @return the block as {@code until} accepted it, or empty when {@code timeout} ran out first
     */
    default CompletableFuture<Optional<Block>> awaitBlock(BlockPos pos, Predicate<Block> until,
                                                          Duration timeout) {
        Block now = blockAt(pos.x(), pos.y(), pos.z());
        return CompletableFuture.completedFuture(
                until.test(now) ? Optional.of(now) : Optional.empty());
    }

    /**
     * The name of the fluid at a world position - {@code "Water"}, {@code "Lava"}, ... - or
     * {@code null} when there is no fluid there (or the section is not loaded).
     *
     * <p>Fluids are a layer of their own: {@link #blockAt} reads air where water or lava is, so a
     * scan for the surface would land on the floor of a pond. Ask here as well to lift clear of
     * one, or to keep out of lava.
     */
    String fluidAt(int x, int y, int z);

    /**
     * The fluid at a world position with its id and level, or {@code null} when there is none
     * (or the section is not loaded) - what copying a fluid needs, where {@link #fluidAt} only
     * names it. A source and its flow are different fluids ({@code Water_Source} and
     * {@code Water}); the level is the server's, kept as it sent it.
     */
    default FluidCell fluidCellAt(int x, int y, int z) {
        return null;
    }

    /**
     * The id this connection numbers a fluid by, from its name ({@code "Water_Source"}), or
     * {@code -1} when the catalog has no such fluid (or has not arrived yet).
     */
    default int fluidId(String name) {
        return -1;
    }

    /** The local player, or empty until a client session is live. */
    Optional<Player> player();
}
