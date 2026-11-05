package meridian.core.impl;

/**
 * Decodes the block-id array out of a {@code SetChunk.data} payload.
 *
 * <p>A chunk section is 32×32×32 = 32768 blocks. {@code SetChunk.data} holds
 * three concatenated palettes — block ids, filler, rotation — each prefixed by
 * a {@code PaletteType} ordinal byte. The block ids and the rotations are kept;
 * the filler palette between them is read past.
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
     */
    record Section(int[] ids, byte[] rotations) {}

    /**
     * Decodes a section's block ids and its rotations. The rotation palette comes third, after
     * the filler palette, which is stepped over. A payload that ends before it - nothing turned,
     * or a server that sends less - gives no rotations rather than an error.
     */
    static Section decodeSection(byte[] data) {
        int[] ids = new int[SECTION_BLOCKS];
        if (data == null || data.length < 1) {
            return new Section(ids, null);
        }
        int offset = decodeTyped(data, 0, ids);
        if (offset >= data.length) {
            return new Section(ids, null);
        }
        offset = decodeTyped(data, offset, null);            // filler: read past, not kept
        if (offset >= data.length || (data[offset] & 0xFF) == 0) {
            return new Section(ids, null);
        }
        int[] rotation = new int[SECTION_BLOCKS];
        decodeTyped(data, offset, rotation);
        byte[] rotations = new byte[SECTION_BLOCKS];
        boolean any = false;
        for (int i = 0; i < SECTION_BLOCKS; i++) {
            rotations[i] = (byte) rotation[i];
            any |= rotation[i] != 0;
        }
        return new Section(ids, any ? rotations : null);
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
