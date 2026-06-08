package meridian.core.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Forges block interactions — a meridian-core Layer-1 service.
 *
 * <p>Each method builds and sends a {@code SyncInteractionChains} packet the
 * server accepts as if the player performed the action: the registry resolves
 * interaction ids, the VM produces the operation data, the inventory tracker
 * supplies the held-item state. A Layer-2 consumer just names a block.
 *
 * <p><b>Forges are asynchronous.</b> A forge does not run instantly — a
 * watering charge plays out over many server ticks — and core serializes all
 * forges through one queue so concurrent callers never clobber each other.
 * Every forge method therefore returns a {@link CompletableFuture} that
 * completes once <em>that</em> chain has fully played out, so the caller can
 * sequence follow-up work with {@code .thenRun(...)} / {@code .thenCompose(...)}.
 * The queue only serializes — it adds no padding, so queued forges run
 * back-to-back at the tick rate; any human-like spacing is the caller's choice.
 * The future always completes normally — a forge that could not run (no
 * session, no root) simply completes with no effect. Callbacks run on core's
 * scheduler thread.
 *
 * <p>All calls are no-ops while {@link #available()} is {@code false}; the
 * returned future still completes.
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
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> useOnBlock(BlockPos pos);

    /**
     * Forges a {@code Primary} (left-click) interaction on {@code pos} — a
     * single hit. The held item acts as the tool; the server applies the
     * damage, so a block may need several hits to break.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> hitBlock(BlockPos pos);

    /**
     * Forges a seed-planting interaction on {@code pos} (tilled soil); the crop
     * block lands at {@code y + 1}. The held item must be the seed.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> plantOnBlock(BlockPos pos);

    /**
     * Forges a block placement on the top face of {@code pos} (lands at
     * {@code y + 1}). Shorthand for {@link #placeOnBlock(BlockPos, Face)} with
     * {@link Face#UP}.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> placeOnBlock(BlockPos pos);

    /**
     * Forges a block placement against {@code target}'s {@code face}: the held
     * building block lands in the adjacent cell {@code face.from(target)}. Same
     * {@code Secondary} / {@code PlaceBlockInteraction} mechanism as
     * {@link #plantOnBlock}, but any face — so you can build sideways (bridge)
     * or below, not just on top.
     *
     * <p>The server places the block at the resolved cell with only a reach
     * (distance) check — no adjacent-support requirement — so {@code target}
     * just needs to be a block you click and the destination cell within reach.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> placeOnBlock(BlockPos target, Face face);

    /**
     * Forges a block placement against {@code target}'s {@code face} (the
     * geometry — which neighbour the block clicks and so which cell it lands in),
     * but oriented as if it had been placed against {@code orient}.
     *
     * <p>The server stores a placed block's rotation verbatim, so orientation is
     * independent of the side the block attaches to. This lets you attach to
     * whatever solid neighbour is available (a block can only be placed against
     * an existing one) yet pin a rotatable block's facing to a fixed direction.
     * {@link #placeOnBlock(BlockPos, Face)} is this with {@code orient == face}.
     *
     * @return a future completing when the forged chain has played out
     */
    CompletableFuture<Void> placeOnBlock(BlockPos target, Face face, Face orient);

    /**
     * Forges a watering-can interaction on {@code pos}. The held item must be
     * the can.
     *
     * @return a future completing when the forged charge has played out
     */
    CompletableFuture<Void> waterBlock(BlockPos pos);

    /**
     * Forges a hotbar slot switch — a {@code SwapFrom} / {@code ChangeActiveSlot}
     * interaction, the same one the client sends when the player scrolls the
     * hotbar. The server validates interactions against the item physically in
     * the active slot, so this is the only honest way to "equip" a different
     * item before forging an interaction that needs it.
     *
     * @return a future completing when the switch chain has played out
     */
    CompletableFuture<Void> switchHotbarSlot(int slot);

    /**
     * Forges one native dig swing of the held tool, aimed at {@code aim}. Only the
     * main {@code Primary} chain is sent; the tool's {@code SelectInteraction} runs
     * <em>server-side</em>, so the server picks the swing area itself and spawns a
     * break fork per block, which core answers reactively — the swing clears the
     * tool's real area (a shovel's AoE), not a set of blocks the caller chose.
     * {@code aim} is just the swing's aim/reach point (a block in front of the
     * player). A tool without a {@code SelectInteraction} (e.g. a pickaxe) simply
     * breaks the aimed block.
     *
     * @return a future completing when the forged swing's main chain has played out
     */
    CompletableFuture<Void> digSwing(BlockPos aim);

    /** {@code true} once a client session is live and packets can be sent. */
    boolean available();
}
