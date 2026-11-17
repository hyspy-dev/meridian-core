package meridian.core.impl;

import java.util.List;

/**
 * The tool chosen for a block (the user's report of 18.11: the miner broke stone with a shovel once
 * the pickaxe had been repaired down to its floor). A plain test class: every {@code test*} method
 * runs, an {@link AssertionError} fails it.
 */
public class ToolChoiceTest {

    private static final String PICK = "Tool_Pickaxe_";
    private static final String SHOVEL = "Tool_Shovel_";

    private static void eq(int want, int got, String what) {
        if (want != got) {
            throw new AssertionError(what + ": slot " + got + ", wanted " + want);
        }
    }

    public void testSpentPickaxeStillBeatsShovelOnStone() {
        eq(0, ToolChoice.pick(List.of(new ToolChoice(0, PICK, 0.5, 1, false, true),
                new ToolChoice(1, SHOVEL, 0.1, 1, false, false))), "spent pickaxe vs sound shovel");
    }

    public void testSpentPickaxeGivesWayToSoundPickaxe() {
        eq(2, ToolChoice.pick(List.of(new ToolChoice(0, PICK, 0.8, 1, false, true),
                new ToolChoice(1, SHOVEL, 0.1, 1, false, false),
                new ToolChoice(2, PICK, 0.5, 1, false, false))), "spent strong pickaxe vs sound weaker pickaxe");
    }

    public void testBrokenPickaxeGivesWayToAnything() {
        eq(1, ToolChoice.pick(List.of(new ToolChoice(0, PICK, 0.5, 1, true, false),
                new ToolChoice(1, SHOVEL, 0.1, 1, false, false))), "broken pickaxe vs sound shovel");
    }

    public void testOnlyBrokenLeft() {
        eq(0, ToolChoice.pick(List.of(new ToolChoice(0, PICK, 0.5, 1, true, false))), "only a broken one");
    }

    public void testStrongestThenFaster() {
        eq(1, ToolChoice.pick(List.of(new ToolChoice(0, PICK, 0.5, 1, false, false),
                new ToolChoice(1, PICK, 0.5, 2, false, false),
                new ToolChoice(2, SHOVEL, 0.3, 9, false, false))), "same power, faster");
    }

    public static void main(String[] args) throws Exception {
        ToolChoiceTest t = new ToolChoiceTest();
        int n = 0;
        for (var m : ToolChoiceTest.class.getDeclaredMethods()) {
            if (m.getName().startsWith("test")) {
                m.invoke(t);
                System.out.println("PASS " + m.getName());
                n++;
            }
        }
        System.out.println("PASS " + n + " tests");
    }
}
