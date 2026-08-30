package meridian.core.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import meridian.protocol.packets.worldmap.MapImage;

/**
 * Turns a remembered tile back into a wire image, optionally at a smaller size.
 *
 * <p>Replaying explored tiles is only useful if the client can hold them, and its map has a
 * budget. Size is the lever: a tile rendered at half the side costs a quarter of the pixels, so
 * the same budget covers four times the world. The server picks the size of what it draws; the
 * proxy picks the size of what it replays, and this is where that happens.
 *
 * <p>The wire form is palette-indexed: the distinct colours of a tile go into a palette, each
 * pixel becomes an index, and indices are bit-packed at the smallest width that fits. Terrain
 * tiles hold few colours, so this is usually 1–4 bits per pixel. A tile with more colours than
 * one byte can index has its rarest colours snapped to the nearest kept one.
 */
final class MapImageEncoder {

    /** Palette entries are {@code 0xRRGGBBAA} on the wire; replayed tiles are fully opaque. */
    private static final int ALPHA = 0xFF;
    /** Widest index the format uses — 256 colours is far more than terrain needs. */
    private static final int MAX_PALETTE = 256;

    private MapImageEncoder() {
    }

    /**
     * Encodes {@code tile} at {@code targetSize} pixels per side (nearest-neighbour when it
     * differs from the tile's own size). Returns null if the tile has no usable pixels.
     */
    static MapImage encode(MapTileImpl tile, int targetSize) {
        int size = Math.max(1, targetSize);
        int[] pixels = resample(tile, size);

        // Palette: first-seen order, so the common colours land at low indices.
        Map<Integer, Integer> paletteIndex = new LinkedHashMap<>();
        for (int rgb : pixels) {
            if (paletteIndex.size() >= MAX_PALETTE) break;
            paletteIndex.putIfAbsent(rgb, paletteIndex.size());
        }
        if (paletteIndex.isEmpty()) return null;

        int[] palette = new int[paletteIndex.size()];
        paletteIndex.forEach((rgb, index) -> palette[index] = rgb << 8 | ALPHA);

        int bits = bitsFor(palette.length);
        byte[] packed = new byte[(pixels.length * bits + 7) / 8];
        for (int i = 0; i < pixels.length; i++) {
            int index = indexOf(paletteIndex, palette, pixels[i]);
            int bitOffset = i * bits;
            int byteIndex = bitOffset >>> 3;
            int bitShift = bitOffset & 7;
            packed[byteIndex] |= (byte) (index << bitShift);
            if (bitShift + bits > 8 && byteIndex + 1 < packed.length) {
                packed[byteIndex + 1] |= (byte) (index >>> (8 - bitShift));
            }
        }

        MapImage img = new MapImage();
        img.width = size;
        img.height = size;
        img.palette = palette;
        img.bitsPerIndex = (byte) bits;
        img.packedIndices = packed;
        return img;
    }

    /** The tile's pixels at {@code size} per side; nearest-neighbour, which suits flat terrain colour. */
    private static int[] resample(MapTileImpl tile, int size) {
        int[] out = new int[size * size];
        for (int z = 0; z < size; z++) {
            int sourceZ = z * tile.size() / size;
            for (int x = 0; x < size; x++) {
                int colour = tile.colourAt(x * tile.size() / size, sourceZ);
                out[z * size + x] = colour < 0 ? 0 : colour;
            }
        }
        return out;
    }

    /** Smallest power-of-two index width that addresses the whole palette. */
    private static int bitsFor(int paletteSize) {
        int bits = 1;
        while ((1 << bits) < paletteSize && bits < 8) {
            bits <<= 1;
        }
        return bits;
    }

    /** The palette index of a colour, or the nearest kept colour when the palette filled up. */
    private static int indexOf(Map<Integer, Integer> paletteIndex, int[] palette, int rgb) {
        Integer exact = paletteIndex.get(rgb);
        if (exact != null) return exact;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int candidate = palette[i] >>> 8;
            int dr = ((candidate >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
            int dg = ((candidate >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
            int db = (candidate & 0xFF) - (rgb & 0xFF);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
