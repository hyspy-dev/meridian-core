package meridian.core.api;

import java.util.Optional;

/**
 * Forges block interactions — a meridian-core Layer-1 service.
 *
 * <p>Each method builds and sends a {@code SyncInteractionChains} packet the
 * server accepts as if the player performed the action: the registry resolves
 * interaction ids, the VM produces the operation data, the inventory tracker
 * supplies the held-item state. A Layer-2 consumer just names a block.
 *
 * <p>All calls are no-ops while {@link #available()} is {@code false}.
 */
public interface InteractionControl {

    /**
     * The block the player is currently looking at, taken from observed
     * {@code MouseInteraction} traffic. Empty until the client reports one.
     */
    Optional<BlockPos> targetedBlock();

    /**
     * Forges a {@code Use} interaction on {@code pos} — e.g. harvesting a ripe
     * crop. The server resolves the interaction from the target block.
     */
    void useOnBlock(BlockPos pos);

    /**
     * Forges a seed-planting interaction on {@code pos} (tilled soil); the crop
     * block lands at {@code y + 1}. The held item must be the seed.
     */
    void plantOnBlock(BlockPos pos);

    /** Forges a watering-can interaction on {@code pos}. The held item must be the can. */
    void waterBlock(BlockPos pos);

    /** {@code true} once a client session is live and packets can be sent. */
    boolean available();
}
