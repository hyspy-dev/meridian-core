package meridian.core.impl.interaction;

import java.util.List;
import java.util.Map;
import java.util.Set;
import meridian.core.api.InteractionControl.Heal;
import meridian.protocol.ApplyEffectInteraction;
import meridian.protocol.BlockFace;
import meridian.protocol.ClearEntityEffectInteraction;
import meridian.protocol.EffectConditionInteraction;
import meridian.protocol.EntityEffect;
import meridian.protocol.GameMode;
import meridian.protocol.Interaction;
import meridian.protocol.InteractionType;
import meridian.protocol.Match;
import meridian.protocol.OverlapBehavior;
import meridian.protocol.SerialInteraction;
import meridian.protocol.ValueType;

/**
 * A bite of a food whose slow heal is under way adds only its instant part: the game's food
 * starts its regeneration over (Overwrite), it does not stack. The fighter ate 25 pies a fight
 * for want of knowing it.
 */
public class HealingTest {
    static final int HEALTH = 0;
    static final int INSTANT = 1;   // Food_Instant_Heal_T1: +5% once
    static final int REGEN_T3 = 2;  // HealthRegen_Buff_T3: +2% each 2 s for 360 s, Overwrite
    static final int REGEN_T1 = 3;  // HealthRegen_Buff_T1: +1% each 2 s for 120 s, Overwrite
    static final int RAW = 4;       // HealthRegen_Buff_Raw

    static final Map<Integer, EntityEffect> EFFECTS = Map.of(
            INSTANT, effect(5, 0.1f, 0),
            REGEN_T3, effect(2, 360, 2),
            REGEN_T1, effect(1, 120, 2),
            RAW, effect(1, 30, 2));

    public void testUnderWayCountsWhatIsLeft() {
        Heal h = Healing.underWay(Map.of(REGEN_T3, 100.0), EFFECTS::get, HEALTH, 100);
        check(near(h.overTime(), 100) && near(h.seconds(), 100), "100 s left of 1%/s is 100 points: " + h);
        check(Healing.underWay(Map.of(INSTANT, 0.05), EFFECTS::get, HEALTH, 100).equals(Heal.NONE),
                "an instant effect is no healing under way");
        check(Healing.underWay(Map.of(REGEN_T3, Double.POSITIVE_INFINITY), EFFECTS::get, HEALTH, 100)
                .equals(Heal.NONE), "an endless effect's amount cannot be told");
    }

    public void testFirstBiteGivesAll() {
        Heal h = Healing.gain(pie(), Map.of(), EFFECTS::get, HEALTH, 200, 0, 200);
        check(near(h.now(), 10), "5% of 200 at once: " + h);
        check(near(h.overTime(), 720) && near(h.seconds(), 360), "180 cycles of 4 points: " + h);
    }

    public void testBiteOverRegenAddsOnlyWhatItPutsBack() {
        Heal h = Healing.gain(pie(), Map.of(REGEN_T3, 350.0), EFFECTS::get, HEALTH, 100, 0, 100);
        check(near(h.now(), 5), "the instant part lands: " + h);
        check(near(h.overTime(), 10), "10 s put back of 1%/s: " + h);
        Heal late = Healing.gain(pie(), Map.of(REGEN_T3, 10.0), EFFECTS::get, HEALTH, 100, 0, 100);
        check(near(late.overTime(), 350), "near its end, a bite starts it over: " + late);
    }

    public void testExtendAddsAndIgnoreDoesNot() {
        EntityEffect extend = effect(2, 10, 2);
        extend.overlapBehavior = OverlapBehavior.Extend;
        EntityEffect ignore = effect(2, 10, 2);
        ignore.overlapBehavior = OverlapBehavior.Ignore;
        Map<Integer, EntityEffect> e = Map.of(7, extend, 8, ignore);
        check(near(Healing.gain(List.of(on(7)), Map.of(7, 4.0), e::get, HEALTH, 100, 0, 100).overTime(), 10),
                "an extension adds its whole duration");
        check(near(Healing.gain(List.of(on(8)), Map.of(8, 4.0), e::get, HEALTH, 100, 0, 100).overTime(), 0),
                "an ignore adds nothing");
    }

