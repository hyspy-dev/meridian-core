package meridian.core.impl.interaction;

import java.util.Map;
import java.util.Set;
import meridian.protocol.ApplyEffectInteraction;
import meridian.protocol.BlockFace;
import meridian.protocol.CooldownConditionInteraction;
import meridian.protocol.GameMode;
import meridian.protocol.Interaction;
import meridian.protocol.InteractionCooldown;
import meridian.protocol.InteractionType;
import meridian.protocol.SerialInteraction;
import meridian.protocol.TriggerCooldownInteraction;

/**
 * A health potion starts a 15 s cooldown ({@code Potion_Consume_Health}); its use fails on the
 * {@code CooldownCondition} until it is over. Core keeps the cooldowns as the server does, so a
 * use it foretells or forges fails where the server's does - the fighter drank at it 30 times.
 */
public class CooldownsTest {
    static final class Clock {
        long nanos;
        void seconds(double s) { nanos += (long) (s * 1e9); }
    }

    public void testTriggerThenConditionUntilOver() {
        Clock clock = new Clock();
        Cooldowns c = new Cooldowns(() -> clock.nanos);
        check(!c.onCooldown("Potion_Consume_Health"), "no cooldown before a trigger");
        c.trigger("Potion_Consume_Health", 15, null, false);
        check(c.onCooldown("Potion_Consume_Health"), "on cooldown after the trigger");
        clock.seconds(14.9);
        check(c.onCooldown("Potion_Consume_Health") && c.left("Potion_Consume_Health") < 0.2, "still on at 14.9 s");
        clock.seconds(0.2);
        check(!c.onCooldown("Potion_Consume_Health"), "over after 15 s");
    }

    public void testChargesComeBackOneAtATime() {
        Clock clock = new Clock();
        Cooldowns c = new Cooldowns(() -> clock.nanos);
        c.trigger("Dash", 0.5f, new float[] {3, 3}, false);
        clock.seconds(0.6);
        check(!c.onCooldown("Dash"), "a second charge is left");
        c.trigger("Dash", 0.5f, null, false);
        clock.seconds(0.6);
        check(c.onCooldown("Dash"), "no charge left");
        clock.seconds(2.5);
        check(!c.onCooldown("Dash"), "a charge back after its time");
    }

    public void testCopyStartsNothingInTheOriginal() {
        Clock clock = new Clock();
        Cooldowns c = new Cooldowns(() -> clock.nanos);
        Cooldowns foretold = c.copy();
        foretold.trigger("Potion_Consume_Health", 15, null, false);
        check(foretold.onCooldown("Potion_Consume_Health") && !c.onCooldown("Potion_Consume_Health"),
                "a foretelling walk started a real cooldown");
    }

    /** The potion's own chain: the condition first, the trigger after the drink. */
    public void testWalkFailsOnCooldownAndForetellsNothing() {
        var condition = new CooldownConditionInteraction();
        condition.cooldownId = "Potion_Consume_Health";
        condition.next = 11;
        var serial = new SerialInteraction();
        serial.serialInteractions = new int[] {12, 13};
        var trigger = new TriggerCooldownInteraction();
        trigger.cooldown = new InteractionCooldown();
        trigger.cooldown.cooldownId = "Potion_Consume_Health";
        trigger.cooldown.cooldown = 15;
        var apply = new ApplyEffectInteraction();
        apply.effectId = 5;
        Map<Integer, Interaction> ops = Map.of(10, condition, 11, serial, 12, trigger, 13, apply);
        var root = InteractionFlattener.compile(1, new int[] {10}, ops::get);

        Clock clock = new Clock();
        Cooldowns real = new Cooldowns(() -> clock.nanos);
        var first = Healing.play(root, ctx(real), null, 600);
        check(first.changes().size() == 1, "ready: the potion applies its effect - " + first.changes());
        check(!real.onCooldown("Potion_Consume_Health"), "foretelling the drink started its cooldown");

        real.trigger("Potion_Consume_Health", 15, null, false);   // as a drink core forged would
        var again = Healing.play(root, ctx(real), null, 600);
        check(again.changes().isEmpty(), "on cooldown: no effect foretold - " + again.changes());
    }

    private static InteractionContext ctx(Cooldowns cooldowns) {
        return InteractionContext.ofBlock(InteractionType.Secondary, null, null, -1, BlockFace.None,
                null, GameMode.Adventure, Map.of())
                .withEffects(new WalkEffects(Set.of(), null, new StatSheet(StatSheet.newCatalog())).withCooldowns(cooldowns));
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
