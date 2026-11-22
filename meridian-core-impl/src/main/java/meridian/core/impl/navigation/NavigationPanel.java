package meridian.core.impl.navigation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import meridian.api.module.ModuleContext;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.BlockPos;
import meridian.core.api.Goal;
import meridian.core.api.InteractionControl;
import meridian.core.api.Movement;
import meridian.core.api.NavResult;
import meridian.core.api.Navigator;
import meridian.core.api.Vec3;
import meridian.core.api.Worker;

/**
 * The navigator in the proxy's window and on its command channel. In the window: a point to go
 * to, filled from where the player stands or the block they last clicked, Go and Stop. On the
 * channel: {@code nav <conn> goto|plan <x y z> [radius]} and {@code nav <conn> cancel}. Go and
 * Stop end the work under way as well ({@link Worker#cancel}), whoever set it going.
 */
public final class NavigationPanel {

    private static final String USAGE = "goto <x y z> [radius] | plan <x y z> [radius] | trace <x y z> [radius] | cancel"
            + " | chase <entity> [within]"
            + " | place <x y z> <item> [rotation] | take <x y z> <item> <count> | deposit <x y z> <item> <count>"
            + " | travel <x y z> | pillar <item> <blocks> | down <blocks> | pour|scoop <x y z> <item>";

    private final Navigator navigator;
    private final Worker worker;
    private final Movement movement;
    private final InteractionControl interactions;
    private final SettingBinding<String> xBinding = new SettingBinding<>();
    private final SettingBinding<String> yBinding = new SettingBinding<>();
    private final SettingBinding<String> zBinding = new SettingBinding<>();
    private volatile String xText = "";
    private volatile String yText = "";
    private volatile String zText = "";
    private volatile String status = "Idle.";
    /** Where an entity is, for {@code nav chase}; none until core's module sets it. */
    private volatile java.util.function.IntFunction<java.util.Optional<meridian.core.api.Vec3>> entityAt =
            id -> java.util.Optional.empty();

    public NavigationPanel(Navigator navigator, Worker worker, Movement movement, InteractionControl interactions) {
        this.navigator = navigator;
        this.worker = worker;
        this.movement = movement;
        this.interactions = interactions;
    }

    /** Where an entity is now, for {@code nav chase <entity>}. */
    public void entities(java.util.function.IntFunction<java.util.Optional<meridian.core.api.Vec3>> where) {
        if (where != null) {
            entityAt = where;
        }
    }

    /** The "Go to a point" section, for core's settings. */
    public SettingsSpec section() {
        return SettingsSpec.builder()
                .string("x", "X", "", v -> xText = v == null ? "" : v, xBinding)
                .string("y", "Y", "", v -> yText = v == null ? "" : v, yBinding)
                .string("z", "Z", "", v -> zText = v == null ? "" : v, zBinding)
                .button("Fill from current position", () -> fill(movement.position()))
                .button("Fill from the last block I clicked", this::fillLooked)
                .button("Go", this::go)
                .button("Stop", () -> {
                    worker.cancel();
                    navigator.cancel();
                    status = "Stopped.";
                })
                .liveText("Status", () -> status)
                .build();
    }

    /** The {@code nav} command on the proxy's command channel. */
    public void register(ModuleContext ctx) {
        try {
            ctx.registerCommand("nav", USAGE, (args, json) -> command(args));
        } catch (NoSuchMethodError olderProxy) {
            // no command channel there
        }
    }

    /** Fills the point with where the feet are, as the cell they stand in. */
    private void fill(Vec3 at) {
        if (at == null) {
            status = "No position yet - join a world and move once.";
            return;
        }
        int[] c = Walker.cellOf(at);
        set(c[0], c[1], c[2]);
    }

    /** Fills the point with the cell over the block the player last clicked - where they would stand on it. */
    private void fillLooked() {
        BlockPos b = interactions == null ? null : interactions.targetedBlock().orElse(null);
        if (b == null) {
            status = "No block clicked yet - click one in the game.";
            return;
        }
        set(b.x(), b.y() + 1, b.z());
    }

    private void set(int x, int y, int z) {
        xText = Integer.toString(x);
        yText = Integer.toString(y);
        zText = Integer.toString(z);
        xBinding.set(xText);
        yBinding.set(yText);
        zBinding.set(zText);
    }

