package meridian.core.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleFunction;
import meridian.api.module.Scheduler;
import meridian.core.api.Box;
import meridian.core.api.InteractionOutcome;
import meridian.core.api.Look;
import meridian.core.api.Movement;
import meridian.core.api.RangedCombat;
import meridian.core.impl.interaction.InteractionControlImpl;
import meridian.core.impl.interaction.ProjectilePlayback;
import meridian.core.impl.movement.Terrain;
import meridian.core.impl.projectile.Aabb;
import meridian.core.impl.projectile.AimSolver;
import meridian.core.impl.projectile.ArrowFlight;
import meridian.core.impl.projectile.ProjectileParams;
import meridian.core.impl.projectile.Vec3;
import meridian.protocol.BlockPosition;
import meridian.protocol.Direction;
import meridian.protocol.InteractionChainData;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InteractionType;
import meridian.protocol.Position;
import meridian.protocol.ProjectileConfig;
import meridian.protocol.ProjectileInteraction;
import meridian.protocol.UpdateType;
import meridian.protocol.packets.assets.UpdateProjectileConfigs;
import meridian.protocol.packets.interaction.SyncInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChains;

/** Plays the client's predicted arrows against the session's world and assets. */
final class RangedCombatImpl implements RangedCombat, ProjectilePlayback {
    private final EntityTrackerImpl entities;
    private final InventoryTracker inventory;
    private final PlayerStateImpl player;
    private final InteractionControlImpl hands;
    private final Movement movement;
    private final Terrain terrain;
    private final Scheduler scheduler;
    private final Executor executor;
    private final Map<String, ProjectileConfig> configs = new ConcurrentHashMap<>();
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private final Map<Integer, Motion> motion = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile AtomicBoolean drawCancellation;
    private volatile long generation;
    private volatile double dilation = 1;

    @Override public double timeDilation() { return dilation; }
    void timeDilation(float value) { if (Float.isFinite(value) && value >= 0) dilation = value; }
    private volatile String lastFailure = "No plan yet";

    Map<String, Object> describe() {
        return Map.of("slot", bowSlot().orElse(-1), "configs", configs.size(), "tiers", hands.bowTiers(inventory.itemInHandId()),
                "failure", lastFailure);
    }

    private record ContactTarget(int id, String detail) { }
    private record Motion(meridian.core.api.Vec3 at, long time, Vec3 velocity) { }
    private record Plan(InteractionControlImpl.BowTier tier, ProjectileParams params, AimSolver.Solution aim) { }
    private record Contact(ArrowFlight.Collision collision, InteractionOutcome outcome, double damage) { }

    RangedCombatImpl(EntityTrackerImpl entities, InventoryTracker inventory, PlayerStateImpl player,
                     InteractionControlImpl hands, Movement movement, Terrain terrain,
                     Scheduler scheduler, Executor executor) {
        this.entities = entities;
        this.inventory = inventory;
        this.player = player;
        this.hands = hands;
        this.movement = movement;
        this.terrain = terrain;
        this.scheduler = scheduler;
        this.executor = executor;
    }

    /** Catalog updates are scoped to a connection, just as interaction and item catalogs are. */
    void update(UpdateProjectileConfigs packet) {
        if (packet.type == UpdateType.Init) configs.clear();
        if (packet.configs != null) configs.putAll(packet.configs);
        if (packet.removedConfigs != null) for (String id : packet.removedConfigs) configs.remove(id);
    }

    void reset() {
        configs.clear();
        dilation = 1;
        worldChanged();
    }

    void worldChanged() {
        generation++;
        cancel();
        motion.clear();
        flights.values().forEach(f -> f.done.completeExceptionally(new IllegalStateException("Session ended")));
        flights.clear();
    }

    /** Samples only observed position changes; an idle entity's velocity expires. */
    void observeMotion() {
        long now = System.nanoTime();
        for (int id : entities.trackedEntities()) entities.positionOf(id).ifPresent(at -> motion.compute(id, (key, old) -> {
            if (old == null) return new Motion(at, now, Vec3.ZERO);
            if (at.equals(old.at())) return old;
            double dt = (now - old.time()) / 1e9;
            Vec3 v = dt > 0.001 && dt < 0.5 ? vec(at).subtract(vec(old.at())).multiply(1 / dt) : Vec3.ZERO;
            return new Motion(at, now, v);
        }));
        motion.keySet().removeIf(id -> !entities.trackedEntities().contains(id));
    }

