package meridian.core.impl.projectile;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import meridian.protocol.Direction;
import meridian.protocol.Hitbox;
import meridian.protocol.Model;
import meridian.protocol.PhysicsConfig;
import meridian.protocol.ProjectileConfig;
import meridian.protocol.RotationMode;

/** Plain tests, including all five UUID-matched shots from recorded-shots.json (17 November 2026). */
public class ProjectileTest {
    private static final Aabb BODY = new Aabb(new Vec3(-0.3, 0, -0.3), new Vec3(0.3, 1.8, 0.3));
    private static final Vec3 EYES = new Vec3(0, 1.6, 0);

    private record Shot(String uuid, Vec3 eyes, double yaw, double pitch, double charge,
                        Vec3 contact, double proxyInterval, double errorLimit, double baselineDistance,
                        double baselineTime) { }

    // Literal packet values, no fitted physics. The original JSON is kept alongside this test as a resource.
    private static final List<Shot> SHOTS = List.of(
            new Shot("b8d7476d-cfc1-435d-8c0d-71d275483cb6",
                    new Vec3(-16.281450271606445, 5.599999904632568, -15.719743728637695), 3.1339855, 0.0017500151, 1.807871,
                    new Vec3(-16.538845, 4.8568463, 12.287059), 0.384, 0.09, 0.06017, 0.37714),
            new Shot("be0776f9-6f37-413a-9f80-7a747650c9ba",
                    new Vec3(-16.2987117767334, 5.599999904632568, -15.333163261413574), 3.049986, 0.021000015, 1.034298,
                    new Vec3(-18.16621, 4.0790734, 4.4271145), 0.449, 0.06, 0.02653, 0.44593),
            new Shot("6e32ca78-f757-4f39-bcae-293734d123fd",
                    new Vec3(-27.283830642700195, 5.599999904632568, 2.351508378982544), 2.4812384, 0.036750015, 0.9007013,
                    new Vec3(-40.595024, 3.972027, 19.40716), 0.480, 0.22, 0.18885, 0.51098),
            new Shot("7f33ff2f-54e5-4e2b-b857-71685e04dcb8",
                    new Vec3(-28.98316192626953, 5.599999904632568, 6.0864057540893555), 2.4689884, 0.033250015, 0.7668582,
                    new Vec3(-36.9075, 4.0799336, 15.886717), 0.384, 0.11, 0.07862, 0.39997),
            new Shot("73f73996-43fd-4c56-bb17-e1622792cb29",
                    new Vec3(-29.69697380065918, 5.599999904632568, 6.986935138702393), 2.4689884, 0.18200018, 0.9003438,
                    new Vec3(-48.744324, 5.092676, 30.883371), 0.751, 0.56, 0.52753, 0.80000));

    public void testRecordedShots() {
        System.out.println("uuid | tier | closest blocks | flight s | proxy s | flight-proxy s");
        for (Shot shot : SHOTS) {
            ArrowFlight flight = new ArrowFlight(ShortbowCharge.adamantite(shot.charge()).orElseThrow());
            var closest = flight.closest(flight.launch(shot.eyes(), shot.yaw(), shot.pitch()), shot.contact(), 2);
            System.out.printf(Locale.ROOT, "%s | %d | %.6f | %.6f | %.3f | %+.6f%n", shot.uuid(),
                    ShortbowCharge.tier(shot.charge()), closest.distance(), closest.time(), shot.proxyInterval(),
                    closest.time() - shot.proxyInterval());
            // Empirical envelopes: observed residual plus ~0.03 blocks, not a claim of client equivalence.
            check(closest.distance() <= shot.errorLimit(), "Recording envelope: " + shot.uuid());
            // Tighter numerical regression checks prevent the envelopes from hiding changes to the model.
            near(closest.distance(), shot.baselineDistance(), 0.0001, "distance regression");
            near(closest.time(), shot.baselineTime(), 0.0001, "time regression");
            // Largest observed proxy discrepancy is 49 ms; allow 60 ms, without altering simulated time.
            check(Math.abs(closest.time() - shot.proxyInterval()) < 0.06, "proxy interval envelope");
        }
    }

