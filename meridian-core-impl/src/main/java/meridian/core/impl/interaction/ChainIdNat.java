package meridian.core.impl.interaction;

import java.util.HashMap;
import java.util.Map;

/**
 * Chain-id NAT for forged interaction chains.
 *
 * <p>The proxy and the real client each keep a monotonic interaction
 * {@code chainId} counter, and the server validates continuity. If the proxy
 * forges a chain it must use an id the client will never produce — otherwise
 * the forged chain collides with the player's next real chain and the server
 * desyncs.
 *
 * <p>This translates the local player's chain ids into one proxy-owned
 * server-side space:
 *
 * <ul>
 *   <li>each real client chain id maps to a stable server-side id
 *       ({@link #toServer}); a multi-packet chain reuses its id, so the mapping
 *       is fixed per chain;</li>
 *   <li>forged chains draw fresh server-side ids from the same monotonic
 *       counter ({@link #allocateForged}) — they slot cleanly between the
 *       client's ids without ever colliding;</li>
 *   <li>server-to-client traffic is translated back ({@link #toClient}); a
 *       chain the client never created ({@link #isForged}) is the proxy's own
 *       echo and must be dropped before it reaches the client.</li>
 * </ul>
 *
 * <p>Non-positive ids (server-initiated chains, sentinels) pass untouched.
 * All methods are synchronized: client traffic runs on a Netty event loop
 * while forging is driven from a module thread.
 */
final class ChainIdNat {
    /** {@code server2client} marker for an id the proxy forged itself. */
    private static final Integer FORGED = Integer.MIN_VALUE;

    private final Map<Integer, Integer> client2server = new HashMap<>();
    private final Map<Integer, Integer> server2client = new HashMap<>();
    private int highestServerId;
    private boolean initialised;

    /** Translates a real client chain id to its server-side id (stable per chain). */
    synchronized int toServer(int clientChainId) {
        if (clientChainId <= 0) {
            return clientChainId;
        }
        if (!initialised) {
            // Align the two spaces on the first chain seen — the server already
            // tracks this player's counter, so the first id maps to itself.
            highestServerId = clientChainId - 1;
            initialised = true;
        }
        Integer s = client2server.get(clientChainId);
        if (s == null) {
            s = ++highestServerId;
            client2server.put(clientChainId, s);
            server2client.put(s, clientChainId);
        }
        return s;
    }

    /** Allocates a fresh server-side id for a forged chain. */
    synchronized int allocateForged() {
        int s = ++highestServerId;
        server2client.put(s, FORGED);
        return s;
    }

    /** Translates a server-side chain id back to client space (unknown ids pass). */
    synchronized int toClient(int serverChainId) {
        if (serverChainId <= 0) {
            return serverChainId;
        }
        Integer c = server2client.get(serverChainId);
        return c == null || FORGED.equals(c) ? serverChainId : c;
    }

    /** Whether {@code serverChainId} belongs to a chain the proxy forged. */
    synchronized boolean isForged(int serverChainId) {
        return FORGED.equals(server2client.get(serverChainId));
    }
}
