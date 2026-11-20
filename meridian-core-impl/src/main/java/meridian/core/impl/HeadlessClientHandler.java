package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.HeadlessClient;
import meridian.api.session.ProxySession;
import meridian.protocol.Asset;
import meridian.protocol.Direction;
import meridian.protocol.ModelTransform;
import meridian.protocol.ModelTransformFields;
import meridian.protocol.Position;
import meridian.protocol.TeleportAck;
import meridian.protocol.packets.connection.Ping;
import meridian.protocol.packets.connection.Pong;
import meridian.protocol.packets.connection.PongType;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.ClientReady;
import meridian.protocol.packets.player.ClientTeleport;
import meridian.protocol.packets.player.JoinWorld;
import meridian.protocol.packets.player.SetClientId;
import meridian.protocol.packets.setup.RequestAssets;
import meridian.protocol.packets.setup.SetupFinalize;
import meridian.protocol.packets.setup.ViewRadius;
import meridian.protocol.packets.setup.WorldLoadFinished;
import meridian.protocol.packets.setup.WorldSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's side of the conversation, for a session with no game behind it (headless): what the
 * client says to get into a world and stay there.
 *
 * <p>The proxy has already connected and authenticated. From there the server leads and the client
 * answers (the server's {@code SetupPacketHandler} and {@code GamePacketHandler}):
 * <ol>
 *   <li>{@code WorldSettings} - ask for no assets: an empty list, not none, which would be all of them;</li>
 *   <li>{@code WorldLoadFinished} - the view radius, then {@code SetupFinalize};</li>
 *   <li>{@code JoinWorld} - ready for chunks (within 30 s, or the join fails);</li>
 *   <li>{@code SetClientId} - ready for gameplay;</li>
 *   <li>{@code ClientTeleport} - acknowledged with a movement at the exact target, the way the
 *       server checks it ({@code TeleportAckTracker});</li>
 *   <li>{@code Ping} - one {@code Pong} of each kind: the server queues every ping three times and
 *       takes each kind's pongs in order, closing the connection on one out of turn.</li>
 * </ol>
 *
 * <p>An ordinary session is left alone. Registered early, so a packet another handler drops is
 * still answered.
 */
final class HeadlessClientHandler implements PacketHandler {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** The view radius asked for, in blocks: the server's default of 6 chunks. */
    private static final int VIEW_RADIUS = 6 * 32;

    /**
     * The bot's own pose as core tracks it from what the bot sends: a teleport of core's, a turn to
     * face something. A server teleport that leaves an axis out, or moves by an offset, starts there.
     */
    private final EntityTrackerImpl pose;

    /** Where the server last put us, and which way we face - for when core has seen no movement yet. */
    private Position position;
    private Direction body;
    private Direction look;
    /** Whether a page of the server's is up on the headless client, owed an acknowledgement on its close. */
    private boolean pageUp;

    /** The game's user folder: the player's own preferences ({@link PlayerPreferences}) are read from it. */
    private final java.util.Optional<java.nio.file.Path> userDir;

    HeadlessClientHandler(EntityTrackerImpl pose) {
        this(pose, java.util.Optional.empty());
    }

    HeadlessClientHandler(EntityTrackerImpl pose, java.util.Optional<java.nio.file.Path> userDir) {
        this.pose = pose;
        this.userDir = userDir;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        HeadlessClient client = headlessOf(session);
        if (client == null) {
            return Action.FORWARD;
        }
        if (packet instanceof Ping ping) {
            for (PongType type : PongType.values()) {
                Pong pong = new Pong();
                pong.id = ping.id;
                pong.time = ping.time;
                pong.type = type;
                client.sendAsClient(pong);
            }
        } else if (packet instanceof WorldSettings) {
            RequestAssets request = new RequestAssets();
            request.assets = new Asset[0];
            client.sendAsClient(request);
            log.info("meridian-core: headless {} - world settings in, no assets asked for", client.username());
        } else if (packet instanceof WorldLoadFinished) {
            ViewRadius radius = new ViewRadius();
            radius.value = VIEW_RADIUS;
            client.sendAsClient(radius);
            client.sendAsClient(new SetupFinalize());
            log.info("meridian-core: headless {} - setup done", client.username());
        } else if (packet instanceof JoinWorld join) {
            ClientReady ready = new ClientReady();
            ready.readyForChunks = true;
            ready.readyForGameplay = false;
            client.sendAsClient(ready);
            log.info("meridian-core: headless {} - joining world {}, ready for chunks", client.username(), join.worldUuid);
        } else if (packet instanceof SetClientId id) {
            ClientReady ready = new ClientReady();
            ready.readyForChunks = true;
            ready.readyForGameplay = true;
            client.sendAsClient(ready);
            log.info("meridian-core: headless {} - entity {}, ready for gameplay", client.username(), id.clientId);
            // The player's preferences, as the game sends them on joining: hidden armor stays hidden,
            // picked-up items go where the player has them go.
            PlayerPreferences.fromGame(userDir).ifPresent(prefs -> {
                client.sendAsClient(prefs);
                log.info("meridian-core: headless {} - the player's preferences sent (armor hidden: {} {} {} {})",
                        client.username(), prefs.hideHelmet, prefs.hideCuirass, prefs.hideGauntlets, prefs.hidePants);
            });
        } else if (packet instanceof ClientTeleport teleport) {
            acknowledge(client, teleport);
        } else if (packet instanceof meridian.protocol.packets.interface_.CustomPage) {
            // A client acknowledges every page packet, and a SetPage while a page is up: the
            // server takes no button press of a page until it has its acknowledgements.
            client.sendAsClient(new meridian.protocol.packets.interface_.CustomPageEvent(
                    meridian.protocol.packets.interface_.CustomPageEventType.Acknowledge, null));
            pageUp = true;
        } else if (packet instanceof meridian.protocol.packets.interface_.SetPage && pageUp) {
            client.sendAsClient(new meridian.protocol.packets.interface_.CustomPageEvent(
                    meridian.protocol.packets.interface_.CustomPageEventType.Acknowledge, null));
            pageUp = false;
        }
        return Action.FORWARD;
    }

    /** Moves to where the teleport says, then says so with its id - the position must match it. */
    private void acknowledge(HeadlessClient client, ClientTeleport teleport) {
        ModelTransform t = teleport.modelTransform;
        short ignored = teleport.ignoredTransformFields;
        short relative = teleport.relativeTransformFields;
        meridian.core.api.Vec3 me = pose.localPosition().orElse(null);
        Position at = me != null ? new Position(me.x(), me.y(), me.z())
                : position != null ? new Position(position.x, position.y, position.z) : new Position();
        meridian.core.api.Vec3 angles = pose.localLookAngles();
        if (angles != null) {
            // pitch, yaw, roll - the way the bot last turned; its body follows the yaw.
            look = new Direction((float) angles.y(), (float) angles.x(), (float) angles.z());
            body = new Direction((float) angles.y(), 0f, 0f);
        }
        if (t != null && t.position != null) {
            at.x = axis(at.x, t.position.x, ignored, relative, ModelTransformFields.PositionX);
            at.y = axis(at.y, t.position.y, ignored, relative, ModelTransformFields.PositionY);
            at.z = axis(at.z, t.position.z, ignored, relative, ModelTransformFields.PositionZ);
        }
        position = at;
        if (t != null && t.bodyOrientation != null) {
            body = t.bodyOrientation;
        }
        if (t != null && t.lookOrientation != null) {
            look = t.lookOrientation;
        }
        ClientMovement ack = new ClientMovement();
        ack.teleportAck = new TeleportAck(teleport.teleportId);
        ack.absolutePosition = new Position(at.x, at.y, at.z);
        ack.bodyOrientation = body;
        ack.lookOrientation = look;
        client.sendAsClient(ack);
        log.info("meridian-core: headless {} - teleport {} to {}, {}, {} acknowledged", client.username(),
                teleport.teleportId, at.x, at.y, at.z);
    }

    private static double axis(double current, double value, short ignored, short relative, short field) {
        if ((ignored & field) != 0) {
            return current;
        }
        return (relative & field) != 0 ? current + value : value;
    }

    /** The session's headless client, or {@code null} - also on a proxy too old to know of one. */
    static HeadlessClient headlessOf(ProxySession session) {
        try {
            return session.headless();
        } catch (NoSuchMethodError olderProxy) {
            return null;
        }
    }
}
