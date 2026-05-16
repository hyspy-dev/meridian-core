package meridian.core.impl;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import meridian.protocol.Interaction;
import meridian.protocol.RootInteraction;
import meridian.protocol.UpdateType;
import meridian.protocol.packets.assets.UpdateInteractions;
import meridian.protocol.packets.assets.UpdateRootInteractions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's interaction catalog, mirrored inside the proxy.
 *
 * <p>Hytale broadcasts its full interaction registry to every client at world
 * setup: {@code UpdateRootInteractions} (id 67) maps an integer id to each
 * {@link RootInteraction} (which carries its string {@code id} name and the
 * ids of its operation nodes), and {@code UpdateInteractions} (id 66) maps an
 * integer id to each {@link Interaction} operation node.
 *
 * <p>{@link InteractionRegistryObserver} feeds both packets here. The result is
 * a per-server, per-version <em>name &harr; id</em> table plus the operation
 * trees — everything needed to forge an interaction chain without hard-coding
 * any server-specific integer.
 *
 * <p>Stage 1 of the interaction-forging work (see
 * {@code docs/interaction-chains.md}). Later stages flatten the trees into
 * operation lists and simulate them.
 */
final class InteractionRegistry {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** Root-interaction id &rarr; definition. */
    private final Map<Integer, RootInteraction> roots = new ConcurrentHashMap<>();
    /** Operation-node id &rarr; definition. */
    private final Map<Integer, Interaction> interactions = new ConcurrentHashMap<>();
    /** Root-interaction name &rarr; id. */
    private final Map<String, Integer> rootIdByName = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    /** Applies an observed S2C {@code UpdateRootInteractions}. */
    void onRootInteractions(UpdateRootInteractions packet) {
        if (packet.interactions == null) {
            return;
        }
        if (packet.type == UpdateType.Init) {
            roots.clear();
            rootIdByName.clear();
        }
        for (Map.Entry<Integer, RootInteraction> e : packet.interactions.entrySet()) {
            int id = e.getKey();
            RootInteraction root = e.getValue();
            if (packet.type == UpdateType.Remove) {
                RootInteraction removed = roots.remove(id);
                if (removed != null && removed.id != null) {
                    rootIdByName.remove(removed.id);
                }
            } else {
                roots.put(id, root);
                if (root.id != null) {
                    rootIdByName.put(root.id, id);
                }
            }
        }
        log.info("meridian-core: interaction registry — {} root interaction(s) ({})",
                roots.size(), packet.type);
    }

    /** Applies an observed S2C {@code UpdateInteractions}. */
    void onInteractions(UpdateInteractions packet) {
        if (packet.interactions == null) {
            return;
        }
        if (packet.type == UpdateType.Init) {
            interactions.clear();
        }
        for (Map.Entry<Integer, Interaction> e : packet.interactions.entrySet()) {
            if (packet.type == UpdateType.Remove) {
                interactions.remove(e.getKey());
            } else {
                interactions.put(e.getKey(), e.getValue());
            }
        }
        log.info("meridian-core: interaction registry — {} operation node(s) ({})",
                interactions.size(), packet.type);
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /** {@code true} once both registry packets have been observed. */
    boolean ready() {
        return !roots.isEmpty() && !interactions.isEmpty();
    }

    /** Integer id of the root interaction named {@code name}. */
    OptionalInt rootId(String name) {
        Integer id = rootIdByName.get(name);
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    /** Root interaction by integer id. */
    Optional<RootInteraction> root(int id) {
        return Optional.ofNullable(roots.get(id));
    }

    /** Operation node by integer id. */
    Optional<Interaction> interaction(int id) {
        return Optional.ofNullable(interactions.get(id));
    }

    /**
     * Finds the integer id of the first root interaction whose name contains
     * {@code substring}. Useful while the exact naming is still being mapped.
     */
    OptionalInt findRootContaining(String substring) {
        for (Map.Entry<String, Integer> e : rootIdByName.entrySet()) {
            if (e.getKey().contains(substring)) {
                return OptionalInt.of(e.getValue());
            }
        }
        return OptionalInt.empty();
    }

    int rootCount() {
        return roots.size();
    }

    int interactionCount() {
        return interactions.size();
    }
}
