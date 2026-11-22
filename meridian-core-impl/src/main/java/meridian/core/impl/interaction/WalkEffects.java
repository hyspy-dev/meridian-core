package meridian.core.impl.interaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    /** What the walk did to the player's own effects, in order: what a use adds up to. */
    private final List<Change> changes;
    /**
     * The player's cooldowns: shared by every copy of a walk's effects - what a walk starts, the
     * server starts too - unless a walk that only foretells takes its own ({@link #withCooldowns}).
     */
    private final Cooldowns cooldowns;

    /** An effect the walk applied to the player, or cleared off it. */
    record Change(int effect, boolean applied) {
    }

    WalkEffects(Set<Integer> self, Set<Integer> target, StatSheet stats) {
        this(self, target, stats, List.of(), new Cooldowns(System::nanoTime));
    }

    private WalkEffects(Set<Integer> self, Set<Integer> target, StatSheet stats, List<Change> changes,
                        Cooldowns cooldowns) {
        this.self = new HashSet<>(self);
        this.target = target == null ? null : new HashSet<>(target);
        this.stats = stats.copy();
        this.changes = new ArrayList<>(changes);
        this.cooldowns = cooldowns;
    }

    /** A copy for one walk to change. */
    WalkEffects copy() {
        return new WalkEffects(self, target, stats, changes, cooldowns);
    }

    /** The same, with a target entity having {@code effects}. */
    WalkEffects withTarget(Set<Integer> effects) {
        return new WalkEffects(self, effects == null ? Set.of() : effects, stats, changes, cooldowns);
    }

    /** The same, keeping {@code cooldowns}. */
    WalkEffects withCooldowns(Cooldowns cooldowns) {
        return new WalkEffects(self, target, stats, changes, cooldowns);
    }

    /** The player's cooldowns, as this walk sees them. */
    Cooldowns cooldowns() {
        return cooldowns;
    }

    /** {@code effect} applied to {@code who} ({@code ApplyEffectInteraction}). */
    void apply(InteractionTarget who, int effect) {
        change(who, effect, true);
    }

    /** {@code effect} cleared off {@code who} ({@code ClearEntityEffectInteraction}). */
    void clear(InteractionTarget who, int effect) {
        change(who, effect, false);
    }

    private void change(InteractionTarget who, int effect, boolean applied) {
        Set<Integer> on = of(who);
        if (on == null) {
            return;
        }
        if (applied) {
            on.add(effect);
        } else {
            on.remove(effect);
        }
        if (on == self) {
            changes.add(new Change(effect, applied));
        }
    }

    /** The player's own effects the walk applied and cleared, in order. */
    List<Change> changes() {
        return List.copyOf(changes);
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
