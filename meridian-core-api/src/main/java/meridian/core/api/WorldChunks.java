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
         * arrives. Nothing is delivered in between: what arrives meanwhile belongs to the new world,
         * and core holds it and hands it on right after {@link #enterWorld}.
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
         *
         * <p>Nothing of a world reaches a listener before this names it - not the first burst,
         * which races the join on its own channel, nor what follows a {@link #chunksCleared}: core
         * holds those and delivers them, in order, straight after this call. So a listener can file
         * every section under the world it was last told about.
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

        /**
         * A column's heightmap, at column coordinates: the top of the world at each of its
         * 32&times;32 columns, one int apiece.
         *
         * <p>Ints, not packed bytes. This line's server keeps the heightmap in a column component
         * of its own and sends it as it holds it; the older lines packed the same numbers ten bits
         * at a time into a palette, and this method carried those bytes. Nothing is unpacked on
         * the way here - what the server said is what arrives.
         */
        default void heightmap(int x, int z, int[] heights) {
        }

        /**
         * One column's top has moved, at <em>block</em> coordinates.
         *
         * <p>The server sends a whole heightmap when a lot of it changes at once and single tops
         * when little does; both arrive here the same way, one column at a time, because a
         * listener that keeps a heightmap wants to patch it either way.
         */
        default void height(int blockX, int blockZ, int height) {
        }

        /** A column's grass and foliage tints. */
        default void tintmap(int x, int z, byte[] data) {
        }

        /**
         * The environments of one 32&sup3; section, at <em>section</em> coordinates.
         *
         * <p>A section, not a column: this line's server keeps environments per section and says
         * so per section, where the older lines banded a whole column by height in one message.
         * The payload is that section's own encoding - uniform, or one entry a column.
         */
        default void environments(int x, int y, int z, byte[] data) {
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

    // ------------------------------------------------------------------
    // A section as the server packed it
    // ------------------------------------------------------------------

    /**
     * One palette of a section, as the server packs it: a table of entries - an internal id, the
     * value it stands for, and how many cells hold it - and for every cell the internal id of its
     * entry.
     *
     * <p>For code that keeps sections the way the server keeps them: a save copies each cell's
     * internal id and writes the entries down in its own words. Anything that only wants to know
     * what is where asks {@link World}. The values are this connection's numbers (a block id, a
     * fluid id), or the filler offset and rotation index a cell carries.
     *
     * <p>Cells are numbered as everywhere in a section: {@code (y << 10) | (z << 5) | x}.
     */
    interface Palette {
        /** The server's palette kinds ({@code PaletteType}): no entries, every cell value 0. */
        int EMPTY = 0;
        /** Internal ids of four bits, two cells to a byte. */
        int HALF_BYTE = 1;
        /** Internal ids of a byte. */
        int BYTE = 2;
        /** Internal ids of two bytes - a palette of more than 256 entries. */
        int SHORT = 3;

        /** Which of {@link #EMPTY}, {@link #HALF_BYTE}, {@link #BYTE}, {@link #SHORT} it is. */
        int kind();

        /** How many entries the table has. */
        int size();

        /** The internal id of an entry, {@code 0 <= entry < size()}. */
        int internalId(int entry);

        /** What an entry stands for. */
        int value(int entry);

        /** How many cells the server counted for an entry. */
        int count(int entry);

        /** The internal id a cell holds; {@code 0} in an {@link #EMPTY} palette. */
        int internalAt(int cell);

        /** The value a cell holds - its entry's {@link #value}; {@code 0} in an {@link #EMPTY} one. */
        int valueAt(int cell);
    }

    /** A section's three palettes: the blocks, the filler offsets, the rotations. */
    record Section(Palette blocks, Palette fillers, Palette rotations) {}

    /**
     * A section's fluids: the fluid palette, and the levels as the server packs them - a nibble a
     * cell, the even cell in the <b>low</b> half - or {@code null} when every level is nought.
     */
    record FluidSection(Palette fluids, byte[] levels) {}

    /**
     * Reads a section's payload ({@link Listener#section}'s {@code data}) into its palettes, the
     * internal ids kept. {@code null} - a section of pure air - gives three empty palettes. Pure:
     * the same answer on any thread, the payload left as it was.
     *
     * @throws IllegalArgumentException for a payload that is not a section
     */
    Section decode(byte[] data);

    /** The same for a fluid section's payload ({@link Listener#fluids}'s {@code data}). */
    FluidSection decodeFluids(byte[] data);
}