    public void testPartialBlockShapeAndOwner() {
        ArrowFlight flight = new ArrowFlight(ShortbowCharge.adamantite(0.6).orElseThrow());
        ArrowFlight.State a = new ArrowFlight.State(new Vec3(0.5, 1.2, 0.5), Vec3.ZERO, 0, 0, Vec3.ZERO, false);
        ArrowFlight.State b = new ArrowFlight.State(new Vec3(2, 1.2, 0.5), Vec3.ZERO, flight.dt(), 0, Vec3.ZERO, true);
        var owner = new ArrowFlight.Block(1, 0, 0);
        ArrowFlight.SolidBlocks fence = new ArrowFlight.SolidBlocks() {
            public boolean isSolid(int x, int y, int z) { return x == 1 && y == 1 && z == 0; }
            public List<ArrowFlight.BlockShape> shapes(int x, int y, int z) {
                return isSolid(x, y, z) ? List.of(new ArrowFlight.BlockShape(owner,
                        new Aabb(new Vec3(1.25, 0, 0.25), new Vec3(1.75, 1.5, 0.75)))) : List.of();
            }
        };
        var hit = flight.collide(a, b, List.of(), fence).orElseThrow();
        check(hit.object().equals(owner), "overhanging shape lost its owning cell");
        near(hit.point().x(), 1.175, 1e-9, "partial shape contact");
        ArrowFlight.SolidBlocks slab = new ArrowFlight.SolidBlocks() {
            public boolean isSolid(int x, int y, int z) { return x == 1 && y == 1 && z == 0; }
            public List<Aabb> boxes(int x, int y, int z) {
                return isSolid(x, y, z) ? List.of(new Aabb(new Vec3(1, 0, 0), new Vec3(2, 0.25, 1))) : List.of();
            }
        };
        check(flight.collide(a, b, List.of(), slab).isEmpty(), "partial shape treated as a full cube");
    }

    public void testChargeBoundaries() {
        check(ShortbowCharge.adamantite(0.099999).isEmpty(), "early release must not launch");
        double[] keys = {0.1, 0.3, 0.6, 0.9, 1.2};
        for (int i = 0; i < keys.length; i++) {
            check(ShortbowCharge.tier(keys[i]) == i, "inclusive threshold");
            check(ShortbowCharge.tier(Math.nextDown((float) keys[i])) == i - 1, "packet float below threshold");
        }
        near(ShortbowCharge.adamantite(1.8).orElseThrow().launchSpeed(), 85, 0, "full speed");
    }

    public void testLaunchAndFirstSteps() {
        ProjectileParams p = simple(10, 10, 50, false);
        ArrowFlight flight = new ArrowFlight(p, 0.1);
        var launch = flight.launch(Vec3.ZERO, 0, 0);
        near(launch.velocity().z(), -10, 1e-12, "yaw zero");
        var first = flight.step(launch);
        near(first.position().z(), -1, 1e-12, "first tick launch velocity");
        near(first.velocity().y(), 0, 0, "first tick uninitialized gravity");
        var second = flight.step(first);
        near(second.velocity().z(), -9.96, 1e-12, "quadratic projected drag");
        near(second.velocity().y(), -1, 1e-12, "gravity after drag");
        near(second.position().y(), -0.1, 1e-12, "symplectic position");
        var interpolation = flight.atTime(launch, 0.15);
        near(interpolation.position().y(), -0.05, 1e-12, "fixed-step interpolation");
        near(flight.launch(Vec3.ZERO, Math.PI / 2, 0).velocity().x(), -10, 1e-12, "positive yaw");
        near(flight.launch(Vec3.ZERO, 0, Math.PI / 6).velocity().y(), 5.002213716506958, 1e-12, "lookup pitch");
        var offset = new ArrowFlight(ShortbowCharge.adamantite(1.2).orElseThrow()).launch(Vec3.ZERO, 0, 0);
        near(offset.position().x(), 0.14996051, 1e-7, "offset X then Y");
        near(offset.velocity().y(), 85 * Math.sin(Math.toRadians(2)), 0.07, "constant pitch offset with lookup rounding");
    }

    public void testResistanceLimitAndWater() {
        ArrowFlight clipped = new ArrowFlight(simple(10000, 10, 1, false), 0.1);
        var second = clipped.step(clipped.step(clipped.launch(Vec3.ZERO, 0, 0)));
        near(second.velocity().z(), 0, 1e-9, "drag cannot reverse velocity");
        ArrowFlight water = new ArrowFlight(simple(10, 10, 50, false), 0.1, ArrowFlight.Medium.WATER);
        var wet = water.step(water.step(water.launch(Vec3.ZERO, 0, 0)));
        check(wet.velocity().y() > 0, "wood buoyancy in water");
        check(Math.abs(wet.velocity().z()) < 9.96, "water drag exceeds air drag");
    }

