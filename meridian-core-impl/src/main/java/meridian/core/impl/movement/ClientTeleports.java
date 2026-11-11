package meridian.core.impl.movement;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Every {@code ClientTeleport} a game is sent, and whose it is: the server's, or core's own - a
 * teleport core makes, a game's body core holds shown where it is.
 *
 * <p>The game acknowledges each teleport in a {@code ClientMovement}, and the server checks every
 * acknowledgement against the teleports it sent itself, in order: one it did not send ends the
 * connection ({@code TeleportAckTracker}, "incorrectTeleportId"), and it takes no movement while
 * one of its own is not acknowledged yet. So the acknowledgements of core's teleports stop at the
 * proxy, and core's ids are kept clear of the ones the server will use next - it counts up from its
 * last, and core's stay a quarter turn of the byte ahead of that and further.
 */
public final class ClientTeleports {

    /** A teleport not acknowledged in this long is not going to be. */
    private static final long FORGET_NANOS = 10_000_000_000L;

    private record Sent(int id, boolean ours, long at) {}

    private final ArrayDeque<Sent> sent = new ArrayDeque<>();
    /** The server's last teleport id, as the byte on the wire; -1 before its first. */
    private int serverLast = -1;
    private int ourCount;

    /** An id for a teleport core sends the game, clear of the ones the server uses next. */
    public synchronized byte ours() {
        prune();
        int base = Math.max(0, serverLast);
        int id = (base + 64 + (ourCount++ & 127)) & 0xFF;
        sent.addLast(new Sent(id, true, System.nanoTime()));
        return (byte) id;
    }

    /** The server sent the game a teleport. */
    public synchronized void server(byte id) {
        prune();
        serverLast = id & 0xFF;
        sent.addLast(new Sent(serverLast, false, System.nanoTime()));
    }

    /**
     * The game acknowledged teleport {@code id}: whether it was core's - then its acknowledgement
     * goes no further than the proxy.
     */
    public synchronized boolean ackedOurs(byte id) {
        int want = id & 0xFF;
        for (Iterator<Sent> it = sent.iterator(); it.hasNext(); ) {
            Sent s = it.next();
            if (s.id == want) {
                it.remove();
                return s.ours;
            }
        }
        return false;
    }

    /** Whether a teleport of the server's waits for the game's acknowledgement. */
    public synchronized boolean serverPending() {
        prune();
        for (Sent s : sent) {
            if (!s.ours) {
                return true;
            }
        }
        return false;
    }

    private void prune() {
        long now = System.nanoTime();
        while (!sent.isEmpty() && now - sent.peekFirst().at > FORGET_NANOS) {
            sent.pollFirst();
        }
    }
}
