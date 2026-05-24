package meridian.core.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The local player — position and hotbar state, plus the hotbar-switch action.
 * Obtained from {@link World#player()}.
 */
public interface Player {

    /** The player's current position. */
    Vec3 position();

    /**
     * Unit vector for the player's look direction in world space — derived
     * from the most recently observed {@code lookOrientation} (yaw + pitch
     * in radians). {@code null} until the client has sent at least one
     * {@code ClientMovement} carrying an orientation.
     *
     * <p>Pitch sign matches Hytale's convention: pitch &lt; 0 looks down,
     * pitch &gt; 0 looks up. Magnitude is always 1.
     */
    Vec3 lookDirection();

    /**
     * First non-air block hit by a ray from the player's eye along
     * {@link #lookDirection()} — the in-game crosshair, computed proxy-side.
     * Empty when the player has no observed position / orientation yet, or
     * when the ray exits range without striking anything.
     *
     * <p>Range defaults to Hytale's normal block-interact reach (12 blocks);
     * use the {@link #lookedAtBlock(double)} overload for a different cap.
     */
    Optional<BlockPos> lookedAtBlock();

    /** Range-bounded variant of {@link #lookedAtBlock()}. */
    Optional<BlockPos> lookedAtBlock(double maxRange);

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
