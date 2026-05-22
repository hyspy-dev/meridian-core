package meridian.core.impl;

import meridian.api.session.ProxySession;
import meridian.core.api.DebugRender;
import meridian.core.api.EntityTracker;
import meridian.protocol.DebugShape;
import meridian.protocol.packets.buildertools.BuilderToolLaserPointer;
import meridian.protocol.packets.player.AddOrUpdateTriggerVolumeDisplay;
import meridian.protocol.packets.player.ClearDebugShapes;
import meridian.protocol.packets.player.DisplayDebug;
import meridian.protocol.packets.player.RemoveTriggerVolumeDisplay;
import meridian.protocol.packets.player.TriggerVolumeDisplayEntry;
import meridian.protocol.packets.player.TriggerVolumeShapeType;
import org.joml.Vector3f;

/**
 * Live {@link DebugRender} — sends {@code DisplayDebug} / {@code ClearDebugShapes}
 * straight to the client, the same packets Hytale's {@code /debug shape} commands
 * use. The {@link ProxySession} is captured from observed Default-channel traffic
 * by {@link ServerObserver} / {@link ClientObserver}.
 */
final class DebugRenderImpl implements DebugRender {

    // Solid + wireframe (no flags): a wireframe-only DebugShape renders untinted,
    // so the box must be filled to carry its colour. Opacity keeps it see-through.
    private static final byte FLAGS = 0;
    private static final float OPACITY = 0.5f;

    private final EntityTracker entityTracker;
    private volatile ProxySession session;

    DebugRenderImpl(EntityTracker entityTracker) {
        this.entityTracker = entityTracker;
    }

    /** Captured by the observers — the Default-channel session for this client. */
    void bind(ProxySession session) {
        this.session = session;
    }

    @Override
    public boolean available() {
        return session != null;
    }

    @Override
    public void box(double x, double y, double z,
                    double sizeX, double sizeY, double sizeZ,
                    float red, float green, float blue, float seconds) {
        ProxySession s = session;
        if (s == null) {
            return;
        }
        // DisplayDebug.matrix is a column-major 4x4 TRS: scale on the diagonal,
        // translation in the last column. A Cube is a unit cube on the origin,
        // so this places a sizeX x sizeY x sizeZ box centred on (x, y, z).
        float[] matrix = new float[16];
        matrix[0] = (float) sizeX;
        matrix[5] = (float) sizeY;
        matrix[10] = (float) sizeZ;
        matrix[12] = (float) x;
        matrix[13] = (float) y;
        matrix[14] = (float) z;
        matrix[15] = 1.0f;
        s.sendToClient(new DisplayDebug(DebugShape.Cube, matrix,
                new Vector3f(red, green, blue), seconds, FLAGS, null, OPACITY));
    }

    @Override
    public void clear() {
        ProxySession s = session;
        if (s != null) {
            s.sendToClient(new ClearDebugShapes());
        }
    }

    @Override
    public void worldBox(String id, double x, double y, double z,
                         double sizeX, double sizeY, double sizeZ,
                         float red, float green, float blue, float opacity) {
        ProxySession s = session;
        if (s == null) {
            return;
        }
        // Build a trigger-volume display entry — the editor's box overlay. The
        // client renders it depth-test-bypassed (the through-wall property).
        TriggerVolumeDisplayEntry entry = new TriggerVolumeDisplayEntry();
        entry.shapeType = TriggerVolumeShapeType.Box;
        entry.position = new Vector3f((float) x, (float) y, (float) z);
        // Per Hytale's own TriggerVolumeManager.buildDisplayEntry, `dimensions`
        // is HALF-extents — a 1x1x1 box is (0.5, 0.5, 0.5). Callers pass full
        // dimensions; halve here so the API reads naturally.
        entry.dimensions = new Vector3f(
                (float) (sizeX * 0.5), (float) (sizeY * 0.5), (float) (sizeZ * 0.5));
        entry.color = new Vector3f(red, green, blue);
        entry.opacity = opacity;
        // groupColor is the packed 0x00RRGGBB the renderer uses for the
        // group/occluded tint — without it occluded pixels fall back to grey.
        int r = (int) (red * 255.0f) & 0xFF;
        int g = (int) (green * 255.0f) & 0xFF;
        int b = (int) (blue * 255.0f) & 0xFF;
        entry.groupColor = (r << 16) | (g << 8) | b;
        // Editor metadata the renderer doesn't care about — keep them non-null /
        // zero so the entry serialises cleanly.
        entry.name = "";
        entry.groupId = "";
        entry.effectAssetRef = "";
        s.sendToClient(new AddOrUpdateTriggerVolumeDisplay(id, entry));
    }

