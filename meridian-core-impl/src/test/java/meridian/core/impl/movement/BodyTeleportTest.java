package meridian.core.impl.movement;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import meridian.api.module.Scheduler;
import meridian.protocol.MovementStates;
import meridian.protocol.Position;
import meridian.protocol.TeleportAck;
import meridian.protocol.Vector3d;
import meridian.protocol.packets.player.ClientMovement;

/** Teleports must not be confused with old reports, retain old velocity, or omit their position. */
public class BodyTeleportTest {
    static final class Clock implements Scheduler {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public ScheduledFuture<?> schedule(Runnable task, Duration delay) { tasks.addLast(task); return null; }
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration first, Duration period) {
            throw new UnsupportedOperationException();
        }
    }
    static final class Rig {
        final Clock clock = new Clock();
        final List<ClientMovement> reports = new ArrayList<>();
        final Body.Driver driver = reports::add;
        final Body body = new Body(clock, null, () -> driver, () -> null);
        Rig() { body.takeOver(0, 1, 0, 0, 0, 0, true, false); }
        // Exercise the real report encoder without advancing physics: these tests isolate the
        // teleport/report boundary, so the old world's physics is deliberately unavailable.
        void report() throws Exception {
            var send = Body.class.getDeclaredMethod("send", Body.Driver.class, MovementStates.class);
            send.setAccessible(true);
            send.invoke(body, driver, new MovementStates());
        }
    }

    public void testBareTeleportToAnOldReportedPoint() throws Exception {
        var r = new Rig();
        r.report();
        r.body.sentByOther(at(5));
        r.body.sentByOther(at(0));
        check(r.body.snapshot().x == 0, "a bare teleport back was mistaken for an old own report");
    }

    public void testTeleportResetsTheReportedVelocityToo() throws Exception {
        var r = new Rig();
        var stateField = Body.class.getDeclaredField("state");
        stateField.setAccessible(true);
        BodyState state = (BodyState) stateField.get(r.body);
        state.vx = state.mvx = 12;
        state.vy = state.mvy = -30;
        var reported = Body.class.getDeclaredField("reportV");
        reported.setAccessible(true);
        reported.set(r.body, new double[] {12, -30, 0});
        r.body.teleported(true);
        r.report();
        var velocity = r.reports.getLast().velocity;
        check(velocity.x == 0 && velocity.y == 0 && velocity.z == 0,
                "a post-teleport report retained the old velocity");
        check(state.mvx == 0 && state.mvy == 0, "old per-tick movement survived the reset");
    }

    public void testExplicitSamePositionTeleportStillReportsIt() throws Exception {
        var r = new Rig();
        r.report();
        r.reports.clear();
        r.body.placeAt(0, 1, 0);
        r.report();
        check(!r.reports.isEmpty() && r.reports.getLast().absolutePosition != null,
                "explicit same-position teleport sent no absolute position");
    }

    public void testServerTeleportPausesOldPhysicsUntilAcknowledged() {
        var r = new Rig();
        r.body.serverTeleport(true);
        r.clock.tasks.removeFirst().run();
        check(r.clock.tasks.isEmpty() && r.reports.isEmpty(), "old-world ticks continued before ACK");
        var stale = at(7);
        stale.velocity = new Vector3d(1, 0, 0);
        r.body.sentByOther(stale);
        check(r.body.snapshot().x == 0, "a queued old report replaced the pending teleport");
        var ack = at(10);
        ack.teleportAck = new TeleportAck((byte) 1);
        r.body.sentByOther(ack);
        check(r.body.snapshot().x == 10 && !r.clock.tasks.isEmpty(), "ACK did not resume at the target");
    }

    static ClientMovement at(double x) {
        var movement = new ClientMovement();
        movement.absolutePosition = new Position(x, 1, 0);
        return movement;
    }
    static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