    private Vec3 velocity(int id) {
        Motion m = motion.get(id);
        return m == null || System.nanoTime() - m.time() > 300_000_000L ? Vec3.ZERO : m.velocity();
    }

    private boolean ammunition(String item) {
        String ammo = hands.bowAmmo(item);
        return ammo != null && player.inventories().values().stream().flatMap(s -> s.items().values().stream())
                .anyMatch(i -> ammo.equals(i.id()) && i.quantity() > 0);
    }

    private OptionalInt findBow(boolean withAmmo) {
        return inventory.hotbarItems().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(e -> !inventory.hotbarBroken(e.getKey()) && !hands.bowTiers(e.getValue()).isEmpty()
                        && (!withAmmo || ammunition(e.getValue())))
                .mapToInt(Map.Entry::getKey).findFirst();
    }

    @Override public void cancel() {
        AtomicBoolean pending = drawCancellation;
        if (pending != null) pending.set(true);
    }

    @Override public OptionalInt bowSlot() { return findBow(true); }
    @Override public CompletableFuture<Shot> shoot(int id) { return shoot(id, null, false); }
    @Override public CompletableFuture<Shot> shoot(int id, boolean hardest) { return shoot(id, null, hardest); }
    @Override public CompletableFuture<Shot> shoot(meridian.core.api.Vec3 point) { return shoot(-1, point, false); }