    public void testConfigFactory() {
        ProjectileConfig config = new ProjectileConfig();
        config.physicsConfig = new PhysicsConfig();
        var p = config.physicsConfig;
        p.density = 700; p.gravity = 25; p.terminalVelocityAir = 50; p.terminalVelocityWater = 15;
        p.densityAir = 1.2; p.densityWater = 998; p.rotationMode = RotationMode.VelocityDamped;
        p.computePitch = true; p.rotationForce = 3; p.speedRotationFactor = 2; p.hitWaterImpulseLoss = 0.2;
        config.model = new Model();
        config.model.scale = 2; // Packet hitbox is already scaled; do not multiply by this again.
        config.model.hitbox = new Hitbox(-0.1f, -0.2f, -0.3f, 0.1f, 0.2f, 0.3f);
        config.launchForce = 85;
        config.rotationOffset = new Direction(0.2f, 0.3f, 0);
        ProjectileParams params = ProjectileParams.fromConfig(config);
        near(params.hitbox().max().x(), 0.1, 1e-7, "packet hitbox");
        near(params.pitchOffset(), 0.3, 1e-7, "packet radians");
        near(ProjectileParams.fromConfig(config, 2).hitbox().max().z(), 0.6, 1e-7, "spawn scale");
        check(params.dampPitch(), "rotation force mode");
    }

    public void testSweptCollisionAndFirstContact() {
        ArrowFlight flight = new ArrowFlight(simple(100, 0, 50, false), 0.1);
        var start = flight.launch(new Vec3(-0.5, 0.5, 0), 0, 0);
        var end = flight.step(start);
        Aabb thin = new Aabb(new Vec3(-1, 0, -6), new Vec3(0, 1, -5.99));
        var entity = new ArrowFlight.Entity("target", t -> thin);
        var hit = flight.collide(start, end, List.of(entity), (x, y, z) -> x == -1 && y == 0 && z == -4).orElseThrow();
        check(hit.kind() == ArrowFlight.HitKind.BLOCK, "nearer block wins");
        near(hit.point().z(), -2.925, 1e-12, "box extent before block face");
        near(hit.time(), 0.02925, 1e-12, "fractional hit time");
        check(hit.normal().equals(new Vec3(0, 0, 1)), "entry normal");
        var targetHit = flight.collide(start, end, List.of(entity), null).orElseThrow();
        check(targetHit.object().equals("target"), "thin box must not tunnel");
        var moving = new ArrowFlight.Entity("moving", t -> new Aabb(new Vec3(-1 + 100 * t, 0, -4), new Vec3(0 + 100 * t, 1, -3)));
        check(flight.collide(start, end, List.of(moving), null).isEmpty(), "relative swept coordinates");
        var box = new Aabb(Vec3.ZERO, new Vec3(1, 1, 1));
        check(box.intersect(new Vec3(0.5, 0.5, 0.5), new Vec3(0.6, 0.5, 0.5)).orElseThrow().normal().equals(Vec3.ZERO), "initial overlap normal");
        check(box.intersect(new Vec3(2, 2, 0), new Vec3(2, 2, 4)).isEmpty(), "parallel outside slab");
    }

    public void testStationaryTargets() {
        ProjectileParams params = ShortbowCharge.adamantite(1.2).orElseThrow();
        for (double distance : new double[] {10, 20, 30}) for (double height : new double[] {-3, 0, 5}) {
            AimSolver.Target target = new AimSolver.Target(new Vec3(0, height, -distance), Vec3.ZERO, BODY, 0);
            verify(params, target, 0.2);
        }
        // Non-axis-aligned headings also exercise anisotropic projected drag and spawn rotation.
        verify(params, new AimSolver.Target(new Vec3(18, 3, 22), Vec3.ZERO, BODY, 0), 0);
    }

