package meridian.core.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Takes the player somewhere: finds a way and goes it, move by move, through core's
 * {@link Movement} - walking, jumping up, pulling up onto ledges, dropping down, leaping gaps,
 * swimming, climbing ladders and vines, flying where the player may.
 *
 * <p>A way is found over what core knows of the world. Where the world is not sent yet, the
 * navigator walks as far towards the goal as it can see and looks again from there; where a move
 * turns out blocked (the world changed), it looks again from where the player stands. It gives up
 * when no way is left, or after too many tries.
 *
 * <p>One trip at a time: a new {@link #goTo} (or {@link #chase}) ends the one under way - unless an
 * urgent hold ({@link Movement#takeFirst}) holds that one back: then it waits, and goes on after.
 * The service is core's; a module takes it from the service registry.
 */
public interface Navigator {

    /**
     * Whether the player can be moved here: a headless one, or a game's player, whose body core
     * takes from the game for a trip ({@link Movement#take}).
     */
    boolean available();

    /**
     * Goes to {@code goal}; completes when there, or when no way is left.
     *
     * <p>A game's player's body is held for the trip - a hold of the trip's own
     * ({@link Movement#take}), given back at its end, so a module that holds it too keeps it. The
     * game taking it back (a key that moves the player, the server's teleport, a death) ends the
     * trip {@code INTERRUPTED}.
     *
     * <p>Cancelling the returned future ends this trip, and only this one; {@link #cancel} ends every
     * trip, those held back by an urgent hold too ({@link Movement#takeFirst}). A trip begun while
     * one held back waits does not end it: that one goes on once the urgent hold is given back.
     */
    CompletableFuture<NavResult> goTo(Goal goal);

    /**
     * Goes after something that moves - a mob, a player - to within {@code within} of it: within that
     * many blocks of where {@code target} says it is, across, and no more than {@code within} (at
     * least 1.5) up or down. The way is found to where it is, and found again whenever it has moved
     * off the place the way was found to; the trip is done as soon as the body is near enough,
     * wherever along the way that is. The body is held for it as for {@link #goTo}.
     *
     * <p>The trip goes on as long as the target moves on, until it is caught up with, no way is left
     * to it ({@code NO_PATH}), or {@code target} has no place for it any more - gone from sight, dead
     * ({@code INTERRUPTED}). Cancelling the returned future ends this trip only, as it does
     * {@link #goTo}'s.
     *
     * <p>A core too old to chase goes once to where the target is now.
     *
     * @param target where the target is now, asked again as the trip goes; empty once it is gone
     * @param within how near is near enough, in blocks
     */
    default CompletableFuture<NavResult> chase(java.util.function.Supplier<java.util.Optional<Vec3>> target,
                                               double within) {
        java.util.Optional<Vec3> at = target == null ? java.util.Optional.empty() : target.get();
        if (at.isEmpty()) {
            return CompletableFuture.completedFuture(new NavResult(NavResult.Status.INTERRUPTED, null, 0, 0));
        }
        Vec3 p = at.get();
        return goTo(Goal.near(new BlockPos((int) Math.floor(p.x()), (int) Math.floor(p.y() + 1e-3),
                (int) Math.floor(p.z())), within));
    }

    /** The way to {@code goal} as it is found now, without moving - for a look before going. */
    List<Step> plan(Goal goal);

    /** Ends the trip under way ({@code INTERRUPTED}). */
    void cancel();

    /**
     * One move of a way: of a kind, onto a cell, the feet at that height, at a gait - through the
     * door at {@code door} when it is not {@code null}, which is opened first.
     */
    record Step(Movement.Kind kind, int x, int y, int z, double feet, Movement.Gait gait, BlockPos door) {}
}
