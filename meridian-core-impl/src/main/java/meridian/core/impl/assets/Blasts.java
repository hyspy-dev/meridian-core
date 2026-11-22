package meridian.core.impl.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import meridian.core.api.Combat;
import meridian.core.api.GameAssets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What explodes, and how far its blast reaches - read out of the game's own files. A thrown bomb is a
 * projectile: its config ({@code Server/ProjectileConfigs}) names its model and what it does when it is
 * thrown ({@code ProjectileSpawn}: a wait - the fuse - and then an explosion), when it hits something
 * and when it comes to rest; an explosion ({@code Type: Explode}, under {@code Server/Item/Interactions})
 * says how much it hurts and how far round ({@code Config.EntityDamage}, {@code EntityDamageRadius}),
 * taking what it does not say from its {@code Parent}. The server tells a client none of it - it shows
 * the bomb's model, and that is what a blast is looked up by.
 *
 * <p>A goblin lobber's bomb: {@code Bomb_Large_Fire_Goblin}, thirty to whatever is within six blocks,
 * two seconds after the throw - or at once, where it hits somebody.
 *
 * <p>The installed game's files, as with {@link NpcRoles}: read once per installation in the
 * background, an answer asked before that is empty.
 */
public final class Blasts {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");
    private static final String CONFIGS = "Server/ProjectileConfigs/";
    private static final String INTERACTIONS = "Server/Item/Interactions/";
    private static final String ROOTS = "Server/Item/RootInteractions/";
    /** A chain of parents, or of what follows what, longer than this is a loop in somebody's files. */
    private static final int DEEPEST = 12;

    /** Installation folder → its blasts by model name, being read or read. */
    private static final Map<String, CompletableFuture<Map<String, Combat.Blast>>> READ = new ConcurrentHashMap<>();

    private Blasts() {
    }