    @Override
    public void clearWorldBox(String id) {
        ProxySession s = session;
        if (s != null) {
            s.sendToClient(new RemoveTriggerVolumeDisplay(id));
        }
    }

    @Override
    public void worldLine(double x1, double y1, double z1,
                          double x2, double y2, double z2,
                          float red, float green, float blue, float seconds) {
        ProxySession s = session;
        if (s == null) {
            return;
        }
        BuilderToolLaserPointer pkt = new BuilderToolLaserPointer();
        // Attribute the laser to the local player so the client routes it
        // through its own laser-overlay path; 0 if we have not yet seen
        // SetClientId (rare; the line still renders).
        pkt.playerNetworkId = entityTracker.localEntityId().orElse(0);
        pkt.startX = (float) x1;
        pkt.startY = (float) y1;
        pkt.startZ = (float) z1;
        pkt.endX = (float) x2;
        pkt.endY = (float) y2;
        pkt.endZ = (float) z2;
        // Pack as 0xFFRRGGBB. The server's laser tool uses 0x00RRGGBB
        // (ColorParseUtil.hexStringToRGBInt), but if the client treats the top
        // byte as alpha then zero alpha renders the line invisible — full alpha
        // is the safe bet.
        int r = (int) (red * 255.0f) & 0xFF;
        int g = (int) (green * 255.0f) & 0xFF;
        int b = (int) (blue * 255.0f) & 0xFF;
        pkt.color = 0xFF000000 | (r << 16) | (g << 8) | b;
        pkt.durationMs = Math.max(1, (int) (seconds * 1000.0f));
        s.sendToClient(pkt);
    }

    @Override
    public void wireBox(double x, double y, double z,
                        double sizeX, double sizeY, double sizeZ,
                        float red, float green, float blue, float seconds) {
        double hx = sizeX * 0.5, hy = sizeY * 0.5, hz = sizeZ * 0.5;
        double x0 = x - hx, x1 = x + hx;
        double y0 = y - hy, y1 = y + hy;
        double z0 = z - hz, z1 = z + hz;
        // 4 bottom edges
        worldLine(x0, y0, z0, x1, y0, z0, red, green, blue, seconds);
        worldLine(x1, y0, z0, x1, y0, z1, red, green, blue, seconds);
        worldLine(x1, y0, z1, x0, y0, z1, red, green, blue, seconds);
        worldLine(x0, y0, z1, x0, y0, z0, red, green, blue, seconds);
        // 4 top edges
        worldLine(x0, y1, z0, x1, y1, z0, red, green, blue, seconds);
        worldLine(x1, y1, z0, x1, y1, z1, red, green, blue, seconds);
        worldLine(x1, y1, z1, x0, y1, z1, red, green, blue, seconds);
        worldLine(x0, y1, z1, x0, y1, z0, red, green, blue, seconds);
        // 4 vertical edges
        worldLine(x0, y0, z0, x0, y1, z0, red, green, blue, seconds);
        worldLine(x1, y0, z0, x1, y1, z0, red, green, blue, seconds);
        worldLine(x1, y0, z1, x1, y1, z1, red, green, blue, seconds);
        worldLine(x0, y0, z1, x0, y1, z1, red, green, blue, seconds);
    }
}
