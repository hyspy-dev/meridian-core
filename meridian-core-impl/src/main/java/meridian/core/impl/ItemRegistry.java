package meridian.core.impl;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import meridian.protocol.InteractionType;
import meridian.protocol.ItemBase;
import meridian.protocol.packets.assets.UpdateItems;
import meridian.protocol.packets.assets.UpdateUnarmedInteractions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's item asset catalog, mirrored inside the proxy.
 *
 * <p>Built from S2C {@code UpdateItems} (every {@link ItemBase}) and
 * {@code UpdateUnarmedInteractions} (the empty-hand bindings). Each item
 * carries the data the server uses to start an interaction:
 *
 * <ul>
 *   <li>{@code interactions} — {@link InteractionType} &rarr; root interaction
 *       id: the entry point the VM flattens;</li>
 *   <li>{@code interactionVars} — the variable map {@code ReplaceInteraction}
 *       resolves against (e.g. {@code SeedId} &rarr; the seed's plant root).</li>
 * </ul>
 *
 * <p>This is what lets {@code InteractionControl} forge a chain without first
 * watching the player perform it — the root and its variables come straight
 * from the server's own asset broadcast.
 */
public final class ItemRegistry {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** item id &rarr; its asset definition. */
    private final Map<String, ItemBase> items = new ConcurrentHashMap<>();
    /** empty-hand interaction roots, by type. */
    private volatile Map<InteractionType, Integer> unarmed = Map.of();

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    void onUpdateItems(UpdateItems packet) {
        if (packet.removedItems != null) {
            for (String id : packet.removedItems) {
                items.remove(id);
            }
        }
        if (packet.items != null) {
            packet.items.forEach((id, item) -> {
                if (id != null && item != null) {
                    items.put(id, item);
                }
            });
            log.info("meridian-core: item catalog — {} item(s)", items.size());
        }
    }

    void onUnarmedInteractions(UpdateUnarmedInteractions packet) {
        if (packet.interactions != null) {
            unarmed = Map.copyOf(packet.interactions);
            log.info("meridian-core: unarmed interactions — {} type(s)", unarmed.size());
        }
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /**
     * The root interaction id for {@code itemId}'s interaction of {@code type}.
     * A {@code null} / empty item id resolves against the unarmed bindings.
     */
    public OptionalInt rootFor(String itemId, InteractionType type) {
        Map<InteractionType, Integer> bound;
        if (itemId == null || itemId.isEmpty()) {
            bound = unarmed;
        } else {
            ItemBase item = items.get(itemId);
            bound = item == null ? null : item.interactions;
        }
        Integer root = bound == null ? null : bound.get(type);
        return root == null ? OptionalInt.empty() : OptionalInt.of(root);
    }

    /**
     * The interaction-variable map for {@code itemId} — the values
     * {@code ReplaceInteraction} resolves against. Empty when unknown.
     */
    public Map<String, Integer> varsFor(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return Map.of();
        }
        ItemBase item = items.get(itemId);
        return item == null || item.interactionVars == null ? Map.of() : item.interactionVars;
    }

    /** Whether any item asset has been received yet. */
    public boolean ready() {
        return !items.isEmpty();
    }
}
