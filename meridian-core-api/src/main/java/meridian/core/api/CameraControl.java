package meridian.core.api;

/**
 * Client-camera control — a meridian-core Layer-1 service.
 *
 * <p>Every method translates into the camera packets Hytale's server itself
 * uses ({@code SetServerCamera} / {@code SetFlyCameraMode}) and writes them to
 * the live client connection via {@code ProxySession#sendToClient}. A Layer-2
 * consumer therefore drives the camera without ever touching
 * {@code meridian-protocol}.
 *
 * <p>All calls are no-ops while {@link #available()} is {@code false} (no
 * client connected yet); a consumer should re-apply its desired state once the
 * session reaches a playable phase.
 */
public interface CameraControl {

    /** Native first-person view ({@code ClientCameraView.FirstPerson}). */
    void firstPerson();

    /**
     * Third-person view with a tunable offset. {@code distance} is how far the
     * camera trails the player; {@code shiftX/Y/Z} nudge the camera anchor
     * (in blocks) relative to the player's eye. When {@code inverted} is set the
     * camera is flipped 180° to face the player from the front, looking back —
     * the Minecraft front-view (F5) mode.
     */
    void thirdPerson(double distance, double shiftX, double shiftY, double shiftZ,
                     boolean inverted);

    /** Enters ({@code true}) or leaves ({@code false}) free-fly camera mode. */
    void freecam(boolean enable);

    /**
     * Attaches the camera to entity {@code entityId}, trailing it at
     * {@code distance} blocks (a spectator-style follow cam). {@code shiftX/Y/Z}
     * nudge the camera anchor (in blocks) relative to the entity.
     */
    void followEntity(int entityId, double distance,
                      double shiftX, double shiftY, double shiftZ);

    /**
     * Attaches the camera <em>inside</em> entity {@code entityId} — a
     * first-person POV. {@code shiftX/Y/Z} nudge the eye position (in blocks).
     */
    void entityView(int entityId, double shiftX, double shiftY, double shiftZ);

    /** Restores the default player camera and leaves freecam if active. */
    void reset();

    /**
     * When enabled, the proxy answers the client's own freecam keybind
     * ({@code RequestFlyCameraMode}) directly, bypassing the server's
     * {@code FLY_CAM} permission check.
     */
    void autoGrantFreecam(boolean enable);

    /** {@code true} once a client session is live and camera packets can be sent. */
    boolean available();
}
