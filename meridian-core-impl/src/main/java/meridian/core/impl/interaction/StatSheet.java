package meridian.core.impl.interaction;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import meridian.protocol.ChangeStatBehaviour;
import meridian.protocol.ChangeStatInteraction;
import meridian.protocol.EntityStatResetBehavior;
import meridian.protocol.EntityStatType;
import meridian.protocol.EntityStatUpdate;
import meridian.protocol.Modifier;
import meridian.protocol.ModifierTarget;
import meridian.protocol.StatsConditionInteraction;
import meridian.protocol.ValueType;

/**
 * An entity's stats - health, stamina, mana, a weapon's signature energy - as the server keeps
 * them: each a value between a min and a max, the bounds the stat type's own moved by the
 * modifiers on it.
 *
 * <p>The server sends changes, not values: {@code Init} with the value and modifiers, then
 * {@code Add} an amount, {@code Set} a value, {@code Min}/{@code Max} a cap or floor,
 * {@code Minimize}/{@code Maximize}/{@code Reset}, and modifiers put or removed - each to be
 * applied in turn, as the server's {@code EntityStatMap} did ({@code EntityStatValue}: the value
 * stays clamped to the bounds).
 *
 * <p>An interaction walk works on a {@link #copy()}: its {@code ChangeStatInteraction} ops spend
 * and give on the copy, and its {@code StatsConditionInteraction} ops test the copy, as on the
 * server the earlier ops of a chain change what the later ones see.
 */
public final class StatSheet {

    /** The stat catalog ({@code UpdateEntityStatTypes}), by stat index - shared by every sheet. */
    private final Map<Integer, EntityStatType> types;
    private final Map<Integer, Stat> stats = new HashMap<>();

    private static final class Stat {
        float value;
        final Map<String, Modifier> modifiers = new HashMap<>();
        float min;
        float max;

        Stat copy() {
            Stat s = new Stat();
            s.value = value;
            s.modifiers.putAll(modifiers);
            s.min = min;
            s.max = max;
            return s;
        }
    }

    public StatSheet(Map<Integer, EntityStatType> types) {
        this.types = types;
    }

    /** A new, empty catalog to share between sheets. */
    public static Map<Integer, EntityStatType> newCatalog() {
        return new ConcurrentHashMap<>();
    }

    /** One change the server sent for stat {@code index}. */
    public synchronized void apply(int index, EntityStatUpdate change) {
        if (change == null || change.op == null) {
            return;
        }
        if (change.op == meridian.protocol.EntityStatOp.Remove) {
            stats.remove(index);
            return;
        }
        Stat s = stats.computeIfAbsent(index, this::fresh);
        switch (change.op) {
            case Init -> {
                s.modifiers.clear();
                if (change.modifiers != null) {
                    change.modifiers.forEach((k, m) -> {
                        if (m != null) s.modifiers.put(k, m);
                    });
                }
                bounds(index, s);
                s.value = clamp(change.value, s);
            }
            case PutModifier -> {
                if (change.modifierKey != null && change.modifier != null) {
                    s.modifiers.put(change.modifierKey, change.modifier);
                }
                bounds(index, s);
            }
            case RemoveModifier -> {
                if (change.modifierKey != null) {
                    s.modifiers.remove(change.modifierKey);
                }
                bounds(index, s);
            }
            case Add -> s.value = clamp(s.value + change.value, s);
            case Set -> s.value = clamp(change.value, s);
            case Minimize -> s.value = s.min;
            case Maximize -> s.value = s.max;
            case Reset -> {
                EntityStatType type = types.get(index);
                boolean toMax = type != null && type.resetBehavior == EntityStatResetBehavior.MaxValue;
                s.value = toMax ? s.max : clamp(type != null ? type.value : s.value, s);
            }
            default -> { }
        }
    }

    /** A stat's value, or {@code null} when the entity has no such stat. */
    public synchronized Float value(int index) {
        Stat s = stats.get(index);
        return s == null ? null : s.value;
    }

