package meridian.core.impl.navigation;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import meridian.core.api.EntityTracker;
import meridian.core.api.NavResult;
import meridian.core.api.BlockPos;
import meridian.core.api.Goal;
import meridian.core.api.Movement;
import meridian.core.api.Navigator;
import meridian.core.api.Vec3;

/** A drop at a wall's edge is collected from the accessible side, not abandoned at the other side. */
public class PickupRouteTest {
    public void testPassesNearestStandingCellAcrossWall() {
        var blocked = new BlockPos(0, 1, 1);
        var accessible = new BlockPos(1, 1, 0);
        Movement movement = (Movement) Proxy.newProxyInstance(Movement.class.getClassLoader(),
                new Class<?>[] {Movement.class}, (p, m, a) -> {
                    if (m.getName().equals("standAt")) {
                        return a[0].equals(blocked) || a[0].equals(accessible)
                                ? OptionalDouble.of(1) : OptionalDouble.empty();
                    }
                    throw new AssertionError(m.getName());
                });
        Navigator navigator = (Navigator) Proxy.newProxyInstance(Navigator.class.getClassLoader(),
                new Class<?>[] {Navigator.class}, (p, m, a) -> {
                    Goal g = (Goal) a[0];
                    return g.equals(Goal.at(accessible))
                            ? List.of(new Navigator.Step(Movement.Kind.WALK, 1, 1, 0, 1, Movement.Gait.WALK, null))
                            : List.of();
                });
        var worker = new WorkerImpl(movement, null, null, null, navigator, null, null, null, null, null);
        Goal goal = worker.pickupFrom(new Vec3(1.1, 1, 1.1), new Vec3(-0.5, 1, 1.5));
        if (!Goal.at(accessible).equals(goal)) throw new AssertionError("picked unreachable side: " + goal);
    }

    public void testHeadHighDropAtBackOfAlcoveHasATunnelApproach() {
        Rig r = new Rig();
        Goal goal = r.worker.pickupFrom(r.drop, r.at);
        if (!Goal.at(r.tunnel).equals(goal)) throw new AssertionError("reachable alcove drop abandoned: " + goal);
    }

    public void testCollectionMovesWithinTheTunnelCellToPickUpTheDrop() throws Exception {
        Rig r = new Rig();
        if (r.collect() != 1 || !r.taken || r.approaches != 1) {
            throw new AssertionError("the cell centre is too far; the worker must approach the drop inside the cell");
        }
    }

    public void testCollectionDoesNotApproachAcrossAFall() throws Exception {
        Rig r = new Rig();
        r.hurt = true;
        if (r.collect() != 0 || r.approaches != 0) throw new AssertionError("approached across a predicted fall");
    }

    public void testCollectionDoesNotLeaveTheStandingCellForAnApproach() throws Exception {
        Rig r = new Rig();
        r.end = new Vec3(4.4, 1, 1.1);
        if (r.collect() != 0 || r.approaches != 0) throw new AssertionError("approached outside the validated cell");
    }

    private static final class Rig {
        final BlockPos tunnel = new BlockPos(4, 1, 0);
        // Copper 645 in the real failing run, translated to arena coordinates. It rests at the
        // back of a head-high niche: too far from the tunnel cell's centre, within pickup radius
        // after a small collision-checked step towards that side of the same cell.
        final Vec3 drop = new Vec3(4.33762156896586, 2, 1.98036795686636);
        Vec3 at = new Vec3(2.5, 1, 0.5);
        Vec3 end = new Vec3(4.33762156896586, 1, 0.675);
        boolean hurt;
        boolean taken;
        int approaches;
        final WorkerImpl worker;

        Rig() {
            Movement movement = (Movement) Proxy.newProxyInstance(Movement.class.getClassLoader(),
                    new Class<?>[] {Movement.class}, (p, m, a) -> switch (m.getName()) {
                        case "standAt" -> tunnel.equals(a[0]) ? OptionalDouble.of(1) : OptionalDouble.empty();
                        case "position" -> at;
                        case "predict" -> new Movement.Prediction(
                                new Movement.MoveResult(Movement.MoveResult.Status.ARRIVED, end), 0.2, hurt, 1);
                        case "move" -> {
                            approaches++;
                            at = end;
                            taken = at.distanceSqTo(drop) < 1.75 * 1.75;
                            yield CompletableFuture.completedFuture(new Movement.MoveResult(
                                    Movement.MoveResult.Status.ARRIVED, at));
                        }
                        default -> throw new AssertionError(m.getName());
                    });
            EntityTracker entities = (EntityTracker) Proxy.newProxyInstance(EntityTracker.class.getClassLoader(),
                    new Class<?>[] {EntityTracker.class}, (p, m, a) -> switch (m.getName()) {
                        case "trackedEntities" -> Set.of(1);
                        case "positionOf" -> Optional.of(drop);
                        case "itemOf" -> taken ? Optional.empty() : Optional.of("Ore_Copper");
                        default -> throw new AssertionError(m.getName());
                    });
            Navigator navigator = (Navigator) Proxy.newProxyInstance(Navigator.class.getClassLoader(),
                    new Class<?>[] {Navigator.class}, (p, m, a) -> switch (m.getName()) {
                        case "plan" -> List.of(new Navigator.Step(Movement.Kind.WALK, 4, 1, 0, 1,
                                Movement.Gait.WALK, null));
                        case "goTo" -> {
                            at = new Vec3(4.5, 1, 0.5);
                            yield CompletableFuture.completedFuture(new NavResult(NavResult.Status.ARRIVED, at, 1, 0));
                        }
                        default -> throw new AssertionError(m.getName());
                    });
            worker = new WorkerImpl(movement, null, null, entities, navigator, null, null, null, null, null);
        }

        int collect() throws Exception {
            var method = WorkerImpl.class.getDeclaredMethod("collect", int.class, java.util.function.BiPredicate.class);
            method.setAccessible(true);
            return (Integer) method.invoke(worker, 0, (java.util.function.BiPredicate<Integer, Vec3>) (e, p) -> true);
        }
    }
}
