package meridian.core.impl;

/**
 * Decodes the block-id array out of a {@code SetChunk.data} payload.
 *
 * <p>A chunk section is 32×32×32 = 32768 blocks. {@code SetChunk.data} holds
 * three concatenated palettes — block ids, filler, rotation — each prefixed by
 * a {@code PaletteType} ordinal byte. Only the first (block ids) is needed
 * here, so decoding stops after it.
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
    static int[] decodeBlockIds(byte[] data) {
        int[] blocks = new int[SECTION_BLOCKS];
        if (data == null || data.length < 1) {
            return blocks; // solid-air section
        }
        int type = data[0] & 0xFF;
        switch (type) {
            case 0 -> { /* Empty — all zero */ }
            case 1 -> decodePalette(data, 1, blocks, 4);
            case 2 -> decodePalette(data, 1, blocks, 8);
            case 3 -> decodePalette(data, 1, blocks, 16);
            default -> throw new IllegalArgumentException("unknown palette type " + type);
        }
        return blocks;
    }

    /**
     * Decodes one palette into {@code blocks}.
     *
     * @param idBits internal-id width: 4 (HalfByte), 8 (Byte) or 16 (Short)
     */
    private static void decodePalette(byte[] data, int offset, int[] blocks, int idBits) {
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
