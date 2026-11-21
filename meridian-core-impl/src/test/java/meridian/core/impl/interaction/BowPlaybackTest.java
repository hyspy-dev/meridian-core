package meridian.core.impl.interaction;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import meridian.protocol.BlockFace;
import meridian.protocol.ChargingInteraction;
import meridian.protocol.GameMode;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InteractionType;
import meridian.protocol.ProjectileInteraction;
import meridian.protocol.WaitForDataFrom;

/** Checks launch identity across ticks and replay, and real charge time after a scheduler delay. */
public final class BowPlaybackTest {
    public static void main(String[] args) throws Exception {
        var ctx = InteractionContext.ofBlock(InteractionType.Primary, null, null, -1, BlockFace.None,
                null, GameMode.Adventure, Map.of());
        var projectile = new ProjectileInteraction();
        projectile.runTime = 0.2f;
        var root = InteractionFlattener.compile(1, new int[] {1}, id -> projectile);
        AtomicInteger launches = new AtomicInteger();
        var resolver = new InteractionSimulator.RootResolver() {
            public CompiledInteraction resolve(int id) { return root; }
            public void projectile(ProjectileInteraction node, InteractionSyncData data, double charge) {
                launches.incrementAndGet();
                data.generatedUUID = UUID.randomUUID();
            }
        };
        var walk = new ChainWalk(root, ctx, resolver, Map.of());
        UUID id = walk.tick().ops().getFirst().generatedUUID;
        check(id != null && id.equals(walk.tick().ops().getFirst().generatedUUID), "UUID changed between ticks");
        check(walk.rewind(0), "could not replay launch");
        check(id.equals(walk.tick().ops().getFirst().generatedUUID) && launches.get() == 1, "replay spawned another arrow");

        var charge = new ChargingInteraction();
        charge.waitForDataFrom = WaitForDataFrom.Client;
        charge.allowIndefiniteHold = true;
        charge.chargedNext = Map.of(0f, Integer.MIN_VALUE);
        var chargingRoot = InteractionFlattener.compile(2, new int[] {2}, n -> charge);
        var drawing = new ChainWalk(chargingRoot, ctx, null, Map.of()).realtime().draw(0.025f, t -> true, uuid -> { });
        check(drawing.tick().ops().getFirst().chargeValue == -1, "draw released before time");
        Thread.sleep(80);
        var released = drawing.tick().ops().getFirst();
        check(released.state == InteractionState.Finished && released.chargeValue >= 0.07,
                "draw reported tick count rather than elapsed time: " + released.chargeValue);

        var cancelled = new ChainWalk(chargingRoot, ctx, null, Map.of()).draw(0, t -> false, uuid -> { });
        check(cancelled.tick().ops().getFirst().chargeValue == -2f, "cancel did not use CHARGING_CANCELED");
        // The old bow has no zero-charge label. A release below its first tier finishes
        // without jumping to any label; the enclosing Serial can then return reserved Ammo.
        charge.chargedNext = Map.of(0.1f, 3);
        var oldBow = InteractionFlattener.compile(3, new int[] {2}, n -> n == 2 ? charge : projectile);
        AtomicInteger oldLaunches = new AtomicInteger();
        var oldResolver = new InteractionSimulator.RootResolver() {
            public CompiledInteraction resolve(int rootId) { return oldBow; }
            public void projectile(ProjectileInteraction node, InteractionSyncData data, double seconds) {
                oldLaunches.incrementAndGet();
            }
        };
        var tap = new ChainWalk(oldBow, ctx, oldResolver, Map.of()).hold(0);
        var zero = tap.tick().ops().getFirst();
        for (int i = 0; i < 30 && !tap.done(); i++) tap.tick();
        check(tap.done() && zero.chargeValue == 0 && oldLaunches.get() == 0,
                "tap took the first positive charge tier");
        var minimum = new ChainWalk(oldBow, ctx, oldResolver, Map.of());
        for (int i = 0; i < 30 && !minimum.done(); i++) minimum.tick();
        check(minimum.done() && oldLaunches.get() == 1, "default minimum hold stopped firing");
        System.out.println("PASS BowPlayback: stable UUID, replay, elapsed charge, cancel, tap below first tier");
    }
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
