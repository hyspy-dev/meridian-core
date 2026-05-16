package meridian.core.impl;

import meridian.api.session.ProxySession;
import meridian.core.api.CameraControl;
import meridian.protocol.AttachedToType;
import meridian.protocol.ClientCameraView;
import meridian.protocol.Direction;
import meridian.protocol.Position;
import meridian.protocol.PositionDistanceOffsetType;
import meridian.protocol.RotationType;
import meridian.protocol.ServerCameraSettings;
import meridian.protocol.packets.camera.SetFlyCameraMode;
import meridian.protocol.packets.camera.SetServerCamera;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live {@link CameraControl} — builds the same camera packets Hytale's own
 * server commands use ({@code /camera topdown}, the fly-cam module) and writes
 * them straight to the client.
 *
 * <p>The {@link ProxySession} is captured from observed traffic by
 * {@link ServerObserver}; until one arrives every method is a logged no-op.
 */
final class CameraControlImpl implements CameraControl {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    private volatile ProxySession session;
    private volatile boolean autoGrantFreecam;

    /** Captured by the observers — the main-channel session for this client. */
    void bind(ProxySession session) {
        this.session = session;
    }

    boolean autoGrantFreecam() {
        return autoGrantFreecam;
    }

    @Override
    public boolean available() {
        return session != null;
    }

    @Override
    public void firstPerson() {
        send(new SetServerCamera(ClientCameraView.FirstPerson, false, null), "first-person");
    }

    @Override
    public void thirdPerson(double distance, double shiftX, double shiftY, double shiftZ,
                            boolean inverted) {
        ServerCameraSettings s = baseSettings();
        s.isFirstPerson = false;
        s.distance = (float) distance;
        s.eyeOffset = true;
        s.attachedToType = AttachedToType.LocalPlayer;
        s.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        if (shiftX != 0 || shiftY != 0 || shiftZ != 0) {
            s.positionOffset = new Position(shiftX, shiftY, shiftZ);
        }
        if (inverted) {
            // Flip the camera 180° in yaw: it swings to the player's front and
            // looks back — Minecraft's front-view (F5) mode.
            s.rotationType = RotationType.AttachedToPlusOffset;
            s.rotationOffset = new Direction((float) Math.PI, 0f, 0f);
        }
        send(new SetServerCamera(ClientCameraView.Custom, false, s),
                "third-person dist=" + distance
                        + " shift=(" + shiftX + "," + shiftY + "," + shiftZ + ")"
                        + (inverted ? " inverted" : ""));
    }

    @Override
    public void freecam(boolean enable) {
        send(new SetFlyCameraMode(enable), "freecam=" + enable);
    }

    @Override
    public void followEntity(int entityId, double distance,
                             double shiftX, double shiftY, double shiftZ) {
        ServerCameraSettings s = baseSettings();
        s.isFirstPerson = false;
        s.distance = (float) distance;
        s.eyeOffset = true;
        s.attachedToType = AttachedToType.EntityId;
        s.attachedToEntityId = entityId;
        s.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        if (shiftX != 0 || shiftY != 0 || shiftZ != 0) {
            s.positionOffset = new Position(shiftX, shiftY, shiftZ);
        }
        send(new SetServerCamera(ClientCameraView.Custom, false, s),
                "follow entity " + entityId + " dist=" + distance
                        + " shift=(" + shiftX + "," + shiftY + "," + shiftZ + ")");
    }

    @Override
    public void entityView(int entityId, double shiftX, double shiftY, double shiftZ) {
        ServerCameraSettings s = baseSettings();
        s.isFirstPerson = true;
        s.distance = 0f;
        s.eyeOffset = true;
        s.attachedToType = AttachedToType.EntityId;
        s.attachedToEntityId = entityId;
        if (shiftX != 0 || shiftY != 0 || shiftZ != 0) {
            s.positionOffset = new Position(shiftX, shiftY, shiftZ);
        }
        send(new SetServerCamera(ClientCameraView.Custom, false, s),
                "entity POV " + entityId
                        + " shift=(" + shiftX + "," + shiftY + "," + shiftZ + ")");
    }

    @Override
    public void reset() {
        // Mirrors CameraManager.resetCamera / PlayerCameraResetCommand.
        send(new SetServerCamera(ClientCameraView.Custom, false, null), "reset");
    }

    @Override
    public void autoGrantFreecam(boolean enable) {
        this.autoGrantFreecam = enable;
    }

    private static ServerCameraSettings baseSettings() {
        // Smooth follow, matching the lerp speeds the server's /camera presets use.
        ServerCameraSettings s = new ServerCameraSettings();
        s.positionLerpSpeed = 0.2f;
        s.rotationLerpSpeed = 0.2f;
        return s;
    }

    private void send(meridian.api.packet.Packet packet, String what) {
        ProxySession s = session;
        if (s == null) {
            log.warn("meridian-core: camera {} requested but no client session yet — ignored", what);
            return;
        }
        s.sendToClient(packet);
        log.info("meridian-core: camera -> {}", what);
    }
}
