package meridian.core.impl;

import meridian.core.api.MapTile;
import meridian.core.api.WorldMap;
import meridian.protocol.packets.worldmap.MapImage;

/**
 * A decoded map tile.
 *
 * <p>The server sends each tile palette-indexed and bit-packed ({@code bitsPerIndex} bits per
 * pixel into {@code packedIndices}, colours as {@code 0xRRGGBBAA} in the palette). Decoding
 * happens once, here, so every consumer reads plain {@code 0xRRGGBB} and no module ever needs
 * the wire format. The decoded pixels are kept, not the packed source — a tile is small
 * ({@code size²} ints) and this is the form everything actually reads.
 */
final class MapTileImpl implements MapTile {

    private final int chunkX;
    private final int chunkZ;
    private final int size;
    private final int[] pixels;   // 0xRRGGBB, row-major

    private MapTileImpl(int chunkX, int chunkZ, int size, int[] pixels) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.size = size;
        this.pixels = pixels;
    }

    /** Rebuilds a tile from the RGB bytes written by {@link WorldMapStore}. */
    /** A tile from colours somebody else worked out - the repainted form of another tile. */
    static MapTileImpl fromPixels(int chunkX, int chunkZ, int size, int[] pixels) {
        return new MapTileImpl(chunkX, chunkZ, size, pixels);
    }

    static MapTileImpl fromRgb(int chunkX, int chunkZ, int size, byte[] rgb) {
        int[] pixels = new int[size * size];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (rgb[i * 3] & 0xFF) << 16 | (rgb[i * 3 + 1] & 0xFF) << 8 | (rgb[i * 3 + 2] & 0xFF);
        }
        return new MapTileImpl(chunkX, chunkZ, size, pixels);
    }

    /** The tile's pixels as RGB triplets, the form {@link WorldMapStore} keeps on disk. */
    byte[] toRgb() {
        byte[] rgb = new byte[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) {
            rgb[i * 3] = (byte) (pixels[i] >> 16);
            rgb[i * 3 + 1] = (byte) (pixels[i] >> 8);
            rgb[i * 3 + 2] = (byte) pixels[i];
        }
        return rgb;
    }

    /** Decodes a wire image, or returns null when it carries nothing usable. */
    static MapTileImpl decode(int chunkX, int chunkZ, MapImage img) {
        if (img == null || img.packedIndices == null || img.palette == null
                || img.palette.length == 0) {
            return null;
        }
        int bits = img.bitsPerIndex & 0xFF;
        if (bits == 0) return null;
        int side = img.width > 0 ? img.width : guessSide(img, bits);
        if (side <= 0) return null;

        int[] pixels = new int[side * side];
        for (int i = 0; i < pixels.length; i++) {
            // Palette entries are 0xRRGGBBAA — the alpha is the LOW byte, so dropping it is a
            // shift, not a mask (masking would keep GGBBAA and shift every colour).
            pixels[i] = (paletteColour(img, bits, i) >>> 8) & 0xFFFFFF;
        }
        return new MapTileImpl(chunkX, chunkZ, side, pixels);
    }

    /** Side of an image whose {@code width} field is zero — inferred from the packed length. */
    private static int guessSide(MapImage img, int bits) {
        int totalPixels = img.packedIndices.length * 8 / bits;
        return (int) Math.round(Math.sqrt(totalPixels));
    }

    /** Bit-packed palette lookup: the pixel's index may straddle a byte boundary. */
    private static int paletteColour(MapImage img, int bits, int index) {
        int bitOffset = index * bits;
        int byteIndex = bitOffset >>> 3;
        if (byteIndex >= img.packedIndices.length) return 0;
        int bitShift = bitOffset & 7;
        int raw = (img.packedIndices[byteIndex] & 0xFF) >>> bitShift;
        if (bitShift + bits > 8 && byteIndex + 1 < img.packedIndices.length) {
            raw |= (img.packedIndices[byteIndex + 1] & 0xFF) << (8 - bitShift);
        }
        int paletteIndex = raw & ((1 << bits) - 1);
        return paletteIndex < img.palette.length ? img.palette[paletteIndex] : 0;
    }

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public int colourAt(int pixelX, int pixelZ) {
        if (pixelX < 0 || pixelZ < 0 || pixelX >= size || pixelZ >= size) return -1;
        return pixels[pixelZ * size + pixelX];
    }

    @Override
    public int colourAtBlock(int blockX, int blockZ) {
        // A tile covers one chunk column, so the block's offset inside the column scales to
        // the tile's own resolution — which is not always 1 px per block (the server can be
        // asked to render smaller tiles to fit more of the world in the client at once).
        if (Math.floorDiv(blockX, WorldMap.TILE_BLOCKS) != chunkX
                || Math.floorDiv(blockZ, WorldMap.TILE_BLOCKS) != chunkZ) {
            return -1;
        }
        int offsetX = Math.floorMod(blockX, WorldMap.TILE_BLOCKS);
        int offsetZ = Math.floorMod(blockZ, WorldMap.TILE_BLOCKS);
        return colourAt(offsetX * size / WorldMap.TILE_BLOCKS, offsetZ * size / WorldMap.TILE_BLOCKS);
    }
}
