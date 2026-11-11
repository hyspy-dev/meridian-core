package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.camera.RequestFlyCameraMode;
import meridian.protocol.packets.camera.SetFlyCameraMode;
import meridian.protocol.packets.entities.MountMovement;
import meridian.protocol.packets.player.ClientMovement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NORMAL-position C2S handler, and the one rewriter of the player's movement reports. Feeds
 * {@code ClientMovement} into {@link EntityTrackerImpl} (player position + look) before anything
 * changes it, then lets {@link MovementControlImpl} forge the position and {@link ClientSpoofImpl}
 * the flags and the fall speed ({@code MountMovement}: the flags). And - when
 * {@link CameraControlImpl#autoGrantFreecam()} is set - answers the client's own
 * {@code RequestFlyCameraMode} keybind directly, so freecam works without the server's
 * {@code FLY_CAM} permission.
 *
 * <p>Core's interaction observer, at NORMAL after this one, reads the movement flags as the server
 * gets them: the server's {@code ConditionInteraction} tests the flags it was told.
 */
final class ClientObserver implements PacketHandler {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    private final EntityTrackerImpl tracker;
    private final CameraControlImpl camera;
    private final DebugRenderImpl debugRender;
    private final MovementControlImpl movement;
    private final ClientSpoofImpl spoof;

    ClientObserver(EntityTrackerImpl tracker, CameraControlImpl camera,
                   DebugRenderImpl debugRender, MovementControlImpl movement, ClientSpoofImpl spoof) {
        this.tracker = tracker;
        this.camera = camera;
        this.debugRender = debugRender;
        this.movement = movement;
        this.spoof = spoof;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        // ClientMovement / RequestFlyCameraMode travel on the Default channel —
        // binding the camera / debug-render session only on these keeps it off
        // any other C2S stream, so their packets go out on Default.
        if (packet instanceof ClientMovement clientMovement) {
            camera.bind(session);
            debugRender.bind(session);
            movement.bind(session);
            // Feed the tracker the player's TRUE position before any rewrite, so
            // position() / raycast / ESP keep reflecting where the client really
            // is, even while a teleport/hold sends a different position upstream.
            tracker.onClientMovement(clientMovement);
            // Then let MovementControl forge the outgoing position if armed, and the spoof the
            // flags and the fall speed.
            boolean changed = movement.rewrite(clientMovement);
            changed |= spoof.rewrite(clientMovement);
            return changed ? Action.MODIFIED : Action.FORWARD;
        }
        if (packet instanceof MountMovement mount) {
            return spoof.rewrite(mount) ? Action.MODIFIED : Action.FORWARD;
        }
        if (packet instanceof RequestFlyCameraMode request) {
            camera.bind(session);
            debugRender.bind(session);
            if (camera.autoGrantFreecam()) {
                // Answer the keybind ourselves; do not relay the request.
                session.sendToClient(new SetFlyCameraMode(request.entering));
                log.info("meridian-core: auto-granted freecam keybind (entering={})",
                        request.entering);
                return Action.DROP;
            }
        }
        return Action.FORWARD;
    }
}
