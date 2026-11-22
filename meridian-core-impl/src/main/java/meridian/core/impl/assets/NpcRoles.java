package meridian.core.impl.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import meridian.core.api.Combat;
import meridian.core.api.GameAssets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What an NPC senses - how far it sees and hears, how far it goes after what it was alerted by, how
 * fast it moves - read out of the game's own role files ({@code Server/NPC/Roles}). The server never
 * tells a client any of it: it only shows the NPC's model, and a role names its model
 * ({@code Appearance}), so that is what a kind is looked up by.
 *
 * <p>A role is a template with {@code Parameters} (name to {@code {"Value": ...}}), or a
 * {@code Variant} of another ({@code Reference}) that changes some of them ({@code Modify}), to any
 * depth. Several roles may wear one model (a variant with more health): the kind senses as far as the
 * keenest of them.
 *
 * <p>These are the installed game's files. A server may run roles of its own - they are not sent, and
 * cannot be read: what is answered is the game's word, not that server's.
 *
 * <p>Read once per installation and kept for the process, in the background; an answer asked before
 * the read is done is empty, not waited for - it is asked every beat of a fight.
 */
public final class NpcRoles {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");
    private static final String ROLES = "Server/NPC/Roles/";
    /** A reference chain longer than this is a loop in somebody's files. */
    private static final int DEEPEST = 16;

    /** Installation folder → its kinds' senses by model name, being read or read. */
    private static final Map<String, CompletableFuture<Map<String, Combat.Senses>>> READ = new ConcurrentHashMap<>();

    private NpcRoles() {
    }

    /** The lookup by model name (an entity's model asset id) for the game {@code assets} reads from. */
    public static Function<String, Optional<Combat.Senses>> lookup(GameAssets assets, Executor executor) {
        CompletableFuture<Map<String, Combat.Senses>> table = CompletableFuture
                .supplyAsync(() -> assets.gameDirectory().map(Object::toString).orElse(""), executor)
                .thenCompose(key -> key.isEmpty()
                        ? CompletableFuture.completedFuture(Map.<String, Combat.Senses>of())
                        : READ.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> read(assets), executor)));
        return model -> {
            Map<String, Combat.Senses> known = table.isDone() && !table.isCompletedExceptionally() ? table.getNow(null) : null;
            return model == null || known == null
                    ? Optional.empty() : Optional.ofNullable(known.get(model.toLowerCase(Locale.ROOT)));
        };
    }

    /** Every kind's senses by model name, lower case. */
    static Map<String, Combat.Senses> read(GameAssets assets) {
        long t0 = System.nanoTime();
        Map<String, JsonObject> roles = new HashMap<>();
        for (String path : assets.list(ROLES)) {
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
                    roles.put(name.toLowerCase(Locale.ROOT), e.getAsJsonObject());
                }
            } catch (RuntimeException e) {
                // one file that does not parse is one role not known
            }
        }
        Map<String, Combat.Senses> out = new HashMap<>();
        Map<String, Double> ownReach = new HashMap<>();
        Map<String, Double> leastReach = new HashMap<>();
        for (String name : roles.keySet()) {
            Map<String, JsonElement> p = parameters(roles, name, new HashSet<>(), 0);
            String model = text(p.get("Appearance"));
            double view = number(p.get("ViewRange"));
            if (model == null || model.isEmpty() || view <= 0) {
                continue;
            }
            Combat.Senses s = new Combat.Senses(view, number(p.get("ViewSector")), number(p.get("HearingRange")),
                    number(p.get("AlertedRange")), number(p.get("MaxSpeed")), number(p.get("AttackDistance")));
            String key = model.toLowerCase(Locale.ROOT);
            out.merge(key, s, NpcRoles::keenest);
            if (s.attackDistance() > 0) {
                if (name.equals(key)) {
                    ownReach.put(key, s.attackDistance());
                }
                leastReach.merge(key, s.attackDistance(), Math::min);
            }
        }
        // How far it attacks from is not "the most of them all", as what it senses is: a turret wears
        // the scrapper's model and throws from twenty-five blocks - that made every scrapper, who
        // hits from two and a half, "one that attacks from afar" (22.11). The role named as the model
        // is the kind itself; with none of that name, the least any role wearing it says.
        out.replaceAll((model, s) -> new Combat.Senses(s.viewRange(), s.viewSector(), s.hearingRange(), s.alertedRange(),
                s.maxSpeed(), ownReach.getOrDefault(model, leastReach.getOrDefault(model, 0.0))));
        log.info("meridian-core: what {} kind(s) of NPC sense read from {} role file(s) in {} ms",
                out.size(), roles.size(), (System.nanoTime() - t0) / 1_000_000);
        return out;
    }

    private static Combat.Senses keenest(Combat.Senses a, Combat.Senses b) {
        return new Combat.Senses(Math.max(a.viewRange(), b.viewRange()), Math.max(a.viewSector(), b.viewSector()),
                Math.max(a.hearingRange(), b.hearingRange()), Math.max(a.alertedRange(), b.alertedRange()),
                Math.max(a.maxSpeed(), b.maxSpeed()), Math.max(a.attackDistance(), b.attackDistance()));
    }

    /**
     * A role's parameters as it ends up with them: what it refers to first, then its own plain values
     * and its {@code Parameters}, then what it changes of the one it refers to ({@code Modify}). A
     * value that is computed ({@code {"Compute": ...}}) is not one to read.
     */
    private static Map<String, JsonElement> parameters(Map<String, JsonObject> roles, String name, Set<String> seen,
                                                       int depth) {
        Map<String, JsonElement> out = new HashMap<>();
        JsonObject role = roles.get(name.toLowerCase(Locale.ROOT));
        if (role == null || depth > DEEPEST || !seen.add(name.toLowerCase(Locale.ROOT))) {
            return out;
        }
        String reference = text(role.get("Reference"));
        if (reference != null) {
            out.putAll(parameters(roles, reference, seen, depth + 1));
        }
        for (Map.Entry<String, JsonElement> e : role.entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                out.put(e.getKey(), e.getValue());
            }
        }
        JsonElement own = role.get("Parameters");
        if (own != null && own.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : own.getAsJsonObject().entrySet()) {
                JsonElement v = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("Value") : null;
                if (v != null && v.isJsonPrimitive()) {
                    out.put(e.getKey(), v);
                }
            }
        }
        JsonElement modify = role.get("Modify");
        if (modify != null && modify.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : modify.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    private static String text(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static double number(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
    }
}
