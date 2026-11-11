package meridian.core.impl;

import meridian.core.api.WorldChunks;

/**
 * Decodes the block-id array out of a {@code SetChunk.data} payload.
 *
 * <p>A chunk section is 32×32×32 = 32768 blocks. {@code SetChunk.data} holds
 * three concatenated palettes — block ids, filler, rotation — each prefixed by
 * a {@code PaletteType} ordinal byte. All three are kept.
 *
 * <p>A palette is {@code [short LE: entryCount]}, then {@code entryCount}
 * entries mapping an internal id to an external (real) block id, then a
 * per-block array of internal ids. The internal-id width is the palette type:
 *
 * <ul>
 *   <li>{@code Empty} (0) — section is all air.</li>
 *   <li>{@code HalfByte} (1) — 4-bit internal ids, nibble-packed (16384 bytes).</li>
 *   <li>{@code Byte} (2) — 8-bit internal ids (32768 bytes).</li>
 *   <li>{@code Short} (3) — 16-bit internal ids (65536 bytes, LE).</li>
 * </ul>
 */
final class ChunkDecoder {

    static final int SECTION_BLOCKS = 32768;

    private ChunkDecoder() {}

    /**
     * Decodes the block ids of a section. Returns a fresh {@code int[32768]}
     * indexed by {@code ChunkUtil.indexBlock} order; an all-air section (or a
     * {@code null} / empty payload) yields an all-zero array.
     */
    /**
     * Decodes the fluid ids of a {@code SetFluids.data} section. Its first palette is the fluid-id
     * palette, in the same shape as a block palette, so the same reader serves - it stops after
     * that first palette, before the fluid section's level-nibble tail. {@code 0} is "no fluid".
     */
    static int[] decodeFluidIds(byte[] data) {
        return decodeBlockIds(data);
    }

    /** Bytes of a fluid section's level array: a nibble a cell. */
    static final int LEVEL_BYTES = SECTION_BLOCKS / 2;

    /**
     * A fluid section: the fluid id per cell, and the levels as the server packs them - a nibble a
     * cell, the even cell in the <b>low</b> half (the reverse of a half-byte palette), or
     * {@code null} when every level is nought.
     */
    record Fluids(int[] ids, byte[] levels) {}

    /**
     * Decodes a {@code SetFluids.data} section whole: the fluid palette, then a flag byte, then
     * {@link #LEVEL_BYTES} of levels when the flag is set (the server's
     * {@code FluidSection.serializeForPacket}).
     */
    static Fluids decodeFluids(byte[] data) {
        int[] ids = new int[SECTION_BLOCKS];
        if (data == null || data.length < 1) {
            return new Fluids(ids, null);
        }
        int offset = decodeTyped(data, 0, ids);
        if (offset >= data.length || data[offset] == 0 || offset + 1 + LEVEL_BYTES > data.length) {
            return new Fluids(ids, null);
        }
        byte[] levels = new byte[LEVEL_BYTES];
        System.arraycopy(data, offset + 1, levels, 0, LEVEL_BYTES);
        return new Fluids(ids, levels);
    }

    /** One cell's level out of a packed level array ({@code null} reads as nought). */
    static int levelOf(byte[] levels, int index) {
        return levels == null ? 0 : (levels[index >> 1] >> ((index & 1) * 4)) & 0x0F;
    }

    /** Sets one cell's level in a packed level array. */
    static void setLevel(byte[] levels, int index, int level) {
        int b = levels[index >> 1];
        levels[index >> 1] = (byte) ((index & 1) == 0
                ? (b & 0xF0) | (level & 0x0F)
                : (b & 0x0F) | ((level & 0x0F) << 4));
    }

    static int[] decodeBlockIds(byte[] data) {
        int[] blocks = new int[SECTION_BLOCKS];
        if (data == null || data.length < 1) {
            return blocks; // solid-air section
        }
        decodeTyped(data, 0, blocks);
        return blocks;
    }