    /** At full health a bite gives nothing at once, and near it only what is missing. */
    public void testAtOnceIsNoMoreThanIsMissing() {
        check(near(Healing.gain(pie(), Map.of(), EFFECTS::get, HEALTH, 100, 0, 0).now(), 0), "a pie at full health gives at once");
        check(near(Healing.gain(pie(), Map.of(), EFFECTS::get, HEALTH, 100, 0, 2).now(), 2), "not what is missing");
        check(near(Healing.gain(pie(), Map.of(), EFFECTS::get, HEALTH, 100, 0, 50).now(), 5), "not the pie's own 5");
    }

    public void testInstantOverOneStillOnDoesNotLandAgain() {
        Heal h = Healing.gain(List.of(on(INSTANT)), Map.of(INSTANT, 0.05), EFFECTS::get, HEALTH, 100, 0, 100);
        check(near(h.now(), 0), "the server does not run a one-off change twice: " + h);
    }

    /** Potion_Health_Regen: one change of 35% when its 5 s cycle runs out, 5.05 s on. */
    public void testCycleUnderWayStillLands() {
        EntityEffect potion = effect(35, 5.05f, 5);
        Map<Integer, EntityEffect> e = Map.of(9, potion);
        check(near(Healing.underWay(Map.of(9, 5.05), e::get, HEALTH, 100).overTime(), 35), "fresh: one change to come");
        check(near(Healing.underWay(Map.of(9, 2.6), e::get, HEALTH, 100).overTime(), 35),
                "half way through its cycle, the change is still to come");
        check(near(Healing.underWay(Map.of(9, 0.03), e::get, HEALTH, 100).overTime(), 0), "past its change: none");
    }

    public void testClearedRegenIsLost() {
        Heal h = Healing.gain(List.of(off(REGEN_T3), on(RAW)), Map.of(REGEN_T3, 100.0), EFFECTS::get, HEALTH, 100, 0, 100);
        check(near(h.overTime(), 0), "15 points for 100 lost is no gain: " + h);
    }

    /** The T1 tier check of the game's foods: a lower regen is not laid over a higher one. */
    public void testTierConditionSeesEffectsOn() {
        var condition = new EffectConditionInteraction();
        condition.entityEffects = new int[] {REGEN_T3};
        condition.match = Match.None;
        condition.next = 11;
        var serial = new SerialInteraction();
        serial.serialInteractions = new int[] {12, 13};
        var clear = new ClearEntityEffectInteraction();
        clear.effectId = RAW;
        var apply = new ApplyEffectInteraction();
        apply.effectId = REGEN_T1;
        Map<Integer, Interaction> ops = Map.of(10, condition, 11, serial, 12, clear, 13, apply);
        var root = InteractionFlattener.compile(1, new int[] {10}, ops::get);

        List<WalkEffects.Change> fresh = walk(root, Set.of());
        check(fresh.equals(List.of(off(RAW), on(REGEN_T1))), "nothing on: T1 goes on - " + fresh);
        List<WalkEffects.Change> higher = walk(root, Set.of(REGEN_T3));
        check(higher.isEmpty(), "T3 on: T1 is not laid over it - " + higher);
    }

    private static List<WalkEffects.Change> walk(CompiledInteraction root, Set<Integer> on) {
        var ctx = InteractionContext.ofBlock(InteractionType.Secondary, null, null, -1, BlockFace.None,
                null, GameMode.Adventure, Map.of())
                .withEffects(new WalkEffects(on, null, new StatSheet(StatSheet.newCatalog())));
        return Healing.play(root, ctx, null, 600).changes();
    }

    private static List<WalkEffects.Change> pie() {
        return List.of(on(INSTANT), off(RAW), off(REGEN_T1), on(REGEN_T3));
    }

    private static WalkEffects.Change on(int effect) {
        return new WalkEffects.Change(effect, true);
    }

    private static WalkEffects.Change off(int effect) {
        return new WalkEffects.Change(effect, false);
    }

    private static EntityEffect effect(float percent, float duration, double cooldown) {
        EntityEffect e = new EntityEffect();
        e.statModifiers = Map.of(HEALTH, percent);
        e.valueType = ValueType.Percent;
        e.duration = duration;
        e.damageCalculatorCooldown = cooldown;
        e.overlapBehavior = OverlapBehavior.Overwrite;
        return e;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
