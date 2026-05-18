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

    /**
     * Forges a hotbar slot switch — a {@code SwapFrom} / {@code ChangeActiveSlot}
     * interaction, the same one the client sends when the player scrolls the
     * hotbar. The server validates interactions against the item physically in
     * the active slot, so this is the only honest way to "equip" a different
     * item before forging an interaction that needs it.
     */
    void switchHotbarSlot(int slot);

    /**
     * Full planting cycle: finds seeds in the hotbar, switches to that slot,
     * plants on every tilled-soil block within {@code radius} of the player
     * that has space above, then switches the hotbar back. Returns the number
     * of blocks planted.
     */
    int plantNearby(int radius);

    /**
     * Forges a {@code Use} (harvest) on every crop within {@code radius} of the
     * player — a non-air block sitting on tilled soil. Returns the number of
     * blocks harvested. Growth stage is not checked.
     */
    int harvestNearby(int radius);

    /**
     * Waters every tilled-soil block within {@code radius} of the player:
     * switches to the watering can, replays the captured watering chain on each
     * block (paced, one after another), then switches the hotbar back. Requires
     * a watering can in the hotbar and one watering performed manually first
     * (the charge is replay-only). Returns the number of blocks watered.
     */
    int waterNearby(int radius);

    /** {@code true} once a client session is live and packets can be sent. */
    boolean available();
}
