package meridian.core.api;

/**
 * A single world block — its position, its type, and the interactions that can
 * be forged on it. Obtained from {@link World#blockAt}.
 *
 * <p>The action methods forge the corresponding interaction chain as if the
 * player performed it. They require the right held item (a seed for
 * {@link #plant()}, a watering can for {@link #water()}) — switching to it is
 * the caller's job ({@link Player#selectHotbarSlot}).
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

    /** Forges a {@code Use} interaction on this block — e.g. harvesting a crop. */
    void use();

    /** Forges a seed-planting interaction; the crop block lands one above. */
    void plant();

    /** Forges a watering-can interaction on this block. */
    void water();
}