    /** Every stat's value, by index. */
    public synchronized Map<Integer, Float> values() {
        Map<Integer, Float> out = new HashMap<>();
        stats.forEach((k, s) -> out.put(k, s.value));
        return out;
    }

    /** A copy for one interaction walk to spend and test on. */
    public synchronized StatSheet copy() {
        StatSheet c = new StatSheet(types);
        stats.forEach((k, s) -> c.stats.put(k, s.copy()));
        return c;
    }

    /**
     * {@code StatsConditionInteraction.canAfford}: every cost met - the stat's value (or its
     * percentage of its range) at least the cost, or below it for {@code lessThan}; a lenient
     * check lets a positive stat with an overdraw pool (min below 0) through. A stat the entity
     * lacks, or no costs at all, fails. (0.5.9 has no abilities, so no ability cost.)
     */
    synchronized boolean canAfford(StatsConditionInteraction c) {
        if (c.costs == null) {
            return false;
        }
        for (Map.Entry<Integer, Float> cost : c.costs.entrySet()) {
            Stat s = stats.get(cost.getKey());
            if (s == null || cost.getValue() == null) {
                return false;
            }
            float v = c.valueType == ValueType.Absolute ? s.value
                    : (s.min == s.max ? 0f : (s.value - s.min) / (s.max - s.min)) * 100f;
            if (c.lessThan) {
                if (v >= cost.getValue()) {
                    return false;
                }
            } else if (v < cost.getValue() && !(c.lenient && v > 0 && s.min < 0)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code ChangeStatInteraction} on the player's own stats ({@code processStatChanges}): a
     * percent amount is of the stat's range; then set, add, cap or floor.
     */
    synchronized void change(ChangeStatInteraction c) {
        if (c.statModifiers == null) {
            return;
        }
        for (Map.Entry<Integer, Float> e : c.statModifiers.entrySet()) {
            Stat s = stats.get(e.getKey());
            if (s == null || e.getValue() == null) {
                continue;
            }
            float amount = e.getValue();
            if (c.valueType == ValueType.Percent) {
                amount = amount * (s.max - s.min) / 100f;
            }
            ChangeStatBehaviour how = c.changeStatBehaviour != null ? c.changeStatBehaviour : ChangeStatBehaviour.Add;
            s.value = switch (how) {
                case Set -> clamp(amount, s);
                case Add -> clamp(s.value + amount, s);
            };
        }
    }

    // ------------------------------------------------------------------

    private Stat fresh(int index) {
        Stat s = new Stat();
        bounds(index, s);
        EntityStatType type = types.get(index);
        s.value = type != null ? clamp(type.value, s) : 0f;
        return s;
    }

    /** {@code computeModifiers}: the type's bounds, moved by the additive then multiplicative modifiers. */
    private void bounds(int index, Stat s) {
        EntityStatType type = types.get(index);
        float min = type != null ? type.min : 0f;
        float max = type != null ? type.max : Float.MAX_VALUE;
        for (ModifierTarget target : ModifierTarget.values()) {
            boolean hasAdd = false;
            boolean hasMul = false;
            float add = 0f;
            float mul = 0f;
            for (Modifier m : s.modifiers.values()) {
                if (m.target != target) continue;
                if (m.calculationType == meridian.protocol.CalculationType.Multiplicative) {
                    hasMul = true;
                    mul += m.amount;
                } else {
                    hasAdd = true;
                    add += m.amount;
                }
            }
            if (target == ModifierTarget.Min) {
                if (hasAdd) min = min + add;
                if (hasMul) min = min * mul;
            } else {
                if (hasAdd) max = max + add;
                if (hasMul) max = max * mul;
            }
        }
        s.min = min;
        s.max = max;
        s.value = clamp(s.value, s);
    }

    private static float clamp(float v, Stat s) {
        return Math.max(s.min, Math.min(s.max, v));
    }
}
