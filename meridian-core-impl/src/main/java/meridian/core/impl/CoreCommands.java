package meridian.core.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import meridian.api.module.ModuleContext;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.EntityTracker;
import meridian.core.api.Face;
import meridian.core.api.InteractionOutcome;
import meridian.core.api.FluidCell;
import meridian.core.api.InteractionControl;
import meridian.core.api.Player;
import meridian.core.api.PlayerState;
import meridian.core.api.Vec3;
import meridian.core.api.World;

/**
 * Core's services on the proxy's command channel - {@code core <conn> <what> ...} - so the forged
 * chains can be driven and checked from outside the game, a headless client's above all.
 *
 * <p>Actions answer once their chain has played out; whether the server took it shows in what it
 * sends back ({@code expect}, {@code block}) and in its own log.
 */
final class CoreCommands {

    static final String USAGE = "pos | block <x y z> | look | inv | stats | chain <rootId> | slot <n> | tp <x y z> | face <x y z>|<entity> | "
            + "use|plant|water <x y z> | hit|dig <x y z> [times] | place <x y z> [face] [orient] | "
            + "entities [range] | attack <entity> [times] | useentity <entity> | useitem [times] [hold <s>] | swing [hold <s>] | dodge | assets | asset <path> | "
            + "movement | body | stand <x y z> | move <walk|jump|mantle|drop|leap|swim|climb|fly> <x y z> [walk|run|sprint] | "
            + "predict <kind> <x y z> [gait] [trace] | hold <forward> <strafe> <yaw> [jump] [crouch] [sprint] [walk] [fly] | hold off | halt";

    /** Where the eyes are above the feet - {@code PlayerImpl}'s raycast origin. */
    private static final double EYE_HEIGHT = 1.6;

    private final ModuleContext ctx;
    private final SessionHolder sessions;

    private CoreCommands(ModuleContext ctx, SessionHolder sessions) {
        this.ctx = ctx;
        this.sessions = sessions;
    }

    /** Offers the commands; a proxy without a command channel - or too old to have one - has none. */
    static void register(ModuleContext ctx, SessionHolder sessions) {
        try {
            CoreCommands c = new CoreCommands(ctx, sessions);
            ctx.registerCommand("core", USAGE, c::run);
        } catch (NoSuchMethodError olderProxy) {
            // no command channel there
        }
    }

