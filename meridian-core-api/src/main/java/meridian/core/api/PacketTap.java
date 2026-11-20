package meridian.core.api;

import java.util.Set;

/**
 * The packets themselves, as bytes on the wire, for a module that keeps them rather than reads them -
 * a recording to be played back by something that has the protocol (a server plugin decoding them
 * with the server's own classes).
 *
 * <p>What the server sent is given as it arrived, before anything of the proxy's changed it; what
 * went to the server, as it went - the game's and core's own alike. The bytes are the packet's body
 * as its {@code serialize} writes it: no frame, no compression. The id is this build's; the name is
 * the protocol's class name ({@code EntityUpdates}, {@code ClientMovement}), the same across builds.
 *
 * <p>Listeners are called on the proxy's network threads, in order, and must be quick: hand the bytes
 * to a queue. One that throws is logged and skipped.
 */
public interface PacketTap {

    /** One packet. */
    interface Listener {
        /**
         * @param toServer {@code true} for one that went to the server, {@code false} for one from it
         * @param id       the packet's id in this build
         * @param name     the protocol's class name of it
         * @param body     the packet's body, the listener's to keep
         */
        void packet(boolean toServer, int id, String name, byte[] body);
    }

    /**
     * Hands the packets of the given names to {@code listener} until the returned handle is closed.
     *
     * @param names the protocol's class names wanted; an empty set means every packet
     */
    AutoCloseable subscribe(Set<String> names, Listener listener);
}
