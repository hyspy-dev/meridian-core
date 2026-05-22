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

    /** Whether this block is air (or its chunk is not loaded). */
    boolean isAir();

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
     * Forges a watering-can interaction on this block.
     *
     * @return a future completing when the forged charge has played out
     */
    CompletableFuture<Void> water();
}
