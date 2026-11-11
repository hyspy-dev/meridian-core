package meridian.core.impl;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.core.api.Clipboard;
import meridian.core.api.FluidCell;
import meridian.core.api.PastePreview;
import meridian.core.api.Prefabs;
import meridian.core.api.World;
import meridian.core.api.WorldState;

/**
 * {@link Prefabs}: the server's {@code SelectionPrefabSerializer} format, read and written as the
 * plain JSON the server's prefab files are, and the world's blocks taken the way its {@code /prefab
 * save} takes them.
 */
final class PrefabsImpl implements Prefabs {

    private final World world;
    private final WorldState worldState;

    PrefabsImpl(World world, WorldState worldState) {
        this.world = world;
        this.worldState = worldState;
    }

    // ------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------

    @Override
    public Prefab read(Path file) throws IOException {
        JsonObject doc;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("not a prefab: " + file);
            }
            doc = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("not a prefab: " + file + " (" + e.getMessage() + ")", e);
        }
        JsonElement blocks = doc.get("blocks");
        if (blocks == null || !blocks.isJsonArray()) {
            throw new IOException("prefab file has no 'blocks' array: " + file);
        }
        List<Cell> cells = new ArrayList<>(blocks.getAsJsonArray().size());
        for (JsonElement e : blocks.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject b = e.getAsJsonObject();
            String name = string(b, "name");
            if (name == null) {
                continue;
            }
            cells.add(new Cell(integer(b, "x", 0), integer(b, "y", 0), integer(b, "z", 0), name,
                    integer(b, "rotation", 0), integer(b, "filler", 0)));
        }
        List<Fluid> fluids = new ArrayList<>();
        JsonElement wet = doc.get("fluids");
        if (wet != null && wet.isJsonArray()) {
            for (JsonElement e : wet.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject f = e.getAsJsonObject();
                String name = string(f, "name");
                if (name != null) {
                    fluids.add(new Fluid(integer(f, "x", 0), integer(f, "y", 0), integer(f, "z", 0), name,
                            integer(f, "level", 0)));
                }
            }
        }
        return new Prefab(integer(doc, "anchorX", 0), integer(doc, "anchorY", 0), integer(doc, "anchorZ", 0),
                cells, fluids);
    }

    @Override
    public void write(Path file, Prefab prefab) throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("version", VERSION);
        out.addProperty("blockIdVersion", BlockView.BLOCK_ID_VERSION);
        out.addProperty("anchorX", prefab.anchorX());
        out.addProperty("anchorY", prefab.anchorY());
        out.addProperty("anchorZ", prefab.anchorZ());
        // The server orders by x, then z, then y ("keep the output stable for diffs").
        Comparator<int[]> order = Comparator.<int[]>comparingInt(p -> p[0]).thenComparingInt(p -> p[2])
                .thenComparingInt(p -> p[1]);
        List<Cell> cells = new ArrayList<>(prefab.blocks());
        cells.sort((a, b) -> order.compare(new int[] {a.x(), a.y(), a.z()}, new int[] {b.x(), b.y(), b.z()}));
        JsonArray blocks = new JsonArray();
        for (Cell c : cells) {
            JsonObject b = new JsonObject();
            b.addProperty("x", c.x());
            b.addProperty("y", c.y());
            b.addProperty("z", c.z());
            b.addProperty("name", c.name());
            if (c.filler() != 0) {
                b.addProperty("filler", c.filler());
            }
            if (c.rotation() != 0) {
                b.addProperty("rotation", c.rotation());
            }
            blocks.add(b);
        }
        out.add("blocks", blocks);
        if (!prefab.fluids().isEmpty()) {
            List<Fluid> wet = new ArrayList<>(prefab.fluids());
            wet.sort((a, b) -> order.compare(new int[] {a.x(), a.y(), a.z()}, new int[] {b.x(), b.y(), b.z()}));
            JsonArray fluids = new JsonArray();
            for (Fluid f : wet) {
                JsonObject o = new JsonObject();
                o.addProperty("x", f.x());
                o.addProperty("y", f.y());
                o.addProperty("z", f.z());
                o.addProperty("name", f.name());
                o.addProperty("level", f.level());
                fluids.add(o);
            }
            out.add("fluids", fluids);
        }
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out),
                StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------
    // The world
    // ------------------------------------------------------------------

    @Override
    public Prefab capture(BlockPos min, BlockPos max) {
        int x0 = Math.min(min.x(), max.x()), x1 = Math.max(min.x(), max.x());
        int y0 = Math.min(min.y(), max.y()), y1 = Math.max(min.y(), max.y());
        int z0 = Math.min(min.z(), max.z()), z1 = Math.max(min.z(), max.z());
        List<Cell> cells = new ArrayList<>();
        List<Fluid> fluids = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    FluidCell wet = world.fluidCellAt(x, y, z);
                    if (wet != null && wet.id() > 0 && wet.name() != null) {
                        fluids.add(new Fluid(x - x0, y - y0, z - z0, wet.name(), wet.level()));
                    }
                    Block b = world.blockAt(x, y, z);
                    if (b == null || b.isAir()) {
                        continue;
                    }
                    BlockView type = b.type();
                    if (type == null || type.name() == null || type.name().isEmpty()) {
                        continue;
                    }
                    cells.add(new Cell(x - x0, y - y0, z - z0, type.name(), b.rotation(), b.filler()));
                }
            }
        }
        return new Prefab(0, 0, 0, cells, fluids);
    }

    @Override
    public Resolved resolve(Prefab prefab) {
        List<PastePreview.Change> blocks = new ArrayList<>(prefab.blocks().size());
        Map<String, Integer> unknown = new LinkedHashMap<>();
        int empty = 0;
        for (Cell c : prefab.blocks()) {
            if (c.filler() != 0) {
                continue;               // the block on its own cell brings this one back
            }
            if (BlockView.EMPTY.equals(c.name())) {
                empty++;
                continue;
            }
            OptionalInt id = worldState.blockId(c.name());
            if (id.isEmpty() || id.getAsInt() <= 0) {
                unknown.merge(c.name(), 1, Integer::sum);
                continue;
            }
            blocks.add(new PastePreview.Change(c.x() - prefab.anchorX(), c.y() - prefab.anchorY(),
                    c.z() - prefab.anchorZ(), id.getAsInt(), c.rotation()));
        }
        List<PastePreview.FluidChange> fluids = new ArrayList<>(prefab.fluids().size());
        for (Fluid f : prefab.fluids()) {
            int id = f.name() == null ? -1 : world.fluidId(f.name());
            if (id <= 0) {
                unknown.merge(String.valueOf(f.name()), 1, Integer::sum);
                continue;
            }
            fluids.add(new PastePreview.FluidChange(f.x() - prefab.anchorX(), f.y() - prefab.anchorY(),
                    f.z() - prefab.anchorZ(), id, f.level()));
        }
        return new Resolved(new Clipboard(blocks, fluids), empty, unknown);
    }

    // ------------------------------------------------------------------

    private static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** An int field, plain or as extended JSON writes one ({@code {"$numberInt": "5"}}). */
    private static int integer(JsonObject o, String field, int otherwise) {
        JsonElement e = o.get(field);
        try {
            if (e == null || e.isJsonNull()) {
                return otherwise;
            }
            if (e.isJsonPrimitive()) {
                return e.getAsInt();
            }
            if (e.isJsonObject() && e.getAsJsonObject().has("$numberInt")) {
                return Integer.parseInt(e.getAsJsonObject().get("$numberInt").getAsString());
            }
        } catch (RuntimeException ignored) {
            // a field we cannot read is the default
        }
        return otherwise;
    }
}
