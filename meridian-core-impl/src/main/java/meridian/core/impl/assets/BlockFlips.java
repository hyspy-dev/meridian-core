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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import meridian.core.api.GameAssets;
import meridian.core.api.Rotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every block type's {@code FlipType}, read out of the game's own files - the one thing about a
 * block's mirroring the server never tells the client.
 *
 * <p>Where the server gets it ({@code BlockType.CODEC}): {@code "FlipType"}, inherited - an item's
 * {@code BlockType} takes its parent from its own {@code "Parent"}, else from the item's parent
 * ({@code ContainedAssetCodec.Mode.INHERIT_ID_AND_PARENT}); each {@code State.Definitions} entry is
 * a block type of its own, {@code *<block>_State_Definitions_<state>}, whose parent is the block it
 * is declared in ({@code INJECT_PARENT}). Block types are the items' ({@code Server/Item/Items}) and
 * the stand-alone ones ({@code Server/Item/Block/Blocks}); names are matched as the server's asset
 * map matches them, ignoring case. Unset all the way up is {@code SYMMETRIC}, the codec's default.
 *
 * <p>Read once per installation and kept for the process: a few thousand small files, in the
 * background, long before anyone mirrors a blueprint.
 */
public final class BlockFlips {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    private static final String ITEMS = "Server/Item/Items/";
    private static final String BLOCKS = "Server/Item/Block/Blocks/";
    private static final String STATE_INFIX = "_State_Definitions_";

    /** Installation folder → its flip types, being read or read. */
    private static final Map<String, CompletableFuture<Map<String, Rotation.Flip>>> READ = new ConcurrentHashMap<>();

    private BlockFlips() {
    }

    /**
     * The lookup by block name for the game {@code assets} reads from: starts reading on {@code
     * executor} (once per installation), and answers {@code SYMMETRIC} for a name the game does not
     * define. A lookup made before the read is done waits for it.
     */
    public static Function<String, Rotation.Flip> lookup(GameAssets assets, Executor executor) {
        CompletableFuture<Map<String, Rotation.Flip>> table = CompletableFuture
                .supplyAsync(() -> assets.gameDirectory().map(Object::toString).orElse(""), executor)
                .thenCompose(key -> key.isEmpty()
                        ? CompletableFuture.completedFuture(Map.<String, Rotation.Flip>of())
                        : READ.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> read(assets), executor)));
        return name -> {
            if (name == null) {
                return Rotation.Flip.SYMMETRIC;
            }
            try {
                Rotation.Flip f = table.get(30, TimeUnit.SECONDS).get(name.toLowerCase(Locale.ROOT));
                return f == null ? Rotation.Flip.SYMMETRIC : f;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Rotation.Flip.SYMMETRIC;
            } catch (Exception e) {
                return Rotation.Flip.SYMMETRIC;
            }
        };
    }

    // ------------------------------------------------------------------

    /** One block type as declared: its own flip type if it sets one, and where it inherits from. */
    private record Declared(Rotation.Flip own, String parent) {}

    static Map<String, Rotation.Flip> read(GameAssets assets) {
        long t0 = System.nanoTime();
        Map<String, Declared> declared = new HashMap<>();
        Map<String, String> itemParent = new HashMap<>();
        Set<String> itemsWithBlock = new HashSet<>();
        int files = 0;
        for (String path : assets.list(ITEMS)) {
            if (!path.endsWith(".json")) {
                continue;
            }
            JsonObject item = json(assets, path);
            if (item == null) {
                continue;
            }
            files++;
            String id = key(fileName(path));
            String parent = string(item, "Parent");
            if (parent != null) {
                itemParent.put(id, key(parent));
            }
            JsonElement block = item.get("BlockType");
            if (block != null && block.isJsonObject()) {
                itemsWithBlock.add(id);
                declare(declared, id, block.getAsJsonObject(), null);
            }
        }
        for (String path : assets.list(BLOCKS)) {
            if (!path.endsWith(".json")) {
                continue;
            }
            JsonObject block = json(assets, path);
            if (block != null) {
                files++;
                declare(declared, key(fileName(path)), block, null);
            }
        }
        // An item's block type with no parent of its own takes the item's parent's block type.
        for (String id : itemsWithBlock) {
            Declared d = declared.get(id);
            if (d != null && d.parent() == null) {
                String parentItem = itemParent.get(id);
                if (parentItem != null && declared.containsKey(parentItem)) {
                    declared.put(id, new Declared(d.own(), parentItem));
                }
            }
        }
        Map<String, Rotation.Flip> out = new HashMap<>(declared.size() * 2);
        for (String name : declared.keySet()) {
            out.put(name, resolve(name, declared, new HashSet<>()));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long turned = out.values().stream().filter(f -> f != Rotation.Flip.SYMMETRIC).count();
        log.info("meridian-core: flip types of {} block types read from {} files ({} not symmetric) in {} ms",
                out.size(), files, turned, ms);
        return Map.copyOf(out);
    }

    /** Records a block type and the state types declared in it. */
    private static void declare(Map<String, Declared> into, String key, JsonObject doc, String containing) {
        String parent = string(doc, "Parent");
        into.put(key, new Declared(flipOf(string(doc, "FlipType")),
                parent != null ? key(parent) : containing));
        JsonElement state = doc.get("State");
        if (state == null || !state.isJsonObject()) {
            return;
        }
        JsonElement definitions = state.getAsJsonObject().get("Definitions");
        if (definitions == null || !definitions.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> def : definitions.getAsJsonObject().entrySet()) {
            if (def.getValue().isJsonObject()) {
                declare(into, "*" + key + STATE_INFIX.toLowerCase(Locale.ROOT) + key(def.getKey()),
                        def.getValue().getAsJsonObject(), key);
            }
        }
    }

    private static Rotation.Flip resolve(String name, Map<String, Declared> declared, Set<String> seen) {
        Declared d = declared.get(name);
        if (d == null || !seen.add(name)) {
            return Rotation.Flip.SYMMETRIC;
        }
        if (d.own() != null) {
            return d.own();
        }
        return d.parent() == null ? Rotation.Flip.SYMMETRIC : resolve(d.parent(), declared, seen);
    }

    /** The asset's word for a flip type ({@code EnumCodec}'s camel case), or null when unset or unknown. */
    static Rotation.Flip flipOf(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.replace("_", "").toLowerCase(Locale.ROOT)) {
            case "orthogonal" -> Rotation.Flip.ORTHOGONAL;
            case "orthogonalinverse" -> Rotation.Flip.ORTHOGONAL_INVERSE;
            case "symmetric" -> Rotation.Flip.SYMMETRIC;
            default -> null;
        };
    }

    private static JsonObject json(GameAssets assets, String path) {
        Optional<byte[]> bytes = assets.read(path);
        if (bytes.isEmpty()) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(new String(bytes.get(), StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static String fileName(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.substring(0, file.length() - ".json".length());
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
