package meridian.core.impl;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Which world the WorldMap stream is drawing - not the same thing as the world the player is in.
 *
 * <p>{@code JoinWorld} rides the Default stream; tiles and markers ride the WorldMap stream, which
 * the server sends at the lowest priority. Leaving a world flushes what was queued for it, and
 * that tail can reach the proxy well after the {@code JoinWorld} of the next one. Filed by the
 * join, it would land in the new world - an instance's void drawn over the world it was entered
 * from.
 *
 * <p>The stream's own boundary is {@code ClearWorldMap}: the server sends it once the player is in
 * the new world, which takes the client's {@code ClientReady}, which takes the {@code JoinWorld}.
 * So a join is queued, armed by the ready that follows it, and taken by the next clear. A clear
 * with nothing armed - the server also clears on a view-radius or map-generator change - leaves
 * the world as it is.
 *
 * <p>The first join has no tail in front of it, so it is taken straight away.
 */
final class MapStreamWorld {

    /** Joins in flight are one or two; this only stops a lost clear from growing the queue. */
    private static final int MAX_PENDING = 8;

    private static final class Pending {
        final UUID world;
        boolean armed;

        Pending(UUID world) {
            this.world = world;
        }
    }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    /** The world the stream is drawing; null until the first join. */
    private UUID drawing;

    /**
     * A {@code JoinWorld} went to the client.
     *
     * @return the world the stream draws from now on, or null when it waits for the clear
     */
    synchronized UUID joined(UUID world) {
        if (world == null) {
            return null;
        }
        if (drawing == null) {
            drawing = world;
            return world;
        }
        if (pending.size() == MAX_PENDING) {
            pending.removeFirst();
        }
        pending.addLast(new Pending(world));
        return null;
    }

    /** The client said it is ready for the world it was last sent; the server will clear next. */
    synchronized void clientReady() {
        for (Pending next : pending) {
            if (!next.armed) {
                next.armed = true;
                return;
            }
        }
    }

    /**
     * A {@code ClearWorldMap} came down the WorldMap stream.
     *
     * @return the world the stream draws from now on, or null when this clear changes nothing
     */
    synchronized UUID cleared() {
        Pending next = pending.peekFirst();
        if (next == null || !next.armed) {
            return null;
        }
        pending.removeFirst();
        if (next.world.equals(drawing)) {
            return null;
        }
        drawing = next.world;
        return drawing;
    }

    /** The connection ended: the next join starts from nothing. */
    synchronized void reset() {
        pending.clear();
        drawing = null;
    }
}
