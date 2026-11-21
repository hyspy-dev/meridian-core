package meridian.core.impl.navigation;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.OptionalDouble;
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
}
