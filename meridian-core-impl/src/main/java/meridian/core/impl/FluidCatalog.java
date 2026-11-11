package meridian.core.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import meridian.protocol.Fluid;
import meridian.protocol.UpdateType;
import meridian.protocol.packets.assets.UpdateFluids;

/**
 * The fluid catalog ({@code UpdateFluids}) as this connection numbered it - read once, here, for
 * everything in core that names a fluid or moves a body through one: the chunk mirror, the raw
 * chunk feed, the body's physics.
 *
 * <p>Two views. The fluids themselves follow the server's updates, an {@code Init} replacing them
 * and a {@code Remove} taking some away, as the client's own copy does; that is what the body
 * swims by. The names only grow: a cell already written down, or already in a save, keeps the name
 * its id had, whatever the catalog does after. Clearing the names on a world change once emptied
 * them for good - they arrive with the assets, before the first join - and every fluid then went to
 * disk named "Unknown".
 */
public final class FluidCatalog {

    private final Map<Integer, Fluid> fluids = new ConcurrentHashMap<>();
    private final Map<Integer, String> names = new ConcurrentHashMap<>();

    /** One {@code UpdateFluids}, applied. */
    void update(UpdateFluids u) {
        if (u.type == UpdateType.Init) {
            fluids.clear();
        }
        if (u.fluids == null) {
            return;
        }
        if (u.type == UpdateType.Remove) {
            u.fluids.keySet().forEach(fluids::remove);
            return;
        }
        u.fluids.forEach((id, fluid) -> {
            if (fluid != null) {
                fluids.put(id, fluid);
                if (fluid.id != null) {
                    names.put(id, fluid.id);
                }
            }
        });
    }

    /** The fluid with this id, or {@code null} for one the catalog does not hold. */
    public Fluid fluid(int id) {
        return fluids.get(id);
    }

    /** The name an id has had (Water, Lava, ...), or {@code null} for one never named. */
    public String name(int id) {
        return names.get(id);
    }

    /** Every id that has had a name, with it. A copy. */
    public Map<Integer, String> names() {
        return Map.copyOf(names);
    }

    /** The id of a fluid by name, or {@code -1} when none has had it. */
    public int idByName(String name) {
        if (name != null) {
            for (Map.Entry<Integer, String> e : names.entrySet()) {
                if (name.equals(e.getValue())) {
                    return e.getKey();
                }
            }
        }
        return -1;
    }
}
