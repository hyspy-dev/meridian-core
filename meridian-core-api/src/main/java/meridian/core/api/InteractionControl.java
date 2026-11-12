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
     * Forges a block placement against {@code target}'s {@code face}, the placed block carrying the
     * server's orientation number {@code rotation} (0 to 63: yaw, pitch and roll in quarter turns, as
     * a prefab stores it) - the server keeps a placed block's rotation as it is sent. A negative
     * {@code rotation} orients it as {@link #placeOnBlock(BlockPos, Face)} does.
     *
     * @return how the server ran the chain
     */
    default CompletableFuture<InteractionOutcome> placeOnBlock(BlockPos target, Face face, int rotation) {
        return placeOnBlock(target, face);
    }

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
     * Swings the held tool at {@code pos} until the block there is gone: a {@link #digSwing} at a
     * time, each once the server has said how the one before went, as a held button swings. Done
     * when core has been sent the cell holding something other than the block it started with -
     * air, or what the block breaks into.
     *
     * <p>Aiming is the caller's, as for every forge: the player within reach, and for a headless
     * one, turned to the block ({@link Movement#face(BlockPos)}). The block's damage wears off
     * between swings spaced far apart, so a hard block may take more than its count of hits.
     *
     * <p>Cancelling the returned future stops it: no swing goes out after the one under way.
     *
     * @param pos       the block to break
     * @param maxSwings the most swings to try
     * @return {@code true} once the block is broken; {@code false} when {@code maxSwings} did not
     *         break it, when nothing could be sent, or when the cell was not known or held no block
     *         to begin with - then nothing is sent
     */
    default CompletableFuture<Boolean> breakBlock(BlockPos pos, int maxSwings) {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * How far from the eyes a dig with this item breaks blocks. A tool whose dig is a sweep the
     * server makes itself - a shovel's, a short arc in front - breaks only what the sweep takes in,
     * as far as its far end; any other breaks the block it is aimed at, within the use distance of
     * the player's game mode. An empty or {@code null} item is the bare hand.
     *
     * @return the distance; empty when core does not know the item's swing
     */
    default java.util.OptionalDouble digReach(String itemId) {
        return java.util.OptionalDouble.empty();
    }

    /**
     * How far from the eyes to a block's middle a use with this item in hand reaches - opening a
     * chest, a door, an aimed dig: the item's use distance for the player's game mode, as the server
     * sends it (5 in Adventure unless the item says otherwise); in Creative at least the client's
     * creative reach (10 unless its settings say otherwise). The server allows 2 more before it
     * refuses. An empty or {@code null} item is the bare hand.
     *
     * @return the distance; empty when core cannot tell
     */
    default java.util.OptionalDouble useReach(String itemId) {
        return java.util.OptionalDouble.empty();
    }

    /**
     * Whether a dig with this item is a sweep the server makes - a shovel's arc, which breaks every
     * block in it that the item breaks, not only the one aimed at.
     */
    default boolean digSweeps(String itemId) {
        return false;
    }

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
     * A dodge ({@code InteractionType.Dodge}): to the side the movement keys are held - left or
     * right - a quick push of the body, with the stamina it costs. Only where the server binds a
     * dodge to the player or the held item; the vanilla game binds none, and the server cancels it.
     */
    default CompletableFuture<InteractionOutcome> dodge() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("dodge"));
    }

    /**
     * Forges the held item's {@code Primary} swing aimed at nothing - a swing in the open air.
     *
     * @return how the server ran the swing
     */
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

    /**
     * How far from the eyes a swing of this item hits an entity: the far end of the shape its
     * {@code Primary} swing sweeps (the server's selector - a sword's 2.75), the farthest of its
     * swings, followed through the item's own interaction variables. A player's swing lands within
     * it; the server takes a hit up to the item's use distance and 2 more, but a hit from beyond
     * the swing is no player's. An empty or {@code null} item is the bare hand.
     *
     * @return the reach; empty for an item whose swing hits no entity (a bow, a block), or one core
     *         does not know
     */
    default java.util.OptionalDouble hitReach(String itemId) {
        return java.util.OptionalDouble.empty();
    }

    /**
     * Whether this item guards: its {@code Secondary}, held, raises a guard that takes or turns aside
     * a blow from the front (a sword's, a shield's, a spear's block) - a wield that changes the damage
     * taken. {@link #useItem(Duration)} holds it.
     */
    default boolean guards(String itemId) {
        return false;
    }

    /**
     * What a use of an item does for the player's health.
     *
     * @param now      health given at once, in points
     * @param overTime health given over the next {@code seconds}, in points
     * @param seconds  how long the slow part lasts; {@code 0} when there is none
     */
    record Heal(double now, double overTime, double seconds) {

        public double total() {
            return now + overTime;
        }
    }

    /**
     * What a use ({@code Secondary}) of this item does for the player's health, by the server's own
     * effects and stat changes for it - a potion's, a bread's - a percentage of the max counted by
     * the player's own max health.
     *
     * @return the heal; empty for an item whose use heals nothing, or one core does not know
     */
    default java.util.Optional<Heal> heal(String itemId) {
        return java.util.Optional.empty();
    }

    /**
     * Repairs an item as a player does with a repair kit: takes a kit from the hotbar (an item of
     * the {@code Repair_Kit} set), uses it - the server then shows its list of worn items - and
     * picks the most worn one with this id from that list; the server uses up one kit and gives the
     * item back its durability, under a lower maximum. The slot in hand before is taken again after.
     *
     * <p>A game's player sees the list come up and go again; for a headless one core answers the
     * list as a client does.
     *
     * @return whether the server repaired it; {@code false} with no kit in the hotbar, nothing of the
     *         item worn, or the server refusing it
     */
    default CompletableFuture<Boolean> repair(String itemId) {
        return CompletableFuture.completedFuture(false);
    }

    /** {@code true} once a client session is live and packets can be sent. */
    boolean available();
}