    /** Goes to the point in the fields; the status says how it went. */
    private void go() {
        BlockPos cell;
        try {
            cell = new BlockPos(Integer.parseInt(xText.trim()), Integer.parseInt(yText.trim()),
                    Integer.parseInt(zText.trim()));
        } catch (NumberFormatException e) {
            status = "X, Y and Z must be whole numbers.";
            return;
        }
        if (!navigator.available()) {
            status = "Cannot move the player yet - join a world and move once.";
            return;
        }
        worker.cancel();
        status = "Going to " + cell.x() + " " + cell.y() + " " + cell.z() + "...";
        navigator.goTo(Goal.near(cell, 0)).whenComplete((r, e) -> {
            if (e != null || r == null) {
                status = "Failed: " + (e == null ? "no result" : e.getMessage());
                return;
            }
            String where = r.at() == null ? "" : String.format(Locale.ROOT, " at %.1f %.1f %.1f",
                    r.at().x(), r.at().y(), r.at().z());
            status = switch (r.status()) {
                case ARRIVED -> "Arrived" + where + " (" + r.moves() + " moves).";
                case NO_PATH -> "No way there" + where + ".";
                case BLOCKED -> "Blocked on the way" + where + ".";
                case INTERRUPTED -> "Stopped" + where + ".";
                case NOT_AVAILABLE -> "Cannot move the player here.";
            };
        });
    }

    private CompletableFuture<?> command(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException(USAGE);
        }
        String what = args.get(0).toLowerCase(Locale.ROOT);
        if (what.equals("cancel")) {
            worker.cancel();
            navigator.cancel();
            return CompletableFuture.completedFuture(Map.of("cancelled", true));
        }
        if (what.equals("chase") && args.size() >= 2) {
            int entity = Integer.parseInt(args.get(1));
            double within = args.size() > 2 ? Double.parseDouble(args.get(2)) : 2;
            return navigator.chase(() -> entityAt.apply(entity), within).thenApply(NavigationPanel::result);
        }
        if (what.equals("travel") && args.size() >= 4) {
            // Anywhere: walked, dug, built - the worker's way.
            Goal goal = Goal.at(new BlockPos(Integer.parseInt(args.get(1)), Integer.parseInt(args.get(2)),
                    Integer.parseInt(args.get(3))));
            return worker.travel(goal).thenApply(NavigationPanel::result);
        }
        if (what.equals("down") && args.size() >= 2) {
            return worker.pillarDown(Integer.parseInt(args.get(1))).thenApply(o -> Map.of("outcome", o.name()));
        }
        if (what.equals("pillar") && args.size() >= 3) {
            return worker.pillarUp(args.get(1), Integer.parseInt(args.get(2))).thenApply(o -> Map.of("outcome", o.name()));
        }
        if (args.size() < 4) {
            throw new IllegalArgumentException(USAGE);
        }
        BlockPos cell = new BlockPos(Integer.parseInt(args.get(1)), Integer.parseInt(args.get(2)),
                Integer.parseInt(args.get(3)));
        if (what.equals("place")) {
            if (args.size() < 5) {
                throw new IllegalArgumentException(USAGE);
            }
            int rotation = args.size() > 5 ? Integer.parseInt(args.get(5)) : -1;
            return worker.place(cell, args.get(4), rotation).thenApply(o -> Map.of("outcome", o.name()));
        }
        if (what.equals("pour") || what.equals("scoop")) {
            if (args.size() < 5) {
                throw new IllegalArgumentException(USAGE);
            }
            return (what.equals("pour") ? worker.pour(cell, args.get(4)) : worker.scoop(cell, args.get(4)))
                    .thenApply(o -> Map.of("outcome", o.name()));
        }
        if (what.equals("deposit")) {
            if (args.size() < 6) {
                throw new IllegalArgumentException(USAGE);
            }
            return worker.deposit(cell, Map.of(args.get(4), Integer.parseInt(args.get(5))))
                    .thenApply(m -> Map.of("put", m));
        }
        if (what.equals("take")) {
            if (args.size() < 6) {
                throw new IllegalArgumentException(USAGE);
            }
            return worker.take(cell, Map.of(args.get(4), Integer.parseInt(args.get(5))))
                    .thenApply(m -> Map.of("taken", m));
        }
        double radius = args.size() > 4 ? Double.parseDouble(args.get(4)) : 0;
        Goal goal = Goal.near(cell, radius);
        return switch (what) {
            case "goto" -> {
                worker.cancel();
                yield navigator.goTo(goal).thenApply(NavigationPanel::result);
            }
            case "trace" -> CompletableFuture.supplyAsync(() -> navigator instanceof Walker w
                    ? w.trace(goal) : Map.of("error", "not core's walker"));
            case "plan" -> CompletableFuture.supplyAsync(() -> {
                List<Navigator.Step> steps = navigator.plan(goal);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("moves", steps.size());
                out.put("steps", steps.stream()
                        .map(s -> s.kind() + " " + s.x() + " " + s.y() + " " + s.z() + " " + s.gait()).toList());
                return out;
            });
            default -> throw new IllegalArgumentException(USAGE);
        };
    }

    private static Map<String, Object> result(NavResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status().name());
        if (r.at() != null) {
            out.put("at", Map.of("x", r.at().x(), "y", r.at().y(), "z", r.at().z()));
        }
        out.put("moves", r.moves());
        out.put("replans", r.replans());
        return out;
    }
}
