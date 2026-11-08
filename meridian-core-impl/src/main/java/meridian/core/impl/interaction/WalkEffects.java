package meridian.core.impl.interaction;

import java.util.HashSet;
import java.util.Set;
import meridian.protocol.InteractionTarget;

/**
 * The entity effects a walk sees - the player's own and its target's - as the walk changes them.
 *
 * <p>{@code EffectConditionInteraction} branches on effects the player or the target has; the
 * {@code ApplyEffectInteraction} and {@code ClearEntityEffectInteraction} ops before it in the
 * same walk add and take them away (a weapon's hit clears and sets its combo markers, then tests
 * them). So a walk starts from the effects core last saw and plays its own ops onto a copy.
 *
 * <p>The player's stats ride along the same way: {@code ChangeStatInteraction} spends and gives
 * on the walk's copy, {@code StatsConditionInteraction} tests it.
 *
 * <p>{@code Owner} is the player too: for a player's own chain the owning entity is the player.
 */
final class WalkEffects {

    /** No effects known, no target. */
    static final WalkEffects NONE = new WalkEffects(Set.of(), null, new StatSheet(StatSheet.newCatalog()));

    private final Set<Integer> self;
    /** {@code null} when the walk has no target entity. */
    private final Set<Integer> target;
    /** The player's own stats, the walk's copy. */
    private final StatSheet stats;

    WalkEffects(Set<Integer> self, Set<Integer> target, StatSheet stats) {
        this.self = new HashSet<>(self);
        this.target = target == null ? null : new HashSet<>(target);
        this.stats = stats.copy();
    }

    /** A copy for one walk to change. */
    WalkEffects copy() {
        return new WalkEffects(self, target, stats);
    }

    /** The same, with a target entity having {@code effects}. */
    WalkEffects withTarget(Set<Integer> effects) {
        return new WalkEffects(self, effects == null ? Set.of() : effects, stats);
    }

    /** The player's stats as the walk sees them. */
    StatSheet stats() {
        return stats;
    }

    /** The effects of {@code who}, or {@code null} when there is no such entity. */
    Set<Integer> of(InteractionTarget who) {
        return who == InteractionTarget.Target ? target : self;
    }
}