    private CompletableFuture<Shot> shoot(int id, meridian.core.api.Vec3 point, boolean hardest) {
        if (!busy.compareAndSet(false, true)) return CompletableFuture.completedFuture(refused(Status.BUSY, "Another shot is running"));
        AtomicBoolean cancelled = new AtomicBoolean();
        drawCancellation = cancelled;
        CompletableFuture<Shot> result = new CompletableFuture<>();
        result.whenComplete((r, e) -> { if (result.isCancelled()) cancelled.set(true); });
        try {
            executor.execute(() -> {
                try { result.complete(play(id, point, cancelled, hardest)); }
                catch (java.util.concurrent.TimeoutException e) {
                    result.complete(refused(Status.TIMED_OUT, "Server or movement did not finish in time"));
                } catch (Exception e) {
                    result.complete(refused(Status.UNAVAILABLE, e.getMessage() == null ? e.toString() : e.getMessage()));
                } finally { cancelled.set(true); drawCancellation = null; busy.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            drawCancellation = null;
            busy.set(false);
            result.complete(refused(Status.UNAVAILABLE, "Core is stopping"));
        }
        return result;
    }

    private Shot play(int id, meridian.core.api.Vec3 point, AtomicBoolean cancelled, boolean hardest) throws Exception {
        long session = generation;
        if (cancelled.get()) return refused(Status.CANCELLED, "Shot cancelled");
        OptionalInt slot = findBow(false);
        if (slot.isEmpty()) return refused(Status.NO_BOW, "No supported intact ordinary bow in hotbar");
        slot = findBow(true);
        if (slot.isEmpty()) return refused(Status.NO_AMMO, "No ammunition for an ordinary bow");
        if (configs.isEmpty()) return refused(Status.UNAVAILABLE, "Projectile catalog has not arrived");
        if (dilation != 1) return refused(Status.UNAVAILABLE, "Aim prediction requires normal world time");
        if (player.stats().getOrDefault("SignatureCharges", 0.0) > 0) {
            return refused(Status.UNAVAILABLE, "Signature shot is not an ordinary arrow");
        }
        AimSolver.Target target = target(id, point);
        if (target == null) return refused(Status.UNKNOWN_TARGET, "Target or its model box is unknown");
        String bow = inventory.hotbarItem(slot.getAsInt());
        Plan plan = plan(bow, target, null, 0.15, hardest);
        if (cancelled.get()) return refused(Status.CANCELLED, "Draw cancelled before starting");
        if (plan == null) return refused(Status.UNREACHABLE, "No verified low trajectory: " + lastFailure);
        Movement.State body = movement.state();
        if (body == null || body.velocity().distanceSqTo(new meridian.core.api.Vec3(0, 0, 0)) > 0.01) {
            return refused(Status.UNAVAILABLE, "Shooter must be standing still");
        }
        Movement.Wheel wheel = movement.take().orElse(null);
        if (wheel == null) return refused(Status.UNAVAILABLE, "Cannot take the player's body");
        try {
            if (inventory.activeHotbarSlot() != slot.getAsInt()) {
                InteractionOutcome swap = hands.switchHotbarSlot(slot.getAsInt()).get(5, TimeUnit.SECONDS);
                if (swap.status() != InteractionOutcome.Status.FINISHED) return refused(Status.CANCELLED, "Could not equip bow");
            }
            if (!aim(plan.aim())) return refused(Status.UNAVAILABLE, "Aim was not reported by movement");
            Plan selected = plan;
            CompletableFuture<UUID> launched = new CompletableFuture<>();
            List<InteractionControlImpl.BowTier> tiers = hands.bowTiers(bow);
            class ReleaseAim implements DoubleFunction<Boolean> {
                private CompletableFuture<Boolean> aiming;
                private InteractionControlImpl.BowTier aimingTier;
                @Override public Boolean apply(double elapsed) {
                    if (cancelled.get() || !wheel.held() || session != generation) return false;
                    var currentTier = tiers.stream().filter(t -> t.seconds() <= (float) elapsed)
                            .reduce((a, b) -> b).orElse(selected.tier());
                    if (!currentTier.equals(aimingTier)) {
                        aimingTier = currentTier;
                        aiming = CompletableFuture.supplyAsync(() -> {
                            try {
                                if (cancelled.get() || session != generation || !wheel.held() || !bow.equals(inventory.itemInHandId())) return false;
                                AimSolver.Target latest = target(id, point);
                                Plan fresh = latest == null ? null : plan(bow, latest, currentTier, 0, false);
                                return fresh != null && aim(fresh.aim());
                            } catch (Exception e) { return false; }
                        }, executor);
                    }
                    return aiming.getNow(null);
                }
            }
            DoubleFunction<Boolean> release = new ReleaseAim();
            InteractionOutcome primary = hands.draw(plan.tier().seconds(), release, launched::complete).get(12, TimeUnit.SECONDS);
            if (!launched.isDone()) return new Shot(Status.CANCELLED, "Draw ended without a projectile", null, -1, null,
                    plan.tier().seconds(), 0, Double.NaN, primary, null);
            UUID uuid = launched.get();
            Flight flight = flights.get(uuid);
            if (flight == null) return refused(Status.UNAVAILABLE, "Prediction was lost");
            Contact contact;
            try { contact = flight.done.get(20, TimeUnit.SECONDS); }
            catch (Exception failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                return new Shot(failure instanceof java.util.concurrent.TimeoutException ? Status.TIMED_OUT : Status.UNAVAILABLE,
                        "Arrow released: " + cause, uuid, -1, api(flight.state.position()), flight.chargeSeconds,
                        flight.state.time(), Double.NaN, primary, null);
            } finally { flights.remove(uuid); }
            var hit = contact.collision();
            return new Shot(hit.kind() == ArrowFlight.HitKind.ENTITY ? Status.HIT : Status.MISS, "First simulated contact",
                    uuid, hit.kind() == ArrowFlight.HitKind.ENTITY ? ((ContactTarget) hit.object()).id() : -1, api(hit.point()),
                    flight.chargeSeconds, hit.time(), contact.damage(), primary, contact.outcome());
        } finally { cancelled.set(true); wheel.release(); }
    }

    private boolean aim(AimSolver.Solution solution) throws Exception {
        // Much tighter than the default half-degree at long range. Completion follows the report.
        return movement.look(new Look((float) solution.yaw(), (float) solution.pitch()), Math.toRadians(0.05))
                .get(3, TimeUnit.SECONDS);
    }

    private AimSolver.Target target(int id, meridian.core.api.Vec3 point) {
        if (id < 0) return point == null ? null : new AimSolver.Target(vec(point), Vec3.ZERO,
                new Aabb(new Vec3(-0.05, -0.05, -0.05), new Vec3(0.05, 0.05, 0.05)), 0);
        var at = entities.positionOf(id).orElse(null);
        Box box = entities.projectileBoxesOf(id).stream().max(java.util.Comparator.comparingDouble(b ->
                b.box().width() * b.box().height() * b.box().depth())).map(EntityTrackerImpl.ProjectileBox::box).orElse(null);
        if (at == null || box == null || entities.localEntityId().orElse(-1) == id) return null;
        Vec3 p = vec(at);
        return new AimSolver.Target(p, velocity(id), new Aabb(vec(box.min()).subtract(p), vec(box.max()).subtract(p)), 0);
    }

    /** The first draw that reaches: the shortest of the bow's - or, {@code hardest}, the longest. */
    private Plan plan(String bow, AimSolver.Target target, InteractionControlImpl.BowTier only, double extraDelay,
                      boolean hardest) {
        Vec3 eyes = eyes();
        if (eyes == null) return null;
        List<InteractionControlImpl.BowTier> order = new java.util.ArrayList<>(hands.bowTiers(bow));
        order.sort(java.util.Comparator.comparingDouble(InteractionControlImpl.BowTier::seconds));
        if (hardest) java.util.Collections.reverse(order);
        for (var tier : order) {
            if (only != null && !only.equals(tier)) continue;
            ProjectileConfig config = configs.get(tier.config());
            if (config == null) { lastFailure = "Config missing: " + tier.config() + " (catalog " + configs.size() + ")"; continue; }
            try {
                ProjectileParams params = ProjectileParams.fromConfig(config);
                var solved = new AimSolver(params).solve(eyes, target, only == null ? tier.seconds() + extraDelay : extraDelay, blocks());
                if (solved.solution().isPresent()) return new Plan(tier, params, solved.solution().get());
                lastFailure = solved.failure().name() + " at " + tier.seconds();
            } catch (IllegalArgumentException | IllegalStateException unsupported) {
                lastFailure = unsupported.toString();
                // Unknown space, fluid, variable scale or nonstandard physics: do not guess.
            }
        }
        return null;
    }

    private Vec3 eyes() {
        var feet = entities.localPosition().orElse(null);
        return feet == null ? null : vec(feet).add(new Vec3(0, movement.capabilities().eyeHeight(), 0));
    }

    private ArrowFlight.SolidBlocks blocks() {
        return new ArrowFlight.SolidBlocks() {
            @Override public boolean isSolid(int x, int y, int z) { return !shapes(x, y, z).isEmpty(); }
            @Override public List<ArrowFlight.BlockShape> shapes(int x, int y, int z) {
                return terrain.projectileBoxes(x, y, z).stream().map(shape -> {
                    double[] b = shape.bounds();
                    return new ArrowFlight.BlockShape(new ArrowFlight.Block(shape.x(), shape.y(), shape.z()),
                            new Aabb(new Vec3(b[0], b[1], b[2]), new Vec3(b[3], b[4], b[5])));
                }).toList();
            }
        };
    }

    @Override public void prepare(ProjectileInteraction node, InteractionSyncData data, double chargeSeconds) {
        ProjectileConfig config = configs.get(node.configId);
        Vec3 eyes = eyes();
        var look = entities.localLookAngles();
        if (config == null || eyes == null || look == null) throw new IllegalStateException("Projectile launch unavailable: config=" + node.configId + ", count=" + configs.size() + ", eyes=" + eyes + ", look=" + look);
        if (node.ignorePitch || node.ignoreYaw || node.ignoreRoll) throw new IllegalStateException("Special projectile rotation unsupported");
        data.generatedUUID = UUID.randomUUID();
        data.attackerPos = new Position(eyes.x(), eyes.y(), eyes.z());
        data.attackerRot = new Direction((float) look.y(), (float) look.x(), (float) look.z());
        ArrowFlight model = new ArrowFlight(ProjectileParams.fromConfig(config));
        flights.put(data.generatedUUID, new Flight(data.generatedUUID, config, model,
                model.launch(eyes, look.y(), look.x()), generation, chargeSeconds));
    }

    @Override public void sent(SyncInteractionChains packet) {
        if (packet.updates != null) for (var chain : packet.updates) sent(chain);
    }

    private void sent(SyncInteractionChain chain) {
        if (chain.interactionData != null) for (var op : chain.interactionData) {
            if (op == null || op.generatedUUID == null) continue;
            Flight flight = flights.get(op.generatedUUID);
            if (flight != null && flight.started.compareAndSet(false, true)) {
                flight.startedNanos = System.nanoTime();
                scheduler.schedule(flight::tick, Duration.ofNanos((long) (flight.model.dt() * 1e9)));
                // Manual swing callers do not await contacts. Keep the record only for a bounded time.
                scheduler.schedule(() -> flights.remove(flight.id, flight), Duration.ofSeconds(35));
            }
        }
        if (chain.newForks != null) for (var fork : chain.newForks) sent(fork);
    }

    private final class Flight {
        final UUID id;
        final ProjectileConfig config;
        ArrowFlight model;
        final long session;
        final double chargeSeconds;
        final AtomicBoolean started = new AtomicBoolean();
        final CompletableFuture<Contact> done = new CompletableFuture<>();
        ArrowFlight.State state;
        long startedNanos;
        int wallTicks;
        Flight(UUID id, ProjectileConfig config, ArrowFlight model, ArrowFlight.State state, long session, double chargeSeconds) {
            this.chargeSeconds = chargeSeconds;
            this.id = id; this.config = config; this.model = model; this.state = state; this.session = session;
        }

        void tick() {
            if (session != generation || done.isDone()) return;
            try {
                wallTicks++;
                if (dilation == 0) { schedule(); return; }
                double dt = ArrowFlight.DEFAULT_DT * dilation;
                if (model.dt() != dt) model = new ArrowFlight(model.params(), dt);
                var next = model.step(state);
                List<ArrowFlight.Entity> obstacles = new ArrayList<>();
                for (int entity : entities.trackedEntities()) {
                    if (entity == entities.localEntityId().orElse(-1) || entities.itemOf(entity).isPresent()) continue;
                    var health = entities.healthOf(entity).orElse(null);
                    if (health != null && health.value() <= health.min()) continue;
                    // Models without a body (including projectile models) are ignored below.
                    String asset = entities.entityModelAssetId(entity).orElse("");
                    if (configs.values().stream().anyMatch(c -> c.model != null && asset.equals(c.model.assetId))) continue;
                    Vec3 shift = velocity(entity).multiply(ArrowFlight.DEFAULT_DT);
                    double time = state.time();
                    for (var part : entities.projectileBoxesOf(entity)) {
                        Aabb end = new Aabb(vec(part.box().min()), vec(part.box().max()));
                        Aabb start = end.translate(shift.multiply(-1));
                        if (start.forProjectile(model.params().hitbox())
                                .intersect(state.position(), next.position().subtract(shift)).isEmpty()) continue;
                        // EntityRefCollisionProvider takes the first matching detail, in model order.
                        obstacles.add(new ArrowFlight.Entity(new ContactTarget(entity, part.detail()),
                                t -> end.translate(shift.multiply((t - time) / model.dt() - 1))));
                        break;
                    }
                }
                var collision = model.collide(state, next, obstacles, blocks());
                state = next;
                if (collision.isPresent()) { contact(collision.get()); return; }
                if (state.time() > 15) throw new IllegalStateException("No contact within supported flight horizon");
                schedule();
            } catch (RuntimeException e) { done.completeExceptionally(e); }
        }

        private void schedule() {
            long nextTick = startedNanos + (long) ((wallTicks + 1) * ArrowFlight.DEFAULT_DT * 1e9);
            scheduler.schedule(this::tick, Duration.ofNanos(Math.max(0, nextTick - System.nanoTime())));
        }

        void contact(ArrowFlight.Collision hit) {
            InteractionType type = hit.kind() == ArrowFlight.HitKind.ENTITY ? InteractionType.ProjectileHit : InteractionType.ProjectileMiss;
            Integer root = config.interactions == null ? null : config.interactions.get(type);
            if (root == null) { done.completeExceptionally(new IllegalStateException("No projectile contact root")); return; }
            InteractionChainData data = new InteractionChainData();
            data.proxyId = id;
            data.entityId = hit.kind() == ArrowFlight.HitKind.ENTITY ? ((ContactTarget) hit.object()).id() : -1;
            if (hit.object() instanceof ContactTarget target) data.hitDetail = target.detail();
            Vec3 p = hit.point();
            data.hitLocation = new org.joml.Vector3f((float) p.x(), (float) p.y(), (float) p.z());
            Vec3 normal = hit.kind() == ArrowFlight.HitKind.ENTITY ? Vec3.ZERO : hit.normal();
            data.hitNormal = new org.joml.Vector3f((float) normal.x(), (float) normal.y(), (float) normal.z());
            if (hit.object() instanceof ArrowFlight.Block b) data.blockPosition = new BlockPosition(b.x(), b.y(), b.z());
            else data.blockPosition = new BlockPosition((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
            double health = entities.healthOf(data.entityId).map(h -> h.value()).orElse(Double.NaN);
            hands.projectileContact(root, type, data).whenComplete((outcome, error) -> {
                if (error != null) done.completeExceptionally(error);
                else scheduler.schedule(() -> {
                    double after = entities.healthOf(data.entityId).map(h -> h.value()).orElse(Double.NaN);
                    done.complete(new Contact(hit, outcome, health - after));
                }, Duration.ofMillis(150));
            });
        }
    }

    private static Shot refused(Status status, String why) {
        return new Shot(status, why, null, -1, null, 0, 0, Double.NaN, null, null);
    }
    private static Vec3 vec(meridian.core.api.Vec3 p) { return new Vec3(p.x(), p.y(), p.z()); }
    private static meridian.core.api.Vec3 api(Vec3 p) { return new meridian.core.api.Vec3(p.x(), p.y(), p.z()); }
}
