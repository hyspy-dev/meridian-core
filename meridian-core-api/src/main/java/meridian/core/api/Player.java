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
     * Drops the item in the active hotbar slot by forging a C2S
     * {@code DropItemStack} for that slot. {@code quantity} items are thrown;
     * {@code quantity = 0} is the pre-51 phantom-drop vector (the server kept the
     * slot but spawned a real-item entity with count 0).
     */
    void dropHeld(int quantity);

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

    /**
     * Teleports the player to {@code position} — a real teleport the player sees.
     *
     * <p>Because Hytale movement is client-authoritative, two packets are sent:
     * a {@code ClientTeleport} <b>to the client</b> (the same packet the server
     * uses for {@code /tp} — moves the player's own view) and a forged
     * {@code ClientMovement} carrying the new absolute <b>to the server</b> (a
     * server-initiated teleport only makes the client send back a
     * {@code teleportAck}, not a fresh absolute, so without this the server keeps
     * the old position and snaps the client back). The server accepts any finite
     * absolute with no distance check ({@code ValidateUtil.isSafePosition} =
     * finite-only; a large jump just warns and resets velocity), so client and
     * server agree with no roll-back.
     *
     * <p>Needs an established session (the player must be in a world); a call
     * before then is a logged no-op.
     */
    void teleport(Vec3 position);

    /**
     * <b>Server-side offset</b> (advanced). Continuously shifts the player's
     * <i>reported</i> position so the server and other players see them at, and
     * moving relative to, {@code target} — while the player's own client stays
     * where it is. The constant offset ({@code target} − the player's real
     * position) is captured on the next movement packet and added to every
     * outgoing absolute position until {@link #clearHold}.
     *
     * <p>For an ordinary teleport the player actually moves to, use
     * {@link #teleport} instead.
     */
    void holdPosition(Vec3 target);

    /** Stops a {@link #holdPosition} hold; the player's real position is reported again. */
    void clearHold();
}
