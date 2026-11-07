package meridian.core.api;

import java.util.concurrent.CompletableFuture;

/**
 * A single world block — its position, its type, and the interactions that can
 * be forged on it. Obtained from {@link World#blockAt}.
 *
 * <p>The action methods forge the corresponding interaction chain as if the
 * player performed it. They require the right held item (a seed for
 * {@link #plant()}, a watering can for {@link #water()}) — switching to it is
 * the caller's job ({@link Player#selectHotbarSlot}).
 *
 * <p><b>The action methods are asynchronous.</b> A forge plays out over server
 * ticks and core serializes all forges through one queue; each method returns a
 * {@link CompletableFuture} that completes when that chain has finished, so
 * follow-up work can be sequenced with {@code .thenRun(...)}. See
 * {@link InteractionControl} for the queue's semantics.
 */
public interface Block {

    /** World X coordinate. */
    int x();

    /** World Y coordinate. */
    int y();

    /** World Z coordinate. */
    int z();

    /** The block type, or {@code null} if the chunk / block catalog is not loaded. */
    BlockView type();

    /**
     * Which way this block faces, as the server stores it: an index 0..63 of
     * {@code roll * 16 + pitch * 4 + yaw}, each a quarter turn ({@code 0} none, {@code 1} 90°,
     * {@code 2} 180°, {@code 3} 270°). {@code 0} is unturned - also what a block that cannot turn,
     * or one whose chunk is not loaded, reports. The prefab format stores the same number.
     */
    int rotation();

    /** Whether this block is air (or its chunk is not loaded). */
    boolean isAir();

    /**
     * Whether this cell is the block's own, or one a bigger block only reaches into.
     *
     * <p>A block wider than one cell - a door, a bed, a two-cell chest - stands on one cell and
     * fills the others: each of those reports the same block type, plus this offset back to the
     * cell it stands on. {@code 0} means the cell is the block's own. Otherwise the offset is the
     * server's packing, five signed bits an axis: {@code x | z << 5 | y << 10}, and the block's
     * own cell is this one minus it (see {@link #base()}).
     *
     * <p>Whoever copies blocks keeps only the own cells, as the server does: placing the block on
     * its own cell brings the filled ones back, and placing it on a filled one too gives two.
     * The prefab format stores the same number, under {@code filler}.
     *
     * @return the packed offset, or {@code 0} for a block's own cell, air, or a core too old to know
     */
    default int filler() {
        return 0;
    }

    /** Whether this cell is only filled by a bigger block whose own cell is elsewhere. */
    default boolean isFiller() {
        return filler() != 0;
    }

    /** The cell this block stands on: this one, unless it {@linkplain #isFiller() only fills it}. */
    default BlockPos base() {
        int f = filler();
        return new BlockPos(x() - fillerAxis(f), y() - fillerAxis(f >> 10), z() - fillerAxis(f >> 5));
    }

    /** One axis of a packed filler offset: the low five bits, signed. */
    private static int fillerAxis(int bits) {
        int v = bits & 31;
        return (v & 16) != 0 ? v - 32 : v;
    }

    /**
     * Forges a {@code Use} interaction on this block — e.g. harvesting a crop.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> use();

    /**
     * Forges a {@code Primary} (left-click) interaction on this block — a single
     * hit. The held item acts as the tool; the server applies the damage, so a
     * harder block or a weaker tool may need several hits to break.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> hit();

    /**
     * Forges a seed-planting interaction; the crop block lands one above.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> plant();

    /**
     * Forges a block placement on this block's top face (one above). Shorthand
     * for {@link #place(Face)} with {@link Face#UP}.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> place();

    /**
     * Forges a block placement against this block's {@code face}; the held
     * building block lands in the adjacent cell. Any face — build sideways
     * (bridge) or below, not just on top.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> place(Face face);

    /**
     * Forges a block placement against this block's {@code against} face (where
     * the held block lands — the adjacent cell), but oriented as if it had been
     * placed against {@code orient}. Because the server stores a placed block's
     * rotation verbatim, the facing is independent of the attach side: attach to
     * whatever neighbour exists, yet pin a rotatable block's orientation.
     * {@link #place(Face)} is this with {@code orient == against}.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> place(Face against, Face orient);

    /**
     * Forges a watering-can interaction on this block.
     *
     * @return a future completing when the forged charge has played out
     */
    CompletableFuture<Void> water();
}
