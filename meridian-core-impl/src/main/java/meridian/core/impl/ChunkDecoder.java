package meridian.core.impl;

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
     * {@code null}, and returns the offset just past it.
     */
    private static int decodeTyped(byte[] data, int offset, int[] out) {
        int type = data[offset] & 0xFF;
        return switch (type) {
            case 0 -> offset + 1;                            // Empty: the type byte, nothing else
            case 1 -> decodePalette(data, offset + 1, out, 4);
            case 2 -> decodePalette(data, offset + 1, out, 8);
            case 3 -> decodePalette(data, offset + 1, out, 16);
            default -> throw new IllegalArgumentException("unknown palette type " + type);
        };
    }

    /**
     * Decodes one palette into {@code blocks}, or only steps over it when that is {@code null}.
     *
     * @param idBits internal-id width: 4 (HalfByte), 8 (Byte) or 16 (Short)
     * @return the offset just past the palette
     */
    private static int decodePalette(byte[] data, int offset, int[] blocks, int idBits) {
        int count = u16(data, offset);
        offset += 2;

        // entry: internalId (idBits, but min 1 byte) + externalId (int) + count (short)
        int idBytes = idBits == 16 ? 2 : 1;
        int entrySize = idBytes + 4 + 2;
        int[] internalToExternal = new int[idBits == 16 ? 65536 : (1 << idBits)];
        for (int i = 0; i < count; i++) {
            int base = offset + i * entrySize;
            int internalId = idBytes == 2 ? u16(data, base) : (data[base] & 0xFF);
            int externalId = i32(data, base + idBytes);
            internalToExternal[internalId] = externalId;
        }
        offset += count * entrySize;

        int arrayBytes = idBits == 4 ? SECTION_BLOCKS / 2 : idBits == 8 ? SECTION_BLOCKS : SECTION_BLOCKS * 2;
        if (blocks == null) {
            return offset + arrayBytes;
        }
        switch (idBits) {
            case 4 -> {
                for (int i = 0; i < SECTION_BLOCKS; i++) {
                    int b = data[offset + (i >> 1)] & 0xFF;
                    int nibble = (i & 1) == 0 ? (b >> 4) : (b & 0x0F);
                    blocks[i] = internalToExternal[nibble];
                }
            }
            case 8 -> {
                for (int i = 0; i < SECTION_BLOCKS; i++) {
                    blocks[i] = internalToExternal[data[offset + i] & 0xFF];
                }
            }
            case 16 -> {
                for (int i = 0; i < SECTION_BLOCKS; i++) {
                    blocks[i] = internalToExternal[u16(data, offset + i * 2)];
                }
            }
            default -> throw new IllegalArgumentException("bad idBits " + idBits);
        }
        return offset + arrayBytes;
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
