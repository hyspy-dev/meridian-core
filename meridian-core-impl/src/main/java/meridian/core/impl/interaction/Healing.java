package meridian.core.impl.interaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import meridian.core.api.InteractionControl.Heal;
import meridian.protocol.EntityEffect;
import meridian.protocol.ValueType;

/**
 * What the effects on the player do for its health, as the server ticks them
 * ({@code ActiveEntityEffect}): an effect with a {@code damageCalculatorCooldown} changes the stat
 * once each cooldown for as long as it lasts; one without changes it once, on the tick it is added.
 * Laid over one already on, an effect follows its {@code OverlapBehavior}: an extension adds its
 * duration, an overwrite starts it over (and a one-off change does not land again), an ignore does
 * nothing.
 */
final class Healing {

    private Healing() {
    }

    /** What the effects on now - each with the seconds it has left - will still give the health. */
    static Heal underWay(Map<Integer, Double> left, IntFunction<EntityEffect> effects, int health, double max) {
        double over = 0;
        double seconds = 0;
        for (Map.Entry<Integer, Double> on : left.entrySet()) {
            EntityEffect e = effects.apply(on.getKey());
            double gives = slowHeal(e, on.getValue(), since(e, on.getValue()), health, max);
            if (gives > 0) {
                over += gives;
                seconds = Math.max(seconds, on.getValue());
            }
        }
        return over > 0 ? new Heal(0, over, seconds) : Heal.NONE;
    }

    /** A use's chain played out as a prediction to its end (or {@code mostTicks}): the effects as it left them. */
    static WalkEffects play(CompiledInteraction root, InteractionContext ctx, InteractionSimulator.RootResolver resolver,
                            int mostTicks) {
        // What the server alone decides (the bite taken from the stack) is taken as done: no server here.
        // A walk that only foretells: the cooldowns it would start stay in a copy of its own.
        InteractionContext own = ctx.withEffects(ctx.effects().withCooldowns(ctx.effects().cooldowns().copy()));
        ChainWalk walk = new ChainWalk(root, own, resolver, Map.of()).presumeServer();
        for (int t = 0; t < mostTicks && walk.tick() != null; t++) {
            // on to its end
        }
        return walk.effects();
    }

    /**
     * What a use adds: the {@code changes} it makes to the player's effects, in order, laid over
     * those on now ({@code left}, seconds left each), less what was coming anyway.
     *
     * @param changed health the chain's own stat changes give at once ({@code ChangeStatInteraction})
     * @param missing how far the health is under its max now: what is given at once is no more than that
     *                (the server clamps the stat) - a pie at full health gives nothing at once
     */
    static Heal gain(List<WalkEffects.Change> changes, Map<Integer, Double> left, IntFunction<EntityEffect> effects,
                     int health, double max, double changed, double missing) {
        // Each effect's time left and time into its cycle: an overwrite or an extension starts the
        // time left over, not the cycle (ActiveEntityEffect keeps its sinceLastDamage).
        Map<Integer, double[]> then = new HashMap<>();
        left.forEach((id, l) -> then.put(id, new double[] {l, since(effects.apply(id), l)}));
        double now = changed;
        double seconds = 0;
        for (WalkEffects.Change c : changes) {
            if (!c.applied()) {
                then.remove(c.effect());
                continue;
            }
            EntityEffect e = effects.apply(c.effect());
            if (e == null) {
                continue;
            }
            double[] on = then.get(c.effect());
            if (e.damageCalculatorCooldown <= 0) {
                if (on == null) {
                    now += change(e, health, max);
                }
                then.put(c.effect(), new double[] {e.duration, 0});
                continue;
            }
            double duration = e.infinite ? Double.POSITIVE_INFINITY : e.duration;
            double[] next = on == null ? new double[] {duration, 0} : switch (e.overlapBehavior) {
                case Extend -> new double[] {on[0] + duration, on[1]};
                case Overwrite -> new double[] {duration, on[1]};
                case Ignore -> on;
            };
            then.put(c.effect(), next);
            if (slowHeal(e, next[0], next[1], health, max) > 0) {
                seconds = Math.max(seconds, next[0]);
            }
        }
        double before = underWay(left, effects, health, max).overTime();
        double after = 0;
        for (Map.Entry<Integer, double[]> on : then.entrySet()) {
            after += slowHeal(effects.apply(on.getKey()), on.getValue()[0], on.getValue()[1], health, max);
        }
        return new Heal(Math.min(now, Math.max(0, missing)), Math.max(0, after - before), seconds);
    }

    /**
     * What an effect still gives the health over the {@code left} seconds it lasts, {@code since}
     * seconds into its cycle: its change each time a cycle ({@code damageCalculatorCooldown}) runs
     * out; 0 for one that does not heal over time, and for one without an end (no amount can be told).
     */
    static double slowHeal(EntityEffect e, double left, double since, int health, double max) {
        if (e == null || e.damageCalculatorCooldown <= 0 || Double.isInfinite(left) || left <= 0) {
            return 0;
        }
        return change(e, health, max) * Math.floor((since + left) / e.damageCalculatorCooldown + 1e-6);
    }

    /** How far into its cycle an effect is with {@code left} seconds of its duration left - as if put on once. */
    static double since(EntityEffect e, double left) {
        if (e == null || e.damageCalculatorCooldown <= 0 || Double.isInfinite(left) || left >= e.duration) {
            return 0;
        }
        return (e.duration - left) % e.damageCalculatorCooldown;
    }

    /** One change of the health by the effect, in points; 0 for none or a loss. */
    private static double change(EntityEffect e, int health, double max) {
        Float v = e.statModifiers == null ? null : e.statModifiers.get(health);
        if (v == null || v <= 0) {
            return 0;
        }
        return e.valueType == ValueType.Percent ? v / 100.0 * max : v;
    }
}
