package meridian.core.impl;

import java.util.Arrays;
import meridian.core.impl.movement.Terrain;
import meridian.protocol.packets.world.SetChunk;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.UnloadChunk;

/** A streamed cave ceiling must not win a race against the column's higher surface. */
public class SurfaceLoadingTest {
    public void testWaitsForAdvertisedTop() {
        var chunks = new ChunkTracker(new FluidCatalog());
        var terrain = new Terrain(chunks, null, null);
        for (int y : new int[] {2, 4, 6, 7, 8, 9, 10, 11, 12}) section(chunks, y);
        section(chunks, 1);
        block(chunks, 63);
        int[] heights = new int[1024];
        Arrays.fill(heights, 122);
        chunks.onColumnHeights(1, 0, heights);
        check(terrain.surfaceAt(0, 0).isEmpty(), "landed before this column's heightmap arrived");
        chunks.onColumnHeights(0, 0, heights);
        check(terrain.surfaceAt(0, 0).isEmpty(), "landed on the cave before the top arrived");
        section(chunks, 3);
        block(chunks, 122);
        check(terrain.surfaceAt(0, 0).orElse(-1) == 123, "did not land on the real surface");
        chunks.onColumnHeight(0, 0, 160);
        check(terrain.surfaceAt(0, 0).isEmpty(), "ignored a top change to an unknown section");
    }

    public void testHeightmapForgottenWithColumn() {
        var chunks = new ChunkTracker(new FluidCatalog());
        int[] data = new int[1024];
        Arrays.fill(data, 100);
        chunks.onColumnHeights(0, 0, data);
        data[0] = 7;
        check(chunks.columnHeightAt(0, 0) == 100, "heightmap shares a mutable packet array");
        var unload = new UnloadChunk();
        unload.chunkX = unload.chunkZ = 0;
        chunks.onUnloadChunk(unload);
        check(chunks.columnHeightAt(0, 0) == Integer.MIN_VALUE, "unload retained another world's top");
        chunks.onColumnHeights(0, 0, data);
        chunks.onClearChunks();
        check(chunks.columnHeightAt(0, 0) == Integer.MIN_VALUE, "clear retained another world's top");
    }

    private static void section(ChunkTracker chunks, int y) {
        var packet = new SetChunk();
        packet.x = packet.z = 0;
        packet.y = y;
        chunks.onSetChunk(packet);
    }

    private static void block(ChunkTracker chunks, int y) {
        var packet = new ServerSetBlock();
        packet.x = packet.z = 0;
        packet.y = y;
        packet.blockId = 1;
        chunks.onServerSetBlock(packet);
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