    private CompletableFuture<?> run(List<String> args, String json) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException(USAGE);
        }
        String what = args.get(0).toLowerCase(Locale.ROOT);
        return switch (what) {
            case "pos" -> CompletableFuture.completedFuture(pos());
            case "block" -> CompletableFuture.completedFuture(block(at(args, 1)));
            case "look" -> CompletableFuture.completedFuture(look());
            case "inv" -> CompletableFuture.completedFuture(inventory());
            case "stats" -> CompletableFuture.completedFuture(stats());
            case "assets" -> CompletableFuture.completedFuture(assets());
            case "movement" -> CompletableFuture.completedFuture(movement());
            case "stand" -> CompletableFuture.completedFuture(stand(at(args, 1)));
            case "move" -> move(args);
            case "body" -> CompletableFuture.completedFuture(body());
            case "hold" -> CompletableFuture.completedFuture(holdKeys(args));
            case "predict" -> CompletableFuture.completedFuture(predict(args));
            case "halt" -> {
                movementService().stop();
                yield CompletableFuture.completedFuture(body());
            }
            case "asset" -> CompletableFuture.completedFuture(asset(args.get(1)));
            case "chain" -> CompletableFuture.completedFuture(chain(Integer.parseInt(args.get(1))));
            case "face" -> CompletableFuture.completedFuture(args.size() == 2
                    ? faceEntity(Integer.parseInt(args.get(1))) : face(at(args, 1)));
            case "entities" -> CompletableFuture.completedFuture(
                    entities(args.size() > 1 ? Double.parseDouble(args.get(1)) : 32));
            case "attack" -> {
                int id = Integer.parseInt(args.get(1));
                faceIfHeadless(id);
                yield afterEntity(repeat(times(args, 2), () -> interactions().hitEntity(id)), id);
            }
            case "useentity" -> {
                int id = Integer.parseInt(args.get(1));
                faceIfHeadless(id);
                yield afterEntity(interactions().useOnEntity(id).thenApply(List::of), id);
            }
            case "useitem" -> {
                java.time.Duration hold = hold(args);
                int n = args.size() > 1 && !args.get(1).equals("hold") ? times(args, 1) : 1;
                yield repeat(n, () -> hold == null ? interactions().useItem() : interactions().useItem(hold))
                        .thenApply(this::withPos);
            }
            case "swing" -> {
                java.time.Duration hold = hold(args);
                yield (hold == null ? interactions().swing() : interactions().swing(hold))
                        .thenApply(List::of).thenApply(this::withPos);
            }
            case "dodge" -> interactions().dodge().thenApply(List::of).thenApply(this::withPos);
            case "slot" -> interactions().switchHotbarSlot(Integer.parseInt(args.get(1)))
                    .thenApply(List::of).thenApply(this::withPos);
            case "tp" -> {
                BlockPos p = at(args, 1);
                player().teleport(new Vec3(p.x() + 0.5, p.y(), p.z() + 0.5));
                yield CompletableFuture.completedFuture(Map.of("teleported", p));
            }
            case "use" -> after(interactions().useOnBlock(at(args, 1)).thenApply(List::of), at(args, 1));
            case "hit" -> after(repeat(times(args, 4), () -> interactions().hitBlock(at(args, 1))), at(args, 1));
            case "plant" -> after(interactions().plantOnBlock(at(args, 1)).thenApply(List::of), Face.UP.from(at(args, 1)));
            case "water" -> after(interactions().waterBlock(at(args, 1)).thenApply(List::of), at(args, 1));
            case "dig" -> after(repeat(times(args, 4), () -> interactions().digSwing(at(args, 1))), at(args, 1));
            case "place" -> {
                BlockPos target = at(args, 1);
                Face face = args.size() > 4 ? face(args.get(4)) : Face.UP;
                Face orient = args.size() > 5 ? face(args.get(5)) : face;
                yield after(interactions().placeOnBlock(target, face, orient).thenApply(List::of), face.from(target));
            }
            default -> throw new IllegalArgumentException("core: " + USAGE);
        };
    }

    /**
     * Runs an action {@code n} times, each once the server has said how the one before went - the
     * way a held button swings: a block's damage wears off between hits spaced further apart.
     */
    private static CompletableFuture<List<InteractionOutcome>> repeat(int n,
            java.util.function.Supplier<CompletableFuture<InteractionOutcome>> action) {
        List<InteractionOutcome> all = new java.util.concurrent.CopyOnWriteArrayList<>();
        CompletableFuture<InteractionOutcome> f = action.get().thenApply(o -> add(all, o));
        for (int i = 1; i < n; i++) {
            f = f.thenCompose(v -> action.get()).thenApply(o -> add(all, o));
        }
        return f.thenApply(v -> List.copyOf(all));
    }

    private static InteractionOutcome add(List<InteractionOutcome> all, InteractionOutcome o) {
        all.add(o);
        return o;
    }

    /** How the server ran each chain - one, or a list of them. */
    private static Object outcomes(List<InteractionOutcome> list) {
        if (list.size() == 1) {
            return outcome(list.get(0));
        }
        return list.stream().map(CoreCommands::outcome).toList();
    }

    /** One chain's outcome: how it ended, what failed in it, its forks, how often core was corrected. */
    private static Map<String, Object> outcome(InteractionOutcome o) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", o.status().name());
        out.put("ops", o.steps().size());
        List<String> failed = o.failedSteps().stream().map(s -> s.index() + " " + s.operation()).toList();
        if (!failed.isEmpty()) {
            out.put("failed", failed);
        }
        if (!o.forks().isEmpty()) {
            out.put("forks", o.forks());
        }
        if (o.corrections() > 0) {
            out.put("corrections", o.corrections());
        }
        return out;
    }

    /** Where the player is, with how the chains went. */
    private Map<String, Object> withPos(List<InteractionOutcome> list) {
        Map<String, Object> out = new LinkedHashMap<>(pos());
        out.put("outcome", outcomes(list));
        return out;
    }

    /** {@code ... hold <seconds>}: how long the button is held; {@code null} when not said. */
    private static java.time.Duration hold(List<String> args) {
        int at = args.indexOf("hold");
        if (at < 0 || at + 1 >= args.size()) {
            return null;
        }
        return java.time.Duration.ofNanos((long) (Double.parseDouble(args.get(at + 1)) * 1_000_000_000L));
    }

    private meridian.core.api.Movement movementService() {
        return ctx.services().get(meridian.core.api.Movement.class)
                .orElseThrow(() -> new IllegalStateException("no Movement service"));
    }

    /** The body: where it is, whether it stands, and what it can do. */
    private Map<String, Object> movement() {
        meridian.core.api.Movement m = movementService();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", m.available());
        Vec3 p = m.position();
        if (p != null) {
            out.put("position", Map.of("x", p.x(), "y", p.y(), "z", p.z()));
        }
        out.put("onGround", m.onGround());
        out.put("capabilities", m.capabilities());
        return out;
    }

    /** Where the feet are when the body stands in a cell, or that it cannot. */
    private Map<String, Object> stand(BlockPos cell) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cell", cell);
        java.util.OptionalDouble feet = movementService().standAt(cell);
        out.put("stands", feet.isPresent());
        feet.ifPresent(f -> out.put("feet", f));
        out.put("known", movementService().known(cell));
        return out;
    }

    /**
     * {@code move <walk|jump|mantle|drop|leap|swim|climb|fly> <x y z> [walk|run|sprint]}: one move
     * onto that cell - its floor, or for a swim, a climb or a flight the cell itself when nobody
     * stands there.
     */
    private CompletableFuture<Map<String, Object>> move(List<String> args) {
        meridian.core.api.Movement m = movementService();
        meridian.core.api.Movement.Move move = moveOf(m, args);
        return m.move(move).thenApply(CoreCommands::result);
    }

    private static meridian.core.api.Movement.Move moveOf(meridian.core.api.Movement m, List<String> args) {
        meridian.core.api.Movement.Kind kind = meridian.core.api.Movement.Kind.valueOf(args.get(1).toUpperCase(Locale.ROOT));
        BlockPos cell = at(args, 2);
        meridian.core.api.Movement.Gait gait = args.size() > 5
                ? meridian.core.api.Movement.Gait.valueOf(args.get(5).toUpperCase(Locale.ROOT))
                : meridian.core.api.Movement.Gait.RUN;
        java.util.OptionalDouble feet = m.standAt(cell);
        double y;
        if (feet.isPresent()) {
            y = feet.getAsDouble();
        } else if (kind == meridian.core.api.Movement.Kind.SWIM || kind == meridian.core.api.Movement.Kind.FLY
                || kind == meridian.core.api.Movement.Kind.CLIMB) {
            y = cell.y();
        } else {
            throw new IllegalArgumentException("nobody stands in " + cell);
        }
        return new meridian.core.api.Movement.Move(kind, new Vec3(cell.x() + 0.5, y, cell.z() + 0.5), gait);
    }

    private static Map<String, Object> result(meridian.core.api.Movement.MoveResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status().name());
        if (r.at() != null) {
            out.put("at", Map.of("x", r.at().x(), "y", r.at().y(), "z", r.at().z()));
        }
        return out;
    }

    /** The whole body now: where, how fast, what it is doing. */
    private Map<String, Object> body() {
        meridian.core.api.Movement.State s = movementService().state();
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.position() != null) {
            out.put("at", Map.of("x", s.position().x(), "y", s.position().y(), "z", s.position().z()));
        }
        out.put("velocity", Map.of("x", s.velocity().x(), "y", s.velocity().y(), "z", s.velocity().z()));
        out.put("yaw", s.yaw());
        List<String> doing = new java.util.ArrayList<>();
        if (s.onGround()) doing.add("onGround");
        if (s.inFluid()) doing.add("inFluid");
        if (s.swimming()) doing.add("swimming");
        if (s.climbing()) doing.add("climbing");
        if (s.flying()) doing.add("flying");
        if (s.crouching()) doing.add("crouching");
        if (s.sprinting()) doing.add("sprinting");
        if (s.sliding()) doing.add("sliding");
        if (s.rolling()) doing.add("rolling");
        if (s.mantling()) doing.add("mantling");
        if (s.hanging()) doing.add("hanging");
        out.put("states", doing);
        return out;
    }

    /**
     * {@code hold <forward> <strafe> <yaw degrees> [jump] [crouch] [sprint] [walk] [fly]} holds
     * keys; {@code hold off} lets go.
     */
    private Map<String, Object> holdKeys(List<String> args) {
        meridian.core.api.Movement m = movementService();
        if (args.size() > 1 && args.get(1).equalsIgnoreCase("off")) {
            m.hold(null);
            return body();
        }
        double forward = Double.parseDouble(args.get(1));
        double strafe = Double.parseDouble(args.get(2));
        float yaw = (float) Math.toRadians(Double.parseDouble(args.get(3)));
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (int i = 4; i < args.size(); i++) {
            keys.add(args.get(i).toLowerCase(Locale.ROOT));
        }
        m.hold(new meridian.core.api.Movement.Controls(forward, strafe, yaw, 0f, keys.contains("jump"),
                keys.contains("crouch"), keys.contains("sprint"), keys.contains("walk"), keys.contains("fly")));
        return body();
    }

    /** {@code predict <kind> <x y z> [gait]}: how the move would go, played on a copy of the body. */
    private Map<String, Object> predict(List<String> args) {
        meridian.core.api.Movement m = movementService();
        List<String> trace = args.contains("trace") ? new java.util.ArrayList<>() : null;
        List<String> rest = args.stream().filter(a -> !a.equals("trace")).toList();
        meridian.core.api.Movement.Prediction p = trace != null
                && m instanceof meridian.core.impl.movement.MovementImpl impl
                ? impl.predict(List.of(moveOf(m, rest)), trace) : m.predict(moveOf(m, rest));
        Map<String, Object> out = new LinkedHashMap<>(result(p.result()));
        if (trace != null) {
            out.put("trace", trace);
        }
        out.put("seconds", p.seconds());
        out.put("hurt", p.hurt());
        return out;
    }

    /** Where the game's own files come from: the installation, its version. */
    private Map<String, Object> assets() {
        meridian.core.api.GameAssets a = ctx.services().get(meridian.core.api.GameAssets.class)
                .orElseThrow(() -> new IllegalStateException("no GameAssets service"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", a.available());
        a.gameVersion().ifPresent(v -> out.put("version", v));
        a.gameDirectory().ifPresent(d -> out.put("gameDirectory", d.toString()));
        return out;
    }

    /** One of the game's files: where it comes from, how big, its sha256 - never the file itself. */
    private Map<String, Object> asset(String path) {
        meridian.core.api.GameAssets a = ctx.services().get(meridian.core.api.GameAssets.class)
                .orElseThrow(() -> new IllegalStateException("no GameAssets service"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("source", a.source(path).name());
        a.read(path).ifPresent(bytes -> {
            out.put("size", bytes.length);
            try {
                out.put("sha256", java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (java.security.NoSuchAlgorithmException ignored) {
                // every JVM has SHA-256
            }
        });
        if (path.endsWith("/")) {
            List<String> under = a.list(path);
            out.put("count", under.size());
            out.put("first", under.stream().limit(5).toList());
        }
        return out;
    }

    /** The optional repeat count after {@code x y z}, at {@code at}: 1 when absent. */
    private static int times(List<String> args, int at) {
        return args.size() > at ? Math.max(1, Integer.parseInt(args.get(at))) : 1;
    }

    /** When the server has said how the chain went: that, and the block it was aimed at as core now sees it. */
    private CompletableFuture<Map<String, Object>> after(CompletableFuture<List<InteractionOutcome>> chain,
                                                         BlockPos look) {
        // The server's block update comes about with its word on the chain; the block is read a
        // moment later, so what it is then is the server's answer, not our guess.
        return chain.thenCompose(list -> CompletableFuture.supplyAsync(() -> list,
                CompletableFuture.delayedExecutor(150, java.util.concurrent.TimeUnit.MILLISECONDS)))
                .thenApply(list -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("outcome", outcomes(list));
            out.put("block", block(look));
            return out;
        });
    }

    private Map<String, Object> pos() {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<Player> p = world().player();
        out.put("available", p.isPresent());
        p.ifPresent(player -> {
            out.put("position", player.position());
            out.put("look", player.lookDirection());
            out.put("heldItem", player.heldItem());
            out.put("activeSlot", player.activeHotbarSlot());
        });
        return out;
    }

    private Map<String, Object> look() {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<BlockPos> at = player().lookedAtBlock();
        out.put("lookedAt", at.orElse(null));
        at.ifPresent(b -> out.put("block", block(b)));
        return out;
    }

    private Map<String, Object> block(BlockPos pos) {
        World w = world();
        Block b = w.blockAt(pos.x(), pos.y(), pos.z());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pos", pos);
        out.put("type", b.type() == null ? null : b.type().name());
        out.put("id", b.type() == null ? 0 : b.type().id());
        out.put("air", b.isAir());
        if (b.type() != null && !b.type().categories().isEmpty()) {
            out.put("categories", b.type().categories());
        }
        if (b.rotation() != 0) {
            out.put("rotation", b.rotation());
        }
        if (b.isFiller()) {
            out.put("filler", b.filler());
            out.put("base", b.base());
        }
        FluidCell fluid = w.fluidCellAt(pos.x(), pos.y(), pos.z());
        if (fluid != null) {
            out.put("fluid", fluid);
        }
        return out;
    }

    /**
     * Turns a headless client's head and body to the middle of a block, the way a player aims: a
     * movement at the spot it stands on, sent as the client's own. What is selected by where the
     * player looks - a shovel's area dig - is then aimed there. A game turns its own head.
     */
    private Map<String, Object> face(BlockPos target) {
        Map<String, Object> out = faceAt(new Vec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5));
        out.put("at", target);
        return out;
    }

    /** Turns a headless client to an entity's middle - where a swing at it aims. */
    private Map<String, Object> faceEntity(int entityId) {
        Vec3 at = entityTracker().positionOf(entityId)
                .orElseThrow(() -> new IllegalArgumentException("entity " + entityId + " is not in sight"));
        Map<String, Object> out = faceAt(new Vec3(at.x(), at.y() + 0.9, at.z()));
        out.put("entity", entityId);
        return out;
    }

    /** A headless client turns to the entity before acting on it; a game aims by itself. */
    private void faceIfHeadless(int entityId) {
        if (sessions.get().map(HeadlessClientHandler::headlessOf).isPresent()
                && entityTracker().positionOf(entityId).isPresent()) {
            faceEntity(entityId);
        }
    }

    private Map<String, Object> faceAt(Vec3 point) {
        meridian.api.session.ProxySession session = sessions.get()
                .orElseThrow(() -> new IllegalStateException("no session yet"));
        meridian.api.session.HeadlessClient client = HeadlessClientHandler.headlessOf(session);
        if (client == null) {
            throw new IllegalStateException("face turns a headless client; a game turns its own head");
        }
        Vec3 feet = player().position();
        double dx = point.x() - feet.x();
        double dy = point.y() - (feet.y() + EYE_HEIGHT);
        double dz = point.z() - feet.z();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) {
            throw new IllegalArgumentException("that is where the eyes are");
        }
        // The inverse of the look vector (-sin yaw cos pitch, sin pitch, -cos yaw cos pitch).
        float yaw = (float) Math.atan2(-dx / len, -dz / len);
        float pitch = (float) Math.asin(dy / len);
        meridian.protocol.packets.player.ClientMovement move = new meridian.protocol.packets.player.ClientMovement();
        move.absolutePosition = new meridian.protocol.Position(feet.x(), feet.y(), feet.z());
        move.bodyOrientation = new meridian.protocol.Direction(yaw, 0f, 0f);
        move.lookOrientation = new meridian.protocol.Direction(yaw, pitch, 0f);
        client.sendAsClient(move);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("yaw", yaw);
        out.put("pitch", pitch);
        return out;
    }

    /** The entities core sees within {@code range} of the player, nearest first. */
    private Object entities(double range) {
        EntityTracker tracker = entityTracker();
        Vec3 me = player().position();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int id : tracker.trackedEntities()) {
            Optional<Vec3> at = tracker.positionOf(id);
            if (at.isEmpty() || tracker.localEntityId().orElse(Integer.MIN_VALUE) == id) {
                continue;
            }
            double d = Math.sqrt(sq(at.get().x() - me.x()) + sq(at.get().y() - me.y()) + sq(at.get().z() - me.z()));
            if (d > range) {
                continue;
            }
            out.add(entity(id, d));
        }
        out.sort(java.util.Comparator.comparingDouble(m -> (Double) m.get("distance")));
        return out;
    }

    private Map<String, Object> entity(int id, double distance) {
        EntityTracker tracker = entityTracker();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("distance", Math.round(distance * 100) / 100.0);
        tracker.entityModelAssetId(id).ifPresent(m -> e.put("model", m));
        tracker.nameplateOf(id).ifPresent(n -> e.put("name", n));
        if (tracker.isPlayer(id)) {
            e.put("player", true);
        }
        tracker.positionOf(id).ifPresent(p -> e.put("position", p));
        if (tracker instanceof EntityTrackerImpl impl && !impl.interactionsOf(id).isEmpty()) {
            e.put("interactions", impl.interactionsOf(id));
        }
        return e;
    }

    /** When the server has said how the action went: that, and the entity as core now sees it, or that it is gone. */
    private CompletableFuture<Map<String, Object>> afterEntity(CompletableFuture<List<InteractionOutcome>> action,
                                                               int id) {
        return action.thenCompose(list -> CompletableFuture.supplyAsync(() -> list,
                CompletableFuture.delayedExecutor(150, java.util.concurrent.TimeUnit.MILLISECONDS)))
                .thenApply(list -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("outcome", outcomes(list));
                    Optional<Vec3> at = entityTracker().positionOf(id);
                    out.put("entity", at.isPresent() ? entity(id, distanceTo(at.get())) : Map.of("id", id, "gone", true));
                    return out;
                });
    }

    private double distanceTo(Vec3 p) {
        Vec3 me = player().position();
        return Math.sqrt(sq(p.x() - me.x()) + sq(p.y() - me.y()) + sq(p.z() - me.z()));
    }

    private static double sq(double v) {
        return v * v;
    }

    private EntityTracker entityTracker() {
        return ctx.services().get(EntityTracker.class)
                .orElseThrow(() -> new IllegalStateException("no EntityTracker service"));
    }

    /** A root interaction's operation list as the forging VM sees it, one line an operation. */
    private Object chain(int rootId) {
        if (interactions() instanceof meridian.core.impl.interaction.InteractionControlImpl impl) {
            return List.of(impl.describeRoot(rootId).split("\n"));
        }
        return "no forging VM here";
    }

    private Object stats() {
        PlayerState state = ctx.services().get(PlayerState.class).orElse(null);
        return state == null ? Map.of() : new java.util.TreeMap<>(state.stats());
    }

    private Object inventory() {
        PlayerState state = ctx.services().get(PlayerState.class).orElse(null);
        return state == null ? Map.of() : state.inventories();
    }

    // ------------------------------------------------------------------

    private World world() {
        return ctx.services().get(World.class).orElseThrow(() -> new IllegalStateException("no World service"));
    }

    private Player player() {
        return world().player().orElseThrow(() -> new IllegalStateException("no player yet"));
    }

    private InteractionControl interactions() {
        return ctx.services().get(InteractionControl.class)
                .orElseThrow(() -> new IllegalStateException("no InteractionControl service"));
    }

    private static BlockPos at(List<String> args, int from) {
        if (args.size() < from + 3) {
            throw new IllegalArgumentException("needs x y z");
        }
        return new BlockPos((int) Math.floor(Double.parseDouble(args.get(from))),
                (int) Math.floor(Double.parseDouble(args.get(from + 1))),
                (int) Math.floor(Double.parseDouble(args.get(from + 2))));
    }

    private static Face face(String name) {
        return Face.valueOf(name.toUpperCase(Locale.ROOT));
    }
}
