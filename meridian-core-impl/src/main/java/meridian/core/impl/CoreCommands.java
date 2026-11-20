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

    static final String USAGE = "pos | block <x y z> | reach [item] | besttool <x y z> | placeat <x y z> [face] | chest <x y z> | known <x y z> | find <name-contains> [radius] [limit] | look | inv | stats | chain <rootId> | slot <n> | tp <x y z> | face <x y z>|<entity> | "
            + "use|plant|water <x y z> | hit|dig <x y z> [times] | break <x y z> [maxSwings] | place <x y z> [face] [orient] | "
            + "hits [seconds] | swings [seconds] | fightitem [item] | repair <item> | urgent <seconds> | wheel <seconds> | "
            + "swingguard <entity> [after <ms>] [hold <s>] [overpower] | "
            + "entities [range] [players] | attack <entity> [times] | useentity <entity> | useitem [times] [hold <s>] | swing [hold <s>] | dodge | assets | asset <path> | "
            + "movement | body | stand <x y z> | surface <x z> [wait <s>] | safe <x y z> [radius] | hurts <x y z> | move <walk|jump|mantle|drop|leap|swim|climb|fly> <x y z> [walk|run|sprint] | "
            + "predict <kind> <x y z> [gait] [trace] | hold <forward> <strafe> <yaw> [jump] [crouch] [sprint] [walk] [fly] | hold off | halt | "
            + "spoof [gamemode <creative|adventure|off> | flight|stamina|death|dropflying|swimming|nofall <on|off>] | "
            + "rotation <index> [rotate <x|y|z> [quarters] | mirror <x|y|z>] | matches <x y z> <name|id> [rotation] | "
            + "labels [show <entity> <rrggbb> <text> | hide <entity> | clear | font <size>] | "
            + "glow <name-contains> [r g b] | glow off | flip <block name> | "
            + "prefab capture <x0 y0 z0> <x1 y1 z1> <file> | prefab read <file> | "
            + "prefab turned <file> [rotate <x|y|z> [quarters] | flip <x|y|z>]...";

    private final ModuleContext ctx;
    private final SessionHolder sessions;
    private final Anonymizer anonymizer;
    private final EntityLabelsImpl labels;

    private CoreCommands(ModuleContext ctx, SessionHolder sessions, Anonymizer anonymizer, EntityLabelsImpl labels) {
        this.ctx = ctx;
        this.sessions = sessions;
        this.anonymizer = anonymizer;
        this.labels = labels;
    }

    /** Offers the commands; a proxy without a command channel - or too old to have one - has none. */
    static void register(ModuleContext ctx, SessionHolder sessions, Anonymizer anonymizer, EntityLabelsImpl labels) {
        try {
            CoreCommands c = new CoreCommands(ctx, sessions, anonymizer, labels);
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
            case "known" -> {
                BlockPos p = at(args, 1);
                yield CompletableFuture.completedFuture(Map.of("pos", p, "known", world().known(p.x(), p.y(), p.z())));
            }
            case "find" -> CompletableFuture.completedFuture(find(args));
            case "rotation" -> CompletableFuture.completedFuture(rotation(args));
            case "matches" -> CompletableFuture.completedFuture(matches(args));
            case "glow" -> CompletableFuture.completedFuture(glow(args));
            case "flip" -> CompletableFuture.completedFuture(flip(args));
            case "prefab" -> CompletableFuture.completedFuture(prefab(args));
            case "look" -> CompletableFuture.completedFuture(look());
            case "inv" -> CompletableFuture.completedFuture(inventory());
            case "stats" -> CompletableFuture.completedFuture(stats());
            case "anon" -> CompletableFuture.completedFuture(anon(args));
            case "labels" -> CompletableFuture.completedFuture(labels.command(args));
            case "hits" -> CompletableFuture.completedFuture(hits(args));
            case "swings" -> CompletableFuture.completedFuture(swings(args));
            case "fightitem" -> CompletableFuture.completedFuture(fightItem(args));
            case "repair" -> {
                if (args.size() < 2) {
                    throw new IllegalArgumentException("repair <item>");
                }
                yield interactions().repair(args.get(1)).thenApply(ok -> Map.of("repaired", ok));
            }
            case "urgent" -> urgent(args);
            case "wheel" -> wheel(args);
            case "swingguard" -> swingGuard(args);
            case "spoof" -> CompletableFuture.completedFuture(spoof(args));
            case "assets" -> CompletableFuture.completedFuture(assets());
            case "movement" -> CompletableFuture.completedFuture(movement());
            case "stand" -> CompletableFuture.completedFuture(stand(at(args, 1)));
            case "surface" -> surface(args);
            case "safe" -> {
                BlockPos p = at(args, 1);
                int radius = args.size() > 4 ? Integer.parseInt(args.get(4)) : 8;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("from", p);
                world().safeSpotNear(p.x(), p.y(), p.z(), radius).ifPresent(v -> out.put("spot", v));
                yield CompletableFuture.completedFuture(out);
            }
            case "hurts" -> {
                BlockPos p = at(args, 1);
                yield CompletableFuture.completedFuture(Map.of("pos", p,
                        "hurts", world().hurtsAt(new Vec3(p.x() + 0.5, p.y(), p.z() + 0.5))));
            }
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
            case "face" -> args.size() == 2 ? faceEntity(Integer.parseInt(args.get(1))) : face(at(args, 1));
            case "entities" -> CompletableFuture.completedFuture(
                    entities(args.size() > 1 ? Double.parseDouble(args.get(1)) : 32,
                            args.size() > 2 && args.get(2).equalsIgnoreCase("players")));
            case "attack" -> {
                int id = Integer.parseInt(args.get(1));
                int n = times(args, 2);
                yield faceIfHeadless(id).thenCompose(v -> afterEntity(repeat(n, () -> interactions().hitEntity(id)), id));
            }
            case "useentity" -> {
                int id = Integer.parseInt(args.get(1));
                yield faceIfHeadless(id).thenCompose(v -> afterEntity(interactions().useOnEntity(id).thenApply(List::of), id));
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
            case "reach" -> {
                // How far a dig and a use with an item reach (the held one without an id).
                String item = args.size() > 1 ? args.get(1) : world().player().map(Player::heldItem).orElse(null);
                java.util.OptionalDouble r = interactions().digReach(item);
                java.util.OptionalDouble use = interactions().useReach(item);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("item", item == null ? "" : item);
                out.put("reach", r.isPresent() ? r.getAsDouble() : -1);
                out.put("use", use.isPresent() ? use.getAsDouble() : -1);
                yield CompletableFuture.completedFuture(out);
            }
            case "besttool" -> {
                // The hotbar slot that breaks the block at x y z best.
                BlockPos p = at(args, 1);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("slot", world().player().map(pl -> pl.bestToolSlot(world().blockAt(p.x(), p.y(), p.z()).type()))
                        .orElse(-1));
                yield CompletableFuture.completedFuture(out);
            }
            case "break" -> {
                BlockPos p = at(args, 1);
                yield interactions().breakBlock(p, args.size() > 4 ? Integer.parseInt(args.get(4)) : 20)
                        .thenApply(broken -> {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("broken", broken);
                            out.put("block", block(p));
                            return out;
                        });
            }
            case "chest" -> {
                // What core last saw in the container at x y z, and which one was opened last.
                BlockPos p = at(args, 1);
                meridian.core.api.Containers c = ctx.services().get(meridian.core.api.Containers.class).orElse(null);
                Map<String, Object> out = new LinkedHashMap<>();
                meridian.core.api.Containers.Contents in = c == null ? null : c.at(p);
                List<Map<String, Object>> items = new java.util.ArrayList<>();
                if (in != null) {
                    in.items().forEach((slot, item) -> items.add(Map.of("slot", slot, "id", item.id(),
                            "quantity", item.quantity())));
                }
                out.put("seen", in != null);
                out.put("items", items);
                out.put("last", c == null ? null : c.lastOpened().orElse(null));
                yield CompletableFuture.completedFuture(out);
            }
            case "placeat" -> {
                // The held block into an empty cell, against whichever neighbour it has.
                BlockPos cell = at(args, 1);
                Face orient = args.size() > 4 ? face(args.get(4)) : null;
                yield after(world().placeAt(cell, orient).thenApply(List::of), cell);
            }
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
        java.util.OptionalDouble feet = world().standAt(cell);
        out.put("stands", feet.isPresent());
        feet.ifPresent(f -> out.put("feet", f));
        out.put("known", world().known(cell.x(), cell.y(), cell.z()));
        return out;
    }

    /**
     * {@code surface <x z> [wait <seconds>]}: the height to stand at on top of that column, or that
     * it is not known yet - with {@code wait}, once it is (or the wait is over).
     */
    private CompletableFuture<Map<String, Object>> surface(List<String> args) {
        if (args.size() < 3) {
            throw new IllegalArgumentException("surface <x z> [wait <seconds>]");
        }
        int x = (int) Math.floor(Double.parseDouble(args.get(1)));
        int z = (int) Math.floor(Double.parseDouble(args.get(2)));
        int at = args.indexOf("wait");
        CompletableFuture<java.util.OptionalInt> y = at > 0 && at + 1 < args.size()
                ? world().awaitSurface(x, z, java.time.Duration.ofMillis((long) (Double.parseDouble(args.get(at + 1)) * 1000)))
                : CompletableFuture.completedFuture(world().surfaceAt(x, z));
        return y.thenApply(feet -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("x", x);
            out.put("z", z);
            out.put("known", feet.isPresent());
            feet.ifPresent(f -> out.put("feet", f));
            return out;
        });
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

    private meridian.core.api.Movement.Move moveOf(meridian.core.api.Movement m, List<String> args) {
        meridian.core.api.Movement.Kind kind = meridian.core.api.Movement.Kind.valueOf(args.get(1).toUpperCase(Locale.ROOT));
        BlockPos cell = at(args, 2);
        meridian.core.api.Movement.Gait gait = args.size() > 5
                ? meridian.core.api.Movement.Gait.valueOf(args.get(5).toUpperCase(Locale.ROOT))
                : meridian.core.api.Movement.Gait.RUN;
        java.util.OptionalDouble feet = world().standAt(cell);
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
        out.put("pitch", s.pitch());
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
        out.put("holds", movementService().holds());
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
        if (b.type() != null && !b.type().states().isEmpty()) {
            out.put("state", b.type().state());
            out.put("family", b.type().family());
            out.put("states", b.type().states());
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
     * Turns a headless client's head and body to the middle of a block, the way a player aims -
     * the body's next report carries the look ({@link meridian.core.api.Movement#face}). What is
     * selected by where the player looks - a shovel's area dig - is then aimed there. A game turns
     * its own head.
     */
    private CompletableFuture<Map<String, Object>> face(BlockPos target) {
        return faceAt(new Vec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5)).thenApply(out -> {
            out.put("at", target);
            return out;
        });
    }

    /** Turns a headless client to an entity's middle - where a swing at it aims. */
    private CompletableFuture<Map<String, Object>> faceEntity(int entityId) {
        Vec3 at = entityTracker().positionOf(entityId)
                .orElseThrow(() -> new IllegalArgumentException("entity " + entityId + " is not in sight"));
        return faceAt(new Vec3(at.x(), at.y() + 0.9, at.z())).thenApply(out -> {
            out.put("entity", entityId);
            return out;
        });
    }

    /** A headless client turns to the entity before acting on it; a game aims by itself. */
    private CompletableFuture<?> faceIfHeadless(int entityId) {
        if (movementService().available() && entityTracker().positionOf(entityId).isPresent()) {
            return faceEntity(entityId);
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Map<String, Object>> faceAt(Vec3 point) {
        meridian.core.api.Movement m = movementService();
        if (!m.available()) {
            throw new IllegalStateException("face turns a headless client; a game turns its own head");
        }
        Vec3 eyes = player().eyePosition();
        if (eyes != null && eyes.distanceSqTo(point) < 1e-12) {
            throw new IllegalArgumentException("that is where the eyes are");
        }
        return m.face(point).thenApply(turned -> {
            meridian.core.api.Movement.State s = m.state();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("turned", turned);
            out.put("yaw", s.yaw());
            out.put("pitch", s.pitch());
            return out;
        });
    }

    /** The entities core sees within {@code range} of the player, nearest first. */
    private Object entities(double range, boolean playersOnly) {
        EntityTracker tracker = entityTracker();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int id : tracker.nearby(range, playersOnly)) {
            tracker.positionOf(id).ifPresent(at -> out.add(entity(id, distanceTo(at))));
        }
        return out;
    }

    /**
     * {@code find <name-contains> [radius] [limit]}: the blocks whose type name contains the text
     * (case counts), nearest the feet first.
     */
    private Object find(List<String> args) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("find <name-contains> [radius] [limit]");
        }
        String part = args.get(1);
        double radius = args.size() > 2 ? Double.parseDouble(args.get(2)) : 16;
        int limit = args.size() > 3 ? Integer.parseInt(args.get(3)) : 10;
        Vec3 feet = player().position();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Block b : world().find(feet, radius, t -> t.name() != null && t.name().contains(part), limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pos", new BlockPos(b.x(), b.y(), b.z()));
            m.put("name", b.type().name());
            m.put("distance", Math.round(Math.sqrt(sq(b.x() + 0.5 - feet.x()) + sq(b.y() + 0.5 - feet.y())
                    + sq(b.z() + 0.5 - feet.z())) * 100) / 100.0);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> entity(int id, double distance) {
        EntityTracker tracker = entityTracker();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("label", tracker.labelOf(id));
        e.put("distance", Math.round(distance * 100) / 100.0);
        tracker.entityModelAssetId(id).ifPresent(m -> e.put("model", m));
        tracker.nameplateOf(id).ifPresent(n -> e.put("name", n));
        tracker.itemOf(id).ifPresent(i -> e.put("item", i));
        if (tracker.isPlayer(id)) {
            e.put("player", true);
        }
        tracker.positionOf(id).ifPresent(p -> e.put("position", p));
        tracker.boundsOf(id).ifPresent(b -> e.put("box", b));
        tracker.healthOf(id).ifPresent(h -> e.put("health", Map.of("value", h.value(), "max", h.max())));
        tracker.heldItemOf(id).ifPresent(h -> e.put("held", h));
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

    private meridian.core.api.Combat combat() {
        return ctx.services().get(meridian.core.api.Combat.class)
                .orElseThrow(() -> new IllegalStateException("no Combat service"));
    }

    /** {@code hits [seconds]}: the hits the player took, oldest first. */
    private Object hits(List<String> args) {
        java.time.Duration within = java.time.Duration.ofMillis((long) (1000 * (args.size() > 1
                ? Double.parseDouble(args.get(1)) : 60)));
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (meridian.core.api.Combat.Hit h : combat().hits(within)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ago", (now - h.at()) / 1000.0);
            m.put("attacker", h.attacker());
            if (h.attacker() >= 0) {
                m.put("label", entityTracker().labelOf(h.attacker()));
            }
            m.put("amount", h.amount());
            m.put("cause", h.cause());
            m.put("from", h.from());
            out.add(m);
        }
        return out;
    }

    /** {@code swings [seconds]}: the attacks other entities began, oldest first. */
    private Object swings(List<String> args) {
        java.time.Duration within = java.time.Duration.ofMillis((long) (1000 * (args.size() > 1
                ? Double.parseDouble(args.get(1)) : 60)));
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (meridian.core.api.Combat.Swing s : combat().swings(within)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ago", (now - s.at()) / 1000.0);
            m.put("entity", s.entity());
            m.put("label", entityTracker().labelOf(s.entity()));
            m.put("type", s.type());
            m.put("chain", s.chain());
            out.add(m);
        }
        return out;
    }

    /** {@code fightitem [item]}: what an item (the held one) does in a fight. */
    private Object fightItem(List<String> args) {
        String item = args.size() > 1 ? args.get(1) : world().player().map(Player::heldItem).orElse(null);
        InteractionControl hands = interactions();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", item);
        java.util.OptionalDouble reach = hands.hitReach(item);
        if (reach.isPresent()) {
            out.put("hitReach", reach.getAsDouble());
        }
        out.put("guards", hands.guards(item));
        hands.heal(item).ifPresent(h -> out.put("heal", Map.of("now", h.now(), "overTime", h.overTime(),
                "seconds", h.seconds())));
        world().player().ifPresent(p -> out.put("bestWeaponSlot", p.bestWeaponSlot()));
        return out;
    }

    /** {@code urgent <seconds>}: an urgent hold on the body for that long - what it holds back waits. */
    private CompletableFuture<?> urgent(List<String> args) {
        double seconds = args.size() > 1 ? Double.parseDouble(args.get(1)) : 2;
        meridian.core.api.Movement.Wheel w = movementService().takeFirst().orElse(null);
        if (w == null) {
            return CompletableFuture.completedFuture(Map.of("taken", false));
        }
        return CompletableFuture.supplyAsync(() -> {
            w.release();
            return Map.of("taken", true, "held", seconds);
        }, CompletableFuture.delayedExecutor((long) (seconds * 1000), java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    /**
     * {@code wheel <seconds>}: an ordinary hold on the body for that long - as a job holds it (a
     * miner's, a builder's); answered when given back, with the holds counted while it was held.
     */
    private CompletableFuture<?> wheel(List<String> args) {
        double seconds = args.size() > 1 ? Double.parseDouble(args.get(1)) : 2;
        meridian.core.api.Movement m = movementService();
        meridian.core.api.Movement.Wheel w = m.take().orElse(null);
        if (w == null) {
            return CompletableFuture.completedFuture(Map.of("taken", false));
        }
        int counted = m.holds();
        return CompletableFuture.supplyAsync(() -> {
            w.release();
            return Map.of("taken", true, "held", seconds, "holdsWhileHeld", counted, "holdsAfter", m.holds());
        }, CompletableFuture.delayedExecutor((long) (seconds * 1000), java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    /**
     * {@code swingguard <entity> [after <ms>] [hold <s>] [overpower]}: a swing at the entity, and
     * {@code after} ms into it (default 40) the guard raised for {@code hold} s (default 0.65) - cutting
     * the swing short where its rules let a guard (the game's way), or with {@code overpower} after it.
     * Answers both outcomes and the entity's health before and after.
     */
    private CompletableFuture<?> swingGuard(List<String> args) {
        int id = Integer.parseInt(args.get(1));
        int at = args.indexOf("after");
        long after = at >= 0 && at + 1 < args.size() ? Long.parseLong(args.get(at + 1)) : 40;
        java.time.Duration hold = hold(args);
        java.time.Duration guardFor = hold == null ? java.time.Duration.ofMillis(650) : hold;
        boolean interrupt = !args.contains("overpower");
        Object before = entityTracker().healthOf(id).map(h -> (Object) h.value()).orElse("unknown");
        return faceIfHeadless(id).thenCompose(v -> {
            CompletableFuture<InteractionOutcome> swing = interactions().hitEntity(id);
            CompletableFuture<InteractionOutcome> guard = CompletableFuture.supplyAsync(() -> null,
                            CompletableFuture.delayedExecutor(after, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .thenCompose(x -> interactions().useItem(guardFor, interrupt));
            return swing.thenCombine(guard, (s, g) -> List.of(s, g));
        }).thenCompose(list -> CompletableFuture.supplyAsync(() -> list,
                CompletableFuture.delayedExecutor(300, java.util.concurrent.TimeUnit.MILLISECONDS)))
                .thenApply(list -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("interrupt", interrupt);
                    out.put("swing", outcome(list.get(0)));
                    out.put("guard", outcome(list.get(1)));
                    out.put("healthBefore", before);
                    out.put("healthAfter", entityTracker().healthOf(id).map(h -> (Object) h.value()).orElse("unknown"));
                    return out;
                });
    }

    /** {@code anon [on|off]}: "Anonymise players" switched, and the names known; {@code anon star <text>} starred. */
    private Object anon(List<String> args) {
        Anonymizer a = anonymizer;
        if (args.size() >= 2 && args.get(1).equalsIgnoreCase("star")) {
            return Map.of("text", a.star(String.join(" ", args.subList(2, args.size()))));
        }
        if (args.size() >= 2) {
            a.setEnabled(args.get(1).equalsIgnoreCase("on") || args.get(1).equalsIgnoreCase("true"));
        }
        return Map.of("enabled", a.isEnabled(), "names", new java.util.TreeSet<>(a.names()));
    }

    private Object stats() {
        PlayerState state = ctx.services().get(PlayerState.class).orElse(null);
        return state == null ? Map.of() : new java.util.TreeMap<>(state.stats());
    }

    /**
     * {@code spoof} answers what is spoofed; {@code spoof gamemode creative|adventure|off} holds the
     * client's game mode or lets go; {@code spoof <what> on|off} switches one of the others.
     */
    private Object spoof(List<String> args) {
        meridian.core.api.ClientSpoof spoof = ctx.services().get(meridian.core.api.ClientSpoof.class)
                .orElseThrow(() -> new IllegalStateException("no ClientSpoof service"));
        if (args.size() >= 3) {
            String what = args.get(1).toLowerCase(Locale.ROOT);
            String value = args.get(2).toLowerCase(Locale.ROOT);
            boolean on = value.equals("on");
            if (!on && !value.equals("off") && !what.equals("gamemode")) {
                throw new IllegalArgumentException("spoof " + what + " on|off");
            }
            switch (what) {
                case "gamemode" -> spoof.holdGameMode(value.equals("off") ? null
                        : meridian.core.api.ClientSpoof.GameMode.valueOf(value.toUpperCase(Locale.ROOT)));
                case "flight" -> spoof.setFlight(on);
                case "stamina" -> spoof.setFullStamina(on);
                case "death" -> spoof.setIgnoreDeath(on);
                case "dropflying" -> spoof.setDropFlying(on);
                case "swimming" -> spoof.setAddSwimming(on);
                case "nofall" -> spoof.setNoFall(on);
                default -> throw new IllegalArgumentException("spoof: gamemode|flight|stamina|death|dropflying|swimming|nofall");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.OptionalInt self = entityTracker().localEntityId();
        if (self.isPresent()) {
            out.put("self", self.getAsInt());   // the player's entity id, for reading the traffic
        }
        out.put("gamemode", spoof.heldGameMode().map(Enum::name).orElse("off"));
        out.put("serverGamemode", spoof.serverGameMode().map(Enum::name).orElse("?"));
        out.put("flight", spoof.flight());
        out.put("stamina", spoof.fullStamina());
        out.put("death", spoof.ignoreDeath());
        out.put("deathHidden", spoof.deathHidden());
        out.put("dropflying", spoof.dropFlying());
        out.put("swimming", spoof.addSwimming());
        out.put("nofall", spoof.noFall());
        return out;
    }

    private Object inventory() {
        PlayerState state = ctx.services().get(PlayerState.class).orElse(null);
        return state == null ? Map.of() : state.inventories();
    }

    // ------------------------------------------------------------------

    /** A block type's flip type, as core read it from the game's files ({@code BlockView.flip}). */
    private Map<String, Object> flip(List<String> args) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("flip <block name>");
        }
        meridian.core.api.WorldState ws = ctx.services().get(meridian.core.api.WorldState.class)
                .orElseThrow(() -> new IllegalStateException("no WorldState"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", args.get(1));
        java.util.OptionalInt id = ws.blockId(args.get(1));
        out.put("id", id.isPresent() ? id.getAsInt() : -1);
        for (meridian.core.api.BlockView t : ws.allBlockTypes()) {
            if (id.isPresent() && t.id() == id.getAsInt()) {
                out.put("flip", t.flip().name());
            }
        }
        return out;
    }

    /**
     * Core's {@link meridian.core.api.Prefabs}: a box captured and written, a file read back, and a
     * file resolved and turned as core's {@link meridian.core.api.Clipboard} turns it - each block by
     * name, so it can be laid beside what the server pastes.
     */
    private Map<String, Object> prefab(List<String> args) {
        meridian.core.api.Prefabs prefabs = ctx.services().get(meridian.core.api.Prefabs.class)
                .orElseThrow(() -> new IllegalStateException("no Prefabs"));
        if (args.size() < 3) {
            throw new IllegalArgumentException("prefab capture|read|turned ...");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            switch (args.get(1).toLowerCase(Locale.ROOT)) {
                case "capture" -> {
                    if (args.size() < 9) {
                        throw new IllegalArgumentException("prefab capture <x0 y0 z0> <x1 y1 z1> <file>");
                    }
                    meridian.core.api.Prefabs.Prefab p = prefabs.capture(at(args, 2), at(args, 5));
                    java.nio.file.Path file = java.nio.file.Path.of(args.get(8));
                    prefabs.write(file, p);
                    out.put("file", file.toAbsolutePath().toString());
                    out.put("blocks", cells(p));
                    out.put("fluids", p.fluids().size());
                }
                case "read" -> {
                    meridian.core.api.Prefabs.Prefab p = prefabs.read(java.nio.file.Path.of(args.get(2)));
                    out.put("anchor", List.of(p.anchorX(), p.anchorY(), p.anchorZ()));
                    out.put("blocks", cells(p));
                    out.put("fluids", p.fluids().size());
                }
                case "turned" -> {
                    meridian.core.api.Prefabs.Resolved r = prefabs.resolve(prefabs.read(java.nio.file.Path.of(args.get(2))));
                    meridian.core.api.Clipboard c = r.clipboard();
                    meridian.core.api.WorldState ws = ctx.services().get(meridian.core.api.WorldState.class).orElseThrow();
                    Map<Integer, meridian.core.api.BlockView> types = new java.util.HashMap<>();
                    for (meridian.core.api.BlockView t : ws.allBlockTypes()) {
                        types.put(t.id(), t);
                    }
                    for (int i = 3; i < args.size(); ) {
                        String op = args.get(i).toLowerCase(Locale.ROOT);
                        int axis = axisOf(args.get(i + 1));
                        if (op.equals("rotate")) {
                            int quarters = 1;
                            if (i + 2 < args.size() && args.get(i + 2).matches("-?\\d+")) {
                                quarters = Integer.parseInt(args.get(i + 2));
                                i++;
                            }
                            c = c.turned(axis, quarters);
                        } else if (op.equals("flip")) {
                            c = c.flipped(axis, id -> {
                                meridian.core.api.BlockView t = types.get(id);
                                return t == null ? null : t.flip();
                            });
                        } else {
                            throw new IllegalArgumentException("rotate or flip, not " + op);
                        }
                        i += 2;
                    }
                    List<List<Object>> blocks = new java.util.ArrayList<>();
                    for (meridian.core.api.PastePreview.Change ch : c.blocks()) {
                        meridian.core.api.BlockView t = types.get(ch.blockId());
                        blocks.add(List.of(ch.dx(), ch.dy(), ch.dz(), t == null ? String.valueOf(ch.blockId()) : t.name(),
                                ch.rotation()));
                    }
                    out.put("blocks", blocks);
                    out.put("empty", r.empty());
                    out.put("unknown", r.unknown());
                }
                default -> throw new IllegalArgumentException("prefab capture|read|turned");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.toString(), e);
        }
        return out;
    }

    private static List<List<Object>> cells(meridian.core.api.Prefabs.Prefab p) {
        List<List<Object>> out = new java.util.ArrayList<>();
        for (meridian.core.api.Prefabs.Cell c : p.blocks()) {
            out.add(List.of(c.x(), c.y(), c.z(), c.name(), c.rotation(), c.filler()));
        }
        return out;
    }

    private static int axisOf(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "x" -> meridian.core.api.Rotation.X;
            case "y" -> meridian.core.api.Rotation.Y;
            case "z" -> meridian.core.api.Rotation.Z;
            default -> throw new IllegalArgumentException("axis x, y or z");
        };
    }

    /** What core's {@link meridian.core.api.Rotation} makes of an orientation number, and of turning it. */
    private static Map<String, Object> rotation(List<String> args) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("rotation <index> [rotate <axis> [quarters] | mirror <axis>]");
        }
        int index = Integer.parseInt(args.get(1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", index);
        out.put("yaw", meridian.core.api.Rotation.yaw(index));
        out.put("pitch", meridian.core.api.Rotation.pitch(index));
        out.put("roll", meridian.core.api.Rotation.roll(index));
        out.put("canonical", meridian.core.api.Rotation.canonical(index));
        if (args.size() >= 4) {
            int axis = switch (args.get(3).toLowerCase(Locale.ROOT)) {
                case "x" -> meridian.core.api.Rotation.X;
                case "y" -> meridian.core.api.Rotation.Y;
                case "z" -> meridian.core.api.Rotation.Z;
                default -> throw new IllegalArgumentException("axis x, y or z");
            };
            switch (args.get(2).toLowerCase(Locale.ROOT)) {
                case "rotate" -> out.put("result", meridian.core.api.Rotation.rotate(index, axis,
                        args.size() > 4 ? Integer.parseInt(args.get(4)) : 1));
                case "mirror" -> out.put("result", meridian.core.api.Rotation.mirror(index, axis));
                default -> throw new IllegalArgumentException("rotate or mirror");
            }
        }
        return out;
    }

    /** {@link Block#matches} of a cell against a block by name or id, facing a given way (its own by default). */
    private Map<String, Object> matches(List<String> args) {
        if (args.size() < 5) {
            throw new IllegalArgumentException("matches <x y z> <name|id> [rotation]");
        }
        BlockPos p = at(args, 1);
        Block b = world().blockAt(p.x(), p.y(), p.z());
        String what = args.get(4);
        int id;
        if (what.chars().allMatch(Character::isDigit)) {
            id = Integer.parseInt(what);
        } else {
            meridian.core.api.WorldState ws = ctx.services().get(meridian.core.api.WorldState.class)
                    .orElseThrow(() -> new IllegalStateException("no WorldState service"));
            id = ws.blockId(what).orElseThrow(() -> new IllegalArgumentException("no block type " + what));
        }
        int rotation = args.size() > 5 ? Integer.parseInt(args.get(5)) : b.rotation();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pos", p);
        out.put("id", id);
        out.put("rotation", rotation);
        out.put("matches", b.matches(id, rotation));
        return out;
    }

    /** The rule key the {@code glow} command owns. */
    private static final String GLOW_RULE = "core-commands:glow";

    /**
     * A rule over every block type for the stand: the types whose name contains the text glow, or
     * no rule at all. Answers how many types the rule touches - what an emit of it should carry.
     */
    private Map<String, Object> glow(List<String> args) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("glow <name-contains> [r g b] | glow off");
        }
        meridian.core.api.WorldState ws = ctx.services().get(meridian.core.api.WorldState.class)
                .orElseThrow(() -> new IllegalStateException("no WorldState service"));
        Map<String, Object> out = new LinkedHashMap<>();
        String text = args.get(1);
        if ("off".equalsIgnoreCase(text)) {
            ws.clearOverrideAll(GLOW_RULE);
            out.put("rule", "off");
            return out;
        }
        int r = args.size() > 4 ? Integer.parseInt(args.get(2)) : 15;
        int g = args.size() > 4 ? Integer.parseInt(args.get(3)) : 15;
        int bl = args.size() > 4 ? Integer.parseInt(args.get(4)) : 15;
        ws.overrideAllBlockTypes(GLOW_RULE, v -> v.name() != null && v.name().contains(text)
                ? v.withLight(1, r, g, bl) : v);
        List<Integer> ids = ws.allBlockTypes().stream()
                .filter(v -> v.name() != null && v.name().contains(text))
                .map(meridian.core.api.BlockView::id).sorted().toList();
        out.put("rule", text);
        out.put("types", ids);
        out.put("catalog", ws.allBlockTypes().size());
        return out;
    }

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
