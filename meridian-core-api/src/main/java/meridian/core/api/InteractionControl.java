package meridian.core.api;

import java.time.Duration;
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
 * <p><b>Forges are played as the client plays them.</b> A chain goes out a
 * packet per tick - a watering charge plays out over many - and core follows
 * the server while it does: an operation only the server decides waits for the
 * server's word, and where the server ran an operation otherwise than core
 * guessed, core goes back there and replays the rest the server's way.
 *
 * <p><b>Forges are asynchronous.</b> Core serializes all forges through one
 * queue so concurrent callers never clobber each other; the next chain starts a
 * tick after the one before has been played, as the client's would. Every forge
 * method returns a {@link CompletableFuture} of the {@link InteractionOutcome} -
 * how the server ran <em>that</em> chain - once the server has said so, so the
 * caller can act on it with {@code .thenAccept(...)} / {@code .thenCompose(...)}:
 * retry, undo its own plans, try something else. The queue only serializes - it
 * adds no padding, so queued forges run back-to-back at the tick rate; any
 * human-like spacing is the caller's choice. The future always completes
 * normally - a forge that could not run (no session, no root) completes with
 * {@link InteractionOutcome.Status#NOT_SENT}. Callbacks run on core's scheduler
 * thread.
 *
 * <p>All calls are no-ops while {@link #available()} is {@code false}; the
 * returned future still completes, {@code NOT_SENT}.
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
     * @return how the server ran the chain
     */
    CompletableFuture<InteractionOutcome> useOnBlock(BlockPos pos);

    /**
     * Forges a {@code Primary} (left-click) interaction on {@code pos} — a
     * single hit. The held item acts as the tool; the server applies the
     * damage, so a block may need several hits to break.
     *
     * @return how the server ran the chain
     */
    CompletableFuture<InteractionOutcome> hitBlock(BlockPos pos);

    /**
     * Forges a seed-planting interaction on {@code pos} (tilled soil); the crop
     * block lands at {@code y + 1}. The held item must be the seed.
     *
     * @return how the server ran the chain
     */
    CompletableFuture<InteractionOutcome> plantOnBlock(BlockPos pos);

    /**
     * Forges a block placement on the top face of {@code pos} (lands at
     * {@code y + 1}). Shorthand for {@link #placeOnBlock(BlockPos, Face)} with
     * {@link Face#UP}.
     *
     * @return how the server ran the chain
     */
    CompletableFuture<InteractionOutcome> placeOnBlock(BlockPos pos);

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
     * @return how the server ran the chain
     */
    CompletableFuture<InteractionOutcome> placeOnBlock(BlockPos target, Face face);

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
     * @return how the server ran the chain
     */
    CompletableFuture<InteractionOutcome> placeOnBlock(BlockPos target, Face face, Face orient);

    /**
     * Forges a watering-can interaction on {@code pos}. The held item must be
     * the can.
     *
     * @return how the server ran the chain
     */
    CompletableFuture<InteractionOutcome> waterBlock(BlockPos pos);

    /**
     * Forges a hotbar slot switch — a {@code SwapFrom} / {@code ChangeActiveSlot}
     * interaction, the same one the client sends when the player scrolls the
     * hotbar. The server validates interactions against the item physically in
     * the active slot, so this is the only honest way to "equip" a different
     * item before forging an interaction that needs it.
     *
     * @return how the server ran the switch; {@code FINISHED} at once when the slot is active already
     */
    CompletableFuture<InteractionOutcome> switchHotbarSlot(int slot);

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
     * @return how the server ran the swing; its {@code forks} are the blocks it broke
     */
    CompletableFuture<InteractionOutcome> digSwing(BlockPos aim);

    /**
     * Forges one attack on an entity: the held item's {@code Primary} swing, reporting the
     * entity as what it hit. The server takes a player's entity hits from the client and runs
     * the item's hit (its damage, knockback, effects) on the entity - as when the player clicks
     * it. The damage lands only within the held item's reach (plus a little), from the eyes.
     *
     * @param entityId the entity's network id ({@link EntityTracker})
     * @return how the server ran the swing; its {@code forks} are the hits it landed. An entity
     *         core does not see sends nothing
     */
    default CompletableFuture<InteractionOutcome> hitEntity(int entityId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("hitEntity"));
    }

    /**
     * Forges a use ({@code Use}, the F key) on an entity - what that entity does when used:
     * an NPC's talk, a mount, a tame. The server runs the entity's own interaction for the use,
     * and only within the player's reach.
     *
     * @param entityId the entity's network id ({@link EntityTracker})
     * @return how the server ran the use
     */
    default CompletableFuture<InteractionOutcome> useOnEntity(int entityId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("useOnEntity"));
    }

    /**
     * Forges the held item's {@code Secondary} use aimed at nothing - eating, drinking, a
     * thrown or charged item: what the right mouse button does in the open air.
     *
     * @return how the server ran the use
     */
    default CompletableFuture<InteractionOutcome> useItem() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("useItem"));
    }

    /**
     * {@link #useItem()} with the button held for {@code hold}, then let go. A charge reached while
     * it is held - a bow's guard, a thrown item's wind-up - runs until it is let go and takes the
     * tier its time reached; {@link Duration#ZERO} is a tap. Without a hold, a forge holds each
     * charge just long enough for its first tier.
     *
     * @return how the server ran the use
     */
    default CompletableFuture<InteractionOutcome> useItem(Duration hold) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("useItem(hold)"));
    }

    /**
     * Forges the held item's {@code Primary} swing aimed at nothing - a swing in the open air.
     *
     * @return how the server ran the swing
     */
    /**
     * A dodge ({@code InteractionType.Dodge}): to the side the movement keys are held - left or
     * right - a quick push of the body, with the stamina it costs. Only where the server binds a
     * dodge to the player or the held item; the vanilla game binds none, and the server cancels it.
     */
    default CompletableFuture<InteractionOutcome> dodge() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("dodge"));
    }

    default CompletableFuture<InteractionOutcome> swing() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("swing"));
    }

    /**
     * {@link #swing()} with the button held for {@code hold}, then let go - a bow's draw: held past
     * its first tier (0.1 s) it shoots, the stronger the longer. {@link Duration#ZERO} is a tap,
     * which for a bow nocks an arrow and puts it back.
     *
     * @return how the server ran the swing
     */
    default CompletableFuture<InteractionOutcome> swing(Duration hold) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("swing(hold)"));
    }

    /** {@code true} once a client session is live and packets can be sent. */
    boolean available();
}
