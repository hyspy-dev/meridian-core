package meridian.core.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class HeightmapDecoderTest {
    public void testSignedKeysAndPackedIndices() {
        byte[] data = new byte[2 + 6 + 4 + 1280];
        var out = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) 3).putShort((short) -32768).putShort((short) -20).putShort((short) 230).putInt(1280);
        int start = out.position();
        for (int i = 0; i < 1024; i++) {
            int value = i % 3;
            for (int b = 0; b < 10; b++) {
                int bit = i * 10 + b;
                data[start + (bit >> 3)] |= (byte) (((value >> b) & 1) << (bit & 7));
            }
        }
        int[] decoded = HeightmapDecoder.decode(data);
        for (int i = 0; i < 1024; i++) {
            int expected = switch (i % 3) { case 0 -> -32768; case 1 -> -20; default -> 230; };
            if (decoded[i] != expected) throw new AssertionError("height at " + i + ": " + decoded[i]);
        }
    }

    public void testRejectsTruncatedPalette() {
        try {
            byte[] truncated = new byte[8];
            truncated[0] = 2;
            HeightmapDecoder.decode(truncated);
            throw new AssertionError("accepted a truncated heightmap");
        } catch (IllegalArgumentException expected) { }
    }
}