    /**
     * A section's block ids and which way each block faces.
     *
     * @param rotations the server's rotation index per block (0..63: {@code roll*16 + pitch*4 +
     *                  yaw}, each a quarter turn), or {@code null} when nothing in the section is
     *                  turned - most sections, and the memory is not worth spending on zeros
     * @param fillers   per block, the offset back to the cell a bigger block stands on ({@code 0}
     *                  for a block's own cell - see {@code Block.filler()}), or {@code null} when
     *                  no block in the section reaches past its own cell
     */
    record Section(int[] ids, byte[] rotations, int[] fillers) {}

    /**
     * Decodes a section's block ids, its fillers and its rotations, the three palettes in that
     * order. A payload that ends early - nothing filled or turned, or a server that sends less -
     * gives none of what is missing rather than an error.
     */
    static Section decodeSection(byte[] data) {
        int[] ids = new int[SECTION_BLOCKS];
        if (data == null || data.length < 1) {
            return new Section(ids, null, null);
        }
        int offset = decodeTyped(data, 0, ids);
        if (offset >= data.length) {
            return new Section(ids, null, null);
        }
        int[] fillers = (data[offset] & 0xFF) == 0 ? null : new int[SECTION_BLOCKS];
        offset = decodeTyped(data, offset, fillers);
        if (fillers != null && allZero(fillers)) {
            fillers = null;
        }
        if (offset >= data.length || (data[offset] & 0xFF) == 0) {
            return new Section(ids, null, fillers);
        }
        int[] rotation = new int[SECTION_BLOCKS];
        decodeTyped(data, offset, rotation);
        byte[] rotations = new byte[SECTION_BLOCKS];
        boolean any = false;
        for (int i = 0; i < SECTION_BLOCKS; i++) {
            rotations[i] = (byte) rotation[i];
            any |= rotation[i] != 0;
        }
        return new Section(ids, any ? rotations : null, fillers);
    }

    // ------------------------------------------------------------------
    // The palettes as they are packed - for WorldChunks.decode
    // ------------------------------------------------------------------

    /**
     * A section's three palettes with their internal ids kept, as {@link WorldChunks#decode} hands
     * them out. A payload that ends early gives empty palettes for what is missing.
     */
    static WorldChunks.Section rawSection(byte[] data) {
        if (data == null || data.length < 1) {
            return new WorldChunks.Section(RawPalette.NONE, RawPalette.NONE, RawPalette.NONE);
        }
        RawPalette blocks = RawPalette.read(data, 0);
        RawPalette fillers = blocks.end < data.length ? RawPalette.read(data, blocks.end) : RawPalette.NONE;
        RawPalette rotations = fillers != RawPalette.NONE && fillers.end < data.length
                ? RawPalette.read(data, fillers.end) : RawPalette.NONE;
        return new WorldChunks.Section(blocks, fillers, rotations);
    }

    /** A fluid section's palette with its internal ids kept, and its levels as packed. */
    static WorldChunks.FluidSection rawFluids(byte[] data) {
        if (data == null || data.length < 1) {
            return new WorldChunks.FluidSection(RawPalette.NONE, null);
        }
        RawPalette fluids = RawPalette.read(data, 0);
        int at = fluids.end;
        if (at >= data.length || data[at] == 0 || at + 1 + LEVEL_BYTES > data.length) {
            return new WorldChunks.FluidSection(fluids, null);
        }
        byte[] levels = new byte[LEVEL_BYTES];
        System.arraycopy(data, at + 1, levels, 0, LEVEL_BYTES);
        return new WorldChunks.FluidSection(fluids, levels);
    }

    /**
     * One palette read in place: its entries copied out, its cells read from the payload on demand
     * (the payload is the packet's own, which nobody may change).
     */
    static final class RawPalette implements WorldChunks.Palette {

        static final RawPalette NONE = new RawPalette(null, EMPTY, new int[0], new int[0], new int[0], 0, 0);

        private final byte[] data;
        private final int kind;
        private final int[] internal;
        private final int[] values;
        private final int[] counts;
        private final int body;
        /** Where the next palette starts. */
        final int end;
        /** Internal id → value, built when a cell's value is first asked for. */
        private volatile int[] lookup;

        private RawPalette(byte[] data, int kind, int[] internal, int[] values, int[] counts, int body, int end) {
            this.data = data;
            this.kind = kind;
            this.internal = internal;
            this.values = values;
            this.counts = counts;
            this.body = body;
            this.end = end;
        }