    /** The lookup by model name (an entity's model asset id) for the game {@code assets} reads from. */
    public static Function<String, Optional<Combat.Blast>> lookup(GameAssets assets, Executor executor) {
        CompletableFuture<Map<String, Combat.Blast>> table = CompletableFuture
                .supplyAsync(() -> assets.gameDirectory().map(Object::toString).orElse(""), executor)
                .thenCompose(key -> key.isEmpty()
                        ? CompletableFuture.completedFuture(Map.<String, Combat.Blast>of())
                        : READ.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> read(assets), executor)));
        return model -> {
            Map<String, Combat.Blast> known = table.isDone() && !table.isCompletedExceptionally() ? table.getNow(null) : null;
            return model == null || known == null
                    ? Optional.empty() : Optional.ofNullable(known.get(model.toLowerCase(Locale.ROOT)));
        };
    }

    /** One explosion a projectile's interactions lead to: how long after their start, and the explosion. */
    private record Found(double after, JsonObject explode) {
    }

    /** Every exploding projectile's blast by its model's name, lower case. */
    static Map<String, Combat.Blast> read(GameAssets assets) {
        long t0 = System.nanoTime();
        Map<String, JsonObject> configs = files(assets, CONFIGS);
        Map<String, JsonObject> interactions = files(assets, INTERACTIONS);
        Map<String, JsonObject> roots = files(assets, ROOTS);
        Map<String, Combat.Blast> out = new HashMap<>();
        for (String name : configs.keySet()) {
            JsonObject config = withParents(configs, name, 0);
            String model = text(config.get("Model"));
            JsonElement on = config.get("Interactions");
            if (model == null || model.isEmpty() || on == null || !on.isJsonObject()) {
                continue;
            }
            double radius = 0;
            double damage = 0;
            double fuse = -1;
            for (Map.Entry<String, JsonElement> when : on.getAsJsonObject().entrySet()) {
                JsonElement chain = when.getValue();
                if (chain.isJsonPrimitive()) {
                    JsonObject root = roots.get(chain.getAsString().toLowerCase(Locale.ROOT));
                    chain = root == null ? chain : root;
                }
                if (chain.isJsonObject() && chain.getAsJsonObject().has("Interactions")) {
                    chain = chain.getAsJsonObject().get("Interactions");
                }
                List<Found> found = new ArrayList<>();
                explosions(interactions, chain, 0, 0, found);
                for (Found f : found) {
                    JsonElement c = f.explode().get("Config");
                    JsonObject cfg = c != null && c.isJsonObject() ? c.getAsJsonObject() : new JsonObject();
                    if (cfg.has("DamageEntities") && !flag(cfg.get("DamageEntities"))) {
                        continue;
                    }
                    radius = Math.max(radius, number(cfg.get("EntityDamageRadius")));
                    damage = Math.max(damage, number(cfg.get("EntityDamage")));
                    if (when.getKey().equals("ProjectileSpawn")) {
                        fuse = fuse < 0 ? f.after() : Math.min(fuse, f.after());
                    }
                }
            }
            if (radius > 0) {
                Combat.Blast blast = new Combat.Blast(radius, damage, Math.max(0, fuse));
                out.merge(model.toLowerCase(Locale.ROOT), blast, (a, b) -> a.radius() >= b.radius() ? a : b);
            }
        }
        log.info("meridian-core: {} kind(s) of thing that explode read from {} projectile config(s) in {} ms",
                out.size(), configs.size(), (System.nanoTime() - t0) / 1_000_000);
        return out;
    }

    /** Every JSON object under a folder, by its file's name without the extension, lower case. */
    private static Map<String, JsonObject> files(GameAssets assets, String under) {
        Map<String, JsonObject> out = new HashMap<>();
        for (String path : assets.list(under)) {
            if (!path.endsWith(".json")) {
                continue;
            }
            try {
                byte[] bytes = assets.read(path).orElse(null);
                if (bytes == null) {
                    continue;
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                JsonElement e = JsonParser.parseString(text.startsWith("﻿") ? text.substring(1) : text);
                if (e.isJsonObject()) {
                    String name = path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length());
                    out.put(name.toLowerCase(Locale.ROOT), e.getAsJsonObject());
                }
            } catch (RuntimeException e) {
                // one file that does not parse is one thing not known
            }
        }
        return out;
    }

    /**
     * A named object with what its {@code Parent} chain says under its own: a field of its own wins;
     * {@code Config} and {@code Interactions} are laid over the parent's field by field.
     */
    private static JsonObject withParents(Map<String, JsonObject> all, String name, int depth) {
        JsonObject own = name == null ? null : all.get(name.toLowerCase(Locale.ROOT));
        return own == null || depth > DEEPEST ? new JsonObject() : withParents(all, own, depth);
    }

    private static JsonObject withParents(Map<String, JsonObject> all, JsonObject own, int depth) {
        JsonObject out = new JsonObject();
        String parent = text(own.get("Parent"));
        if (parent != null && depth <= DEEPEST) {
            for (Map.Entry<String, JsonElement> e : withParents(all, parent, depth + 1).entrySet()) {
                out.add(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, JsonElement> e : own.entrySet()) {
            JsonElement under = out.get(e.getKey());
            if ((e.getKey().equals("Config") || e.getKey().equals("Interactions")) && under != null && under.isJsonObject()
                    && e.getValue().isJsonObject()) {
                JsonObject merged = under.getAsJsonObject().deepCopy();
                for (Map.Entry<String, JsonElement> f : e.getValue().getAsJsonObject().entrySet()) {
                    merged.add(f.getKey(), f.getValue());
                }
                out.add(e.getKey(), merged);
            } else {
                out.add(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** An interaction - a name, or an object given in place - as it ends up with its parents' fields. */
    private static JsonObject interaction(Map<String, JsonObject> interactions, JsonElement e, int depth) {
        if (e == null || depth > DEEPEST) {
            return new JsonObject();
        }
        if (e.isJsonPrimitive()) {
            return withParents(interactions, e.getAsString(), depth);
        }
        return e.isJsonObject() ? withParents(interactions, e.getAsJsonObject(), depth) : new JsonObject();
    }

    /**
     * The explosions a chain leads to, each with the seconds that pass before it: a list is played in
     * order (a {@code Simple} with a {@code RunTime} is a wait - a bomb's fuse), an interaction goes on
     * to its {@code Next}.
     */
    private static void explosions(Map<String, JsonObject> interactions, JsonElement chain, double before, int depth,
                                   List<Found> out) {
        if (chain == null || depth > DEEPEST) {
            return;
        }
        if (chain.isJsonArray()) {
            double t = before;
            JsonArray list = chain.getAsJsonArray();
            for (JsonElement item : list) {
                explosions(interactions, item, t, depth + 1, out);
                JsonObject i = interaction(interactions, item, depth + 1);
                if ("Simple".equals(text(i.get("Type")))) {
                    t += number(i.get("RunTime"));
                }
            }
            return;
        }
        JsonObject i = interaction(interactions, chain, depth + 1);
        if (i.size() == 0) {
            return;
        }
        if ("Explode".equals(text(i.get("Type")))) {
            out.add(new Found(before, i));
        }
        double after = before + ("Simple".equals(text(i.get("Type"))) ? number(i.get("RunTime")) : 0);
        explosions(interactions, i.get("Next"), after, depth + 1, out);
        explosions(interactions, i.get("Interactions"), before, depth + 1, out);
    }

    private static String text(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static double number(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
    }

    private static boolean flag(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
