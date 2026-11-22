package meridian.core.impl.interaction;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The player's interaction cooldowns, kept the way the server keeps them ({@code CooldownHandler}):
 * a {@code TriggerCooldownInteraction} (or {@code ResetCooldownInteraction}) starts one by its id -
 * a health potion's {@code Potion_Consume_Health}, 15 s - and a {@code CooldownConditionInteraction}
 * fails while it runs. A cooldown may have charges: each use takes one, and one comes back each
 * charge time.
 *
 * <p>Fed by the chains core walks for the player: the server runs the same ops on the same chains,
 * so the two count alike. Time runs on a clock, not on ticks - each read catches up.
 */
final class Cooldowns {

    /** The server's defaults where a trigger names no time or charges ({@code InteractionTypeUtils}, {@code InteractionManager}). */
    static final float DEFAULT_COOLDOWN = 0.35f;
    static final float[] DEFAULT_CHARGE_TIMES = {0f};

    private final Map<String, Cooldown> cooldowns = new HashMap<>();
    private final LongSupplier nanos;
    private long last;

    Cooldowns(LongSupplier nanos) {
        this.nanos = nanos;
        this.last = nanos.getAsLong();
    }

    /** A copy to play a walk on that only foretells: what it starts stays in the copy. */
    synchronized Cooldowns copy() {
        tick();
        Cooldowns c = new Cooldowns(nanos);
        cooldowns.forEach((id, cd) -> c.cooldowns.put(id, cd.copy()));
        return c;
    }

    /**
     * {@code TriggerCooldownInteraction.resetCooldown}: the cooldown {@code id} started - with its
     * own time and charges, else those it had, else the defaults - and a charge taken.
     */
    synchronized void trigger(String id, float time, float[] chargeTimes, boolean interruptRecharge) {
        if (id == null || id.isEmpty()) {
            return;     // the server falls back on the chain's root here; core walks name theirs
        }
        tick();
        float t = DEFAULT_COOLDOWN;
        float[] charges = DEFAULT_CHARGE_TIMES;
        boolean interrupt = interruptRecharge;
        Cooldown had = cooldowns.get(id);
        if (had != null) {
            t = had.cooldownMax;
            charges = had.charges;
            interrupt = interrupt || had.interruptRecharge;
        }
        if (time > 0) {
            t = time;
        }
        if (chargeTimes != null && chargeTimes.length > 0) {
            charges = chargeTimes;
        }
        boolean interruptFinal = interrupt;
        float[] chargesFinal = charges;
        float timeFinal = t;
        Cooldown cd = cooldowns.computeIfAbsent(id, k -> new Cooldown(timeFinal, chargesFinal, interruptFinal));
        cd.setCooldownMax(t);
        cd.setCharges(charges);
        cd.deductCharge();
    }

    /** {@code CooldownConditionInteraction.checkCooldown}: whether {@code id} is cooling down now. */
    synchronized boolean onCooldown(String id) {
        tick();
        Cooldown cd = id == null ? null : cooldowns.get(id);
        return cd != null && cd.hasCooldown();
    }

    /** Seconds until {@code id} is ready again; 0 when it is. */
    synchronized double left(String id) {
        tick();
        Cooldown cd = id == null ? null : cooldowns.get(id);
        return cd == null || !cd.hasCooldown() ? 0 : Math.max(0, cd.remaining);
    }

    private void tick() {
        long now = nanos.getAsLong();
        float dt = (now - last) / 1e9f;
        last = now;
        if (dt > 0) {
            cooldowns.values().removeIf(cd -> cd.tick(dt));
        }
    }

    /** {@code CooldownHandler.Cooldown}. */
    private static final class Cooldown {
        float cooldownMax;
        float[] charges;
        float remaining;
        float chargeTimer;
        int chargeCount;
        final boolean interruptRecharge;

        Cooldown(float cooldownMax, float[] charges, boolean interruptRecharge) {
            setCooldownMax(cooldownMax);
            setCharges(charges);
            chargeCount = charges.length;
            this.interruptRecharge = interruptRecharge;
        }

        Cooldown copy() {
            Cooldown c = new Cooldown(cooldownMax, charges, interruptRecharge);
            c.remaining = remaining;
            c.chargeTimer = chargeTimer;
            c.chargeCount = chargeCount;
            return c;
        }

        void setCooldownMax(float max) {
            cooldownMax = max;
            if (remaining > max) {
                remaining = max;
            }
        }

        void setCharges(float[] times) {
            charges = times;
            if (chargeCount > times.length) {
                chargeCount = times.length;
            }
        }

        /** {@code hasCooldown(false)}: no charge to use, or the time not run out. */
        boolean hasCooldown() {
            return !(remaining <= 0 && chargeCount > 0);
        }

        void deductCharge() {
            if (chargeCount > 0) {
                chargeCount--;
            }
            if (interruptRecharge) {
                chargeTimer = 0;
            }
            remaining = cooldownMax;
        }

        /** One step of time; {@code true} once it is over and can go. */
        boolean tick(float dt) {
            // The server steps a tick at a time; a read here may catch up many: a charge back each charge time.
            float t = dt;
            while (chargeCount < charges.length) {
                float need = charges[chargeCount] - chargeTimer;
                if (t < need) {
                    chargeTimer += t;
                    break;
                }
                t -= need;
                chargeCount++;
                chargeTimer = 0;
            }
            remaining -= dt;
            return (chargeCount >= charges.length || charges.length <= 1) && remaining <= 0;
        }
    }
}
