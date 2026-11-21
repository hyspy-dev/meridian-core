package meridian.core.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The old server's ShortBytePalette: signed short keys and 1024 little-endian 10-bit indices. */
final class HeightmapDecoder {
    private HeightmapDecoder() { }

    static int[] decode(byte[] data) {
        if (data == null) return null;
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (in.remaining() < 6) throw new IllegalArgumentException("Truncated heightmap");
        int count = Short.toUnsignedInt(in.getShort());
        if (count > 1024 || in.remaining() < count * 2 + 4) {
            throw new IllegalArgumentException("Invalid heightmap palette");
        }
        short[] keys = new short[Math.max(1, count)];
        for (int i = 0; i < count; i++) keys[i] = in.getShort();
        int length = in.getInt();
        if (count == 0 && length == 0) return new int[1024];
        if (length != 1280 || in.remaining() < length) {
            throw new IllegalArgumentException("Invalid heightmap indices");
        }
        int start = in.position();
        int[] heights = new int[1024];
        for (int i = 0; i < heights.length; i++) {
            int bit = i * 10;
            int at = start + (bit >> 3);
            int packed = Byte.toUnsignedInt(data[at]) | Byte.toUnsignedInt(data[at + 1]) << 8;
            int index = (packed >>> (bit & 7)) & 1023;
            if (index >= keys.length) throw new IllegalArgumentException("Invalid heightmap key");
            heights[i] = keys[index];
        }
        return heights;
    }
}
