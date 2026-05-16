package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.camera.RequestFlyCameraMode;
import meridian.protocol.packets.camera.SetFlyCameraMode;
import meridian.protocol.packets.player.ClientMovement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NORMAL-position C2S handler. Feeds {@code ClientMovement} into
 * {@link EntityTrackerImpl} (player position + look), and — when
 * {@link CameraControlImpl#autoGrantFreecam()} is set — answers the client's
 * own {@code RequestFlyCameraMode} keybind directly, so freecam works without
 * the server's {@code FLY_CAM} permission.
 */
final class ClientObserver implements PacketHandler {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    private final EntityTrackerImpl tracker;
    private final CameraControlImpl camera;

    ClientObserver(EntityTrackerImpl tracker, CameraControlImpl camera) {
        this.tracker = tracker;
        this.camera = camera;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        camera.bind(session);
        if (packet instanceof ClientMovement movement) {
            tracker.onClientMovement(movement);
            return Action.FORWARD;
        }
        if (packet instanceof RequestFlyCameraMode request && camera.autoGrantFreecam()) {
            // Answer the keybind ourselves; do not relay the request to the server.
            session.sendToClient(new SetFlyCameraMode(request.entering));
            log.info("meridian-core: auto-granted freecam keybind (entering={})", request.entering);
            return Action.DROP;
        }
        return Action.FORWARD;
    }
}
