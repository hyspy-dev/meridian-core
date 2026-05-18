package meridian.core.api;

import java.util.concurrent.CompletableFuture;

/**
 * The local player — position and hotbar state, plus the hotbar-switch action.
 * Obtained from {@link World#player()}.
 */
public interface Player {

    /** The player's current position. */
    Vec3 position();

    /** The id of the item in the active hotbar slot, or {@code null} for an empty hand. */
    String heldItem();

    /** The active hotbar slot index. */
    int activeHotbarSlot();

    /**
     * The first hotbar slot holding an item whose id contains {@code substring},
     * or {@code -1} if none — e.g. {@code hotbarSlotOf("Seed")}.
     */
    int hotbarSlotOf(String substring);

    /**
     * Switches the active hotbar slot, forging a {@code SwapFrom} interaction —
     * the same one the client sends when the player scrolls the hotbar. The
     * server runs interactions against the item physically in the active slot,
     * so this is the honest way to equip a different item before an action.
     *
     * <p>Asynchronous, like every forge — see {@link Block}. The returned future
     * completes once the switch chain has played out, so an action that needs
     * the new item can be chained with {@code .thenRun(...)}.
     *
     * @return a future completing when the switch chain has played out
     */
    CompletableFuture<Void> selectHotbarSlot(int slot);
}