    public void testTransverseLeadAndDelay() {
        ProjectileParams params = ShortbowCharge.adamantite(1.2).orElseThrow();
        AimSolver.Target moving = new AimSolver.Target(new Vec3(0, 0, -30), new Vec3(4, 0, 0), BODY, 0);
        var solution = verify(params, moving, 0.6);
        ArrowFlight flight = new ArrowFlight(params);
        var noLead = new AimSolver(params).solve(EYES, new AimSolver.Target(moving.position(), Vec3.ZERO, BODY, 0), 0).solution().orElseThrow();
        check(flight.trace(flight.launch(EYES, noLead.yaw(), noLead.pitch()), 1,
                List.of(new ArrowFlight.Entity(moving, t -> moving.boxAt(t + 0.6))), null).collision().isEmpty(), "aim without lead misses");
        check(solution.yaw() < -0.08, "lead includes release delay");
    }

    public void testFallingTarget() {
        verify(ShortbowCharge.adamantite(1.2).orElseThrow(),
                new AimSolver.Target(new Vec3(0, 6, -25), new Vec3(4, -1, 0), BODY, 20), 0.15);
    }

    public void testUnreachableAndNoConvergence() {
        ProjectileParams params = ShortbowCharge.adamantite(0.1).orElseThrow();
        var far = new AimSolver.Target(new Vec3(0, 0, -1000), Vec3.ZERO, BODY, 0);
        var result = new AimSolver(params).solve(EYES, far, 0);
        check(result.solution().isEmpty() && result.failure() == AimSolver.Failure.NO_LOW_ARC_WITHIN_LIMITS, "unreachable must fail");
        params = ShortbowCharge.adamantite(1.2).orElseThrow();
        var moving = new AimSolver.Target(new Vec3(0, 0, -30), new Vec3(4, 0, 0), BODY, 0);
        result = new AimSolver(new ArrowFlight(params), 4, 1).solve(EYES, moving, 0.6);
        check(result.solution().isEmpty() && result.failure() == AimSolver.Failure.DID_NOT_CONVERGE, "iteration cap must fail");
        result = new AimSolver(params).solve(EYES, new AimSolver.Target(new Vec3(0, 0, -20), Vec3.ZERO, BODY, 0), 0,
                (x, y, z) -> z == -5);
        check(result.failure() == AimSolver.Failure.BLOCKED, "wall blocks a converged shot");
    }

    public void testLowArcChosen() {
        ProjectileParams params = simple(30, 10, 1000000, false);
        var result = new AimSolver(params).solve(EYES,
                new AimSolver.Target(new Vec3(0, 0.7, -20), Vec3.ZERO, BODY, 0), 0).solution().orElseThrow();
        check(result.pitch() < 0.2 && result.flightTime() < 1, "low branch, not high branch");
    }

    private static AimSolver.Solution verify(ProjectileParams params, AimSolver.Target target, double delay) {
        var result = new AimSolver(params).solve(EYES, target, delay);
        check(result.solution().isPresent(), "solver failed: " + target + " " + result.failure());
        var solution = result.solution().get();
        ArrowFlight flight = new ArrowFlight(params);
        var hit = flight.trace(flight.launch(EYES, solution.yaw(), solution.pitch()), solution.flightTime() + flight.dt(),
                List.of(new ArrowFlight.Entity(target, t -> target.boxAt(delay + t))), null).collision();
        check(hit.isPresent(), "independent replay must hit");
        near(hit.get().time(), solution.flightTime(), 1e-9, "verified hit time");
        return solution;
    }

    private static ProjectileParams simple(double speed, double gravity, double terminal, boolean damp) {
        return new ProjectileParams(speed, gravity, 700, terminal, 15, 1.2, 998,
                new Aabb(new Vec3(-0.075, -0.075, -0.075), new Vec3(0.075, 0.075, 0.075)),
                Vec3.ZERO, 0, 0, true, true, damp, 3, 2, 0.2);
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** Runs every public test* method in stable order; an AssertionError exits with failure. */
    public static void main(String[] args) throws Throwable {
        var tests = Arrays.stream(ProjectileTest.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("test") && m.getParameterCount() == 0)
                .sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList();
        var instance = new ProjectileTest();
        for (var method : tests) {
            try { method.invoke(instance); }
            catch (InvocationTargetException e) { throw e.getCause(); }
            System.out.println("PASS " + method.getName());
        }
        System.out.println("PASS " + tests.size() + " tests");
    }
}
