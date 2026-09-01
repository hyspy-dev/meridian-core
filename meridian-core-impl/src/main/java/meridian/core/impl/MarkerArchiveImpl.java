package meridian.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import meridian.core.api.Marker;
import meridian.core.api.MarkerArchive;

/**
 * Core's marker memory, offered to whoever keeps the file.
 *
 * <p>A thin face over {@link MarkerStoreImpl}: core goes on remembering markers exactly as it did,
 * and the only thing that has moved out is the writing down. The markers module reads this at
 * shutdown and hands it back at startup.
 */
final class MarkerArchiveImpl implements MarkerArchive {

    private final MarkerStoreImpl store;

    MarkerArchiveImpl(MarkerStoreImpl store) {
        this.store = store;
    }

    @Override
    public String export() {
        return store.export();
    }

    @Override
    public void restore(String json) {
        store.restore(json);
    }

    @Override
    public boolean isEmpty() {
        return store.isEmpty();
    }

    @Override
    public boolean hasChanges() {
        return store.hasChanges();
    }

    @Override
    public List<UUID> worlds() {
        List<UUID> out = new ArrayList<>();
        for (String id : store.worldIds()) {
            try {
                out.add(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                // Markers filed before the world was known sit under a placeholder that is not a
                // UUID at all. They are still remembered; they simply have no world to name.
            }
        }
        return out;
    }

    @Override
    public List<Marker> markers(UUID world) {
        if (world == null) {
            return List.of();
        }
        List<Marker> out = new ArrayList<>();
        for (MarkerState state : store.markers(world.toString())) {
            out.add(state.snapshot());
        }
        return out;
    }
}
