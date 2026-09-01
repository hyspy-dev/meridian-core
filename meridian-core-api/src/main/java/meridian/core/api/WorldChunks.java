package meridian.core.api;

import java.util.Map;
import java.util.UUID;

/**
 * The world as the server sends it: chunk sections, fluids, biomes and the maps that go with them,
 * exactly as they came off the wire.
 *
 * <p>This is the raw feed, for anything that wants the world itself rather than an answer about
 * it - saving it to disk, drawing it, measuring it. Anything that only needs to know what block is
 * at a position should ask {@link WorldState} or {@link World} instead; they are built on this same
 * traffic and are far cheaper to use.
 *
 * <p>The bytes handed to a listener are the packet's own decoded copy, freshly allocated per
 * packet, so they may be kept - but they must not be modified: the packet is still on its way to
 * the client.
 *
 * <p><b>Ids are per connection.</b> A server hands out its own numbering for blocks, fluids and
 * environments each session, so the sections mean nothing without {@link #blockNames} and friends
 * from the <em>same</em> session. The tables are emptied and rebuilt on every join, which is what
 * makes {@link Listener#enterWorld} the moment to write down anything held from before.
 *
 * <p>Listeners are called on the proxy's network threads, in the order they subscribed, and one
 * that throws is logged and skipped rather than allowed to break the feed. Keep them short: this
 * runs on every chunk of the world going past.
 */
public interface WorldChunks {

    /** What arrives, as it arrives. Implement only the parts you care about. */
    interface Listener {

        /**
         * The chunk stream has turned over: everything the client had is gone, and what comes
         * next belongs to somewhere else.
         *
         * <p>This is the seam, and it is exact. The server tears the old world down with a packet
         * on the <em>chunk</em> channel, ahead of the new world's chunks, so nothing that arrives
         * after this belongs to what came before - which is not true of the join packet, since
         * that rides another channel and races the chunks.
         *
         * <p>Which world comes next is not known yet: {@link #enterWorld} follows, once the join
         * arrives. Anything held between the two belongs to the new world, so hold it rather than
         * filing it under the old one.
         */
        default void chunksCleared() {
        }

        /**
         * The world that everything since the last {@link #chunksCleared} belongs to.
         *
         * <p>Deliberately late: it is fired when the chunk stream turns over and the world is
         * known, not the moment the join packet lands. A subscriber writing chunks down needs the
         * two in the right order far more than it needs the name early.
         *
         * <p>Every id table from before is stale as of this call.
         */
        default void enterWorld(UUID world) {
        }

        /**
         * One 32³ section of blocks, at <em>section</em> coordinates (block coordinate &gt;&gt; 5).
         *
         * @param data        the palette-encoded blocks; {@code null} means a section of pure air
         * @param localLight  block light, or {@code null}
         * @param globalLight sky light, or {@code null}
         */
        default void section(int x, int y, int z, byte[] data, byte[] localLight,
                             byte[] globalLight) {
        }

        /** The fluids of one section, at section coordinates. */
        default void fluids(int x, int y, int z, byte[] data) {
        }

        /** A column's heightmap, at column coordinates. */
        default void heightmap(int x, int z, byte[] data) {
        }

        /** A column's grass and foliage tints. */
        default void tintmap(int x, int z, byte[] data) {
        }

        /** A column's biomes. */
        default void environments(int x, int z, byte[] data) {
        }

        /**
         * The server is done with a column and the client is about to forget it.
         *
         * <p>Whole columns only: a section unload leaves the rest of the column standing, and is
         * not reported here.
         */
        default void unload(int x, int z) {
        }
    }

    /** Starts sending this listener everything that arrives. */
    void subscribe(Listener listener);

    /** Stops. A listener that was never subscribed is ignored. */
    void unsubscribe(Listener listener);

    /** The world the player is in, or {@code null} before the first join. */
    UUID world();

    /** Block id &rarr; name, for this connection. */
    Map<Integer, String> blockNames();

    /** Fluid id &rarr; name, for this connection. */
    Map<Integer, String> fluidNames();

    /** Environment (biome) id &rarr; name, for this connection. */
    Map<Integer, String> environmentNames();
}
