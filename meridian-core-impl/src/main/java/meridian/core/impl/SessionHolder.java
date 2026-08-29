package meridian.core.impl;

import java.util.Optional;
import meridian.api.session.ProxySession;

/**
 * The live client session for one channel, for the parts of core that forge packets rather than
 * observe them.
 *
 * <p>A session is per-stream. A forged packet has to go out on the channel its real counterparts
 * ride, which means capturing the session from a packet that arrived on that same channel — a
 * session taken off a map packet sends everything down the map channel, and one taken off a
 * chat packet cannot carry a map update. So there is one holder per channel rather than one
 * shared: {@code Default} for interface, chat and asset traffic, {@code WorldMap} for the map.
 */
final class SessionHolder {

    private volatile ProxySession session;

    /** Records the session a Default-channel packet arrived on. */
    void capture(ProxySession session) {
        if (session != null) {
            this.session = session;
        }
    }

    /** The current session, or empty before the player has joined a world. */
    Optional<ProxySession> get() {
        return Optional.ofNullable(session);
    }
}