        /** The palette whose type byte is at {@code offset}. */
        static RawPalette read(byte[] data, int offset) {
            int kind = data[offset] & 0xFF;
            if (kind == EMPTY) {
                return new RawPalette(null, EMPTY, new int[0], new int[0], new int[0], 0, offset + 1);
            }
            if (kind != HALF_BYTE && kind != BYTE && kind != SHORT) {
                throw new IllegalArgumentException("unknown palette type " + kind);
            }
            int at = offset + 1;
            int size = u16(data, at);
            at += 2;
            int idBytes = kind == SHORT ? 2 : 1;
            int[] internal = new int[size];
            int[] values = new int[size];
            int[] counts = new int[size];
            for (int i = 0; i < size; i++) {
                internal[i] = idBytes == 2 ? u16(data, at) : data[at] & 0xFF;
                values[i] = i32(data, at + idBytes);
                counts[i] = u16(data, at + idBytes + 4);
                at += idBytes + 4 + 2;
            }
            int bodyBytes = kind == HALF_BYTE ? SECTION_BLOCKS / 2 : kind == BYTE ? SECTION_BLOCKS : SECTION_BLOCKS * 2;
            if (at + bodyBytes > data.length) {
                throw new IllegalArgumentException("palette body runs past the payload");
            }
            return new RawPalette(data, kind, internal, values, counts, at, at + bodyBytes);
        }

        @Override public int kind() { return kind; }
        @Override public int size() { return internal.length; }
        @Override public int internalId(int entry) { return internal[entry]; }
        @Override public int value(int entry) { return values[entry]; }
        @Override public int count(int entry) { return counts[entry]; }

        @Override
        public int internalAt(int cell) {
            return switch (kind) {
                // Two cells a byte, the earlier one in the high half (the server's getNibble).
                case HALF_BYTE -> (data[body + (cell >> 1)] >> ((1 - (cell & 1)) * 4)) & 0x0F;
                case BYTE -> data[body + cell] & 0xFF;
                case SHORT -> u16(data, body + cell * 2);   // little-endian on the wire
                default -> 0;
            };
        }

        @Override
        public int valueAt(int cell) {
            return kind == EMPTY ? 0 : lookup()[internalAt(cell)];
        }

        /** Internal id → value, built once. */
        private int[] lookup() {
            int[] map = lookup;
            if (map == null) {
                map = new int[kind == SHORT ? 65536 : kind == BYTE ? 256 : 16];
                for (int i = 0; i < internal.length; i++) {
                    map[internal[i]] = values[i];
                }
                lookup = map;
            }
            return map;
        }

        /** Every cell's value into {@code out} - an empty palette leaves it as it is (all nought). */
        void fill(int[] out) {
            if (kind == EMPTY) {
                return;
            }
            int[] map = lookup();
            switch (kind) {
                case HALF_BYTE -> {
                    for (int i = 0; i < SECTION_BLOCKS; i++) {
                        int b = data[body + (i >> 1)] & 0xFF;
                        out[i] = map[(i & 1) == 0 ? b >> 4 : b & 0x0F];
                    }
                }
                case BYTE -> {
                    for (int i = 0; i < SECTION_BLOCKS; i++) {
                        out[i] = map[data[body + i] & 0xFF];
                    }
                }
                default -> {
                    for (int i = 0; i < SECTION_BLOCKS; i++) {
                        out[i] = map[u16(data, body + i * 2)];
                    }
                }
            }
        }
    }

    private static boolean allZero(int[] values) {
        for (int v : values) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes the palette whose type byte sits at {@code offset}, into {@code out} when it is not
     * {@code null}, and returns the offset just past it - the one palette reader ({@link RawPalette}),
     * each cell's internal id mapped to the value its entry stands for.
     */
    private static int decodeTyped(byte[] data, int offset, int[] out) {
        RawPalette palette = RawPalette.read(data, offset);
        if (out != null) {
            palette.fill(out);
        }
        return palette.end;
    }

    /** Unsigned little-endian 16-bit. */
    private static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    /** Little-endian 32-bit. */
    private static int i32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }
}
