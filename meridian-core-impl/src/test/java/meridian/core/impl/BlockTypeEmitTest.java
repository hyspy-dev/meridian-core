package meridian.core.impl;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import meridian.api.module.Scheduler;
import meridian.api.session.ProxySession;
import meridian.core.api.BlockView;
import meridian.protocol.BlockType;
import meridian.protocol.UpdateType;
import meridian.protocol.packets.assets.UpdateBlockTypes;

/**
 * What {@link WorldStateImpl} sends the client when its rules change: the types whose look changed,
 * and nothing for a type the client already shows so. A plain test class (no framework on hand
 * offline): every {@code test*} method runs, an {@link AssertionError} fails it.
 */
public class BlockTypeEmitTest {

    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<UpdateBlockTypes> sent = new ArrayList<>();

    private WorldStateImpl world() {
        Scheduler scheduler = (Scheduler) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Scheduler.class}, (p, m, args) -> {
                    scheduled.add((Runnable) args[0]);
                    return null;
                });
        WorldStateImpl ws = new WorldStateImpl(scheduler);
        ws.onServerBlockTypes(new UpdateBlockTypes(UpdateType.Init, Map.of(
                1, type("Rock_Stone"), 2, type("Ore_Copper_Stone"), 3, type("Ore_Copper_Shale")),
                true, true, true, true), session());
        return ws;
    }

    private ProxySession session() {
        return (ProxySession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ProxySession.class}, (p, m, args) -> {
                    if (m.getName().equals("sendToClient")) {
                        sent.add((UpdateBlockTypes) args[0]);
                    }
                    return null;
                });
    }

    /** Runs what is due and gives the ids that went out. */
    private TreeSet<Integer> flush() {
        List<Runnable> due = new ArrayList<>(scheduled);
        scheduled.clear();
        sent.clear();
        due.forEach(Runnable::run);
        TreeSet<Integer> ids = new TreeSet<>();
        sent.forEach(u -> ids.addAll(u.blockTypes.keySet()));
        return ids;
    }

    private static java.util.function.UnaryOperator<BlockView> glow(int level) {
        return v -> v.name().startsWith("Ore_") ? v.withLight(1, level, level, level) : v;
    }

    public void testOnlyTheTypesARuleChangesGoOut() {
        WorldStateImpl ws = world();
        flush();
        ws.overrideAllBlockTypes("glow", glow(15));
        check(flush().equals(new TreeSet<>(List.of(2, 3))), "the ores, not the stone: " + sent);
        ws.overrideAllBlockTypes("glow", glow(15));
        check(flush().isEmpty(), "the same rule again sent " + sent.size() + " packet(s)");
        ws.overrideAllBlockTypes("glow", glow(3));
        check(flush().equals(new TreeSet<>(List.of(2, 3))), "a new colour goes to the ores");
        ws.clearOverrideAll("glow");
        check(flush().equals(new TreeSet<>(List.of(2, 3))), "the ores go back to the truth");
        ws.clearOverrideAll("glow");
        check(flush().isEmpty(), "no rule, nothing to send");
    }

    public void testWhatTheLoadPacketCarriedIsNotSentAgain() {
        WorldStateImpl ws = world();
        flush();
        ws.overrideAllBlockTypes("glow", glow(15));
        flush();
        // The server declares the types again (a world load): the rewrite carries the rule.
        UpdateBlockTypes again = new UpdateBlockTypes(UpdateType.Init, new java.util.HashMap<>(Map.of(
                1, type("Rock_Stone"), 2, type("Ore_Copper_Stone"), 3, type("Ore_Copper_Shale"))),
                true, true, true, true);
        ProxySession s = session();
        ws.onServerBlockTypes(again, s);
        new BlockTypeRewriter(ws).handleS2C(null, again, s);
        check(again.blockTypes.get(2).light != null, "the load packet carries the rule");
        check(again.blockTypes.get(1).light == null, "and leaves the stone as it was");
        ws.itemsArrived();   // every rule runs again, over every type
        check(flush().isEmpty(), "what the load carried went out again: " + sent.size() + " packet(s)");
    }

    public void testLightChannelsAreFourBits() {
        BlockViewImpl v = new BlockViewImpl(2, type("Ore_Copper_Stone"));
        BlockType lit = ((BlockViewImpl) v.withLight(1, 99, -5, 7)).toBlockType();
        check(lit.light.red == 15 && lit.light.green == 0 && lit.light.blue == 7,
                "channels " + lit.light.red + " " + lit.light.green + " " + lit.light.blue);
    }

    public void testAStateBelongsToTheTypeItWasDeclaredOn() {
        BlockType open = type("*Door_State_Definitions_Open");
        open.states = Map.of("Open", 9, "Shut", 8, BlockView.DEFAULT_STATE, 7);
        BlockViewImpl v = new BlockViewImpl(9, open);
        check(v.family() == 7, "family " + v.family());
        check(v.state().equals("Open"), "state " + v.state());
        check(v.sameBlock(7) && v.sameBlock(8) && v.sameBlock(9) && !v.sameBlock(10), "one block, three ids");
        BlockType door = type("Door");
        door.states = Map.of("Open", 9, "Shut", 8);
        check(new BlockViewImpl(7, door).family() == 7, "the declaring type is its own family");
        check(new BlockViewImpl(1, type("Rock_Stone")).family() == 1, "a block without states is its own");
    }

    private static BlockType type(String name) {
        BlockType t = new BlockType();
        t.name = name;
        return t;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
