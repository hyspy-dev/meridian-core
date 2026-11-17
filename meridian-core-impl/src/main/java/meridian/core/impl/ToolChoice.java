package meridian.core.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One hotbar tool that breaks a block, as the choice of the tool to break it with sees it: its slot,
 * kind ({@code Tool_Pickaxe_}), power on the block and speed, and how it stands.
 *
 * @param broken worn out - none of its durability left: it digs nothing
 * @param spent  repaired as far as it goes (its most durability at the server's floor): it digs
 */
record ToolChoice(int slot, String kind, double power, float speed, boolean broken, boolean spent) {

    /**
     * The slot to break with, {@code -1} for none: the strongest, the faster on a tie - but a spent
     * tool gives way to one of its own kind in order (a pickaxe to a pickaxe; a shovel does not take
     * stone from a spent pickaxe that still digs), and a worn-out one to any other that breaks it.
     */
    static int pick(List<ToolChoice> tools) {
        boolean sound = tools.stream().anyMatch(t -> !t.broken);
        Map<String, Integer> bestTierOfKind = new HashMap<>();
        for (ToolChoice t : tools) {
            bestTierOfKind.merge(t.kind, t.tier(), Math::min);
        }
        int best = -1;
        double bestPower = 0;
        float bestSpeed = 0;
        for (ToolChoice t : tools) {
            if (t.tier() > bestTierOfKind.get(t.kind) || (sound && t.broken)) {
                continue;                           // one in better order is there
            }
            if (t.power > bestPower || (t.power == bestPower && best >= 0 && t.speed > bestSpeed)) {
                best = t.slot;
                bestPower = t.power;
                bestSpeed = t.speed;
            }
        }
        return best;
    }

    /** 0 in order, 1 repaired as far as it goes, 2 worn out. */
    int tier() {
        return broken ? 2 : spent ? 1 : 0;
    }
}
