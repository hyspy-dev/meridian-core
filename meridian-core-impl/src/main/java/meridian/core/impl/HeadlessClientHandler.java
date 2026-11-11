package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.HeadlessClient;
import meridian.api.session.ProxySession;
import meridian.protocol.Asset;
import meridian.protocol.Direction;
import meridian.protocol.ModelTransform;
import meridian.protocol.Position;
import meridian.protocol.ComponentUpdate;
import meridian.protocol.TeleportAck;
import meridian.protocol.TransformUpdate;
import meridian.protocol.packets.entities.EntityUpdates;
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

    HeadlessClientHandler(EntityTrackerImpl pose) {
        this.pose = pose;
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
            placed = false;       // 0.5.9: placed in the new world with no teleport, see spawn()
            log.info("meridian-core: headless {} - joining world {}, ready for chunks", client.username(), join.worldUuid);
        } else if (packet instanceof SetClientId id) {
            ClientReady ready = new ClientReady();
            ready.readyForChunks = true;
            ready.readyForGameplay = true;
            client.sendAsClient(ready);
            log.info("meridian-core: headless {} - entity {}, ready for gameplay", client.username(), id.clientId);
        } else if (packet instanceof ClientTeleport teleport) {
            acknowledge(client, teleport);
        } else if (packet instanceof EntityUpdates updates && !placed) {
            spawn(client, updates);
        }
        return Action.FORWARD;
    }

    /** Whether the bot knows where it stands in the world it joined last (0.5.9, see spawn()). */
    private volatile boolean placed;

    /**
     * 0.5.9: the server puts a player into the world with no teleport - the game takes where it
     * stands from its own entity's transform - in every world it joins. The bot does the same, once
     * per join, and says so with a movement there, which is how core learns where it is.
     */
    private void spawn(HeadlessClient client, EntityUpdates updates) {
        int self = pose.localEntityId().orElse(Integer.MIN_VALUE);
        if (self == Integer.MIN_VALUE || updates.updates == null) {
            return;
        }
        for (var update : updates.updates) {
            if (update == null || update.networkId != self || update.updates == null) {
                continue;
            }
            for (ComponentUpdate component : update.updates) {
                if (component instanceof TransformUpdate t && t.transform != null && t.transform.position != null) {
                    ModelTransform m = t.transform;
                    position = new Position(m.position.x, m.position.y, m.position.z);
                    if (m.bodyOrientation != null) {
                        body = m.bodyOrientation;
                    }
                    if (m.lookOrientation != null) {
                        look = m.lookOrientation;
                    }
                    ClientMovement here = new ClientMovement();
                    here.absolutePosition = new Position(position.x, position.y, position.z);
                    here.bodyOrientation = body;
                    here.lookOrientation = look;
                    client.sendAsClient(here);
                    placed = true;
                    log.info("meridian-core: headless {} - in the world at {}, {}, {} (no teleport on 0.5.9)",
                            client.username(), position.x, position.y, position.z);
                    return;
                }
            }
        }
    }

    /** Moves to where the teleport says, then says so with its id - the position must match it. */
    private void acknowledge(HeadlessClient client, ClientTeleport teleport) {
        placed = true;
        ModelTransform t = teleport.modelTransform;
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
            // 0.5.9: a teleport is absolute - no ignored or relative fields.
            at.x = t.position.x;
            at.y = t.position.y;
            at.z = t.position.z;
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

    /** The session's headless client, or {@code null} - also on a proxy too old to know of one. */
    static HeadlessClient headlessOf(ProxySession session) {
        try {
            return session.headless();
        } catch (NoSuchMethodError olderProxy) {
            return null;
        }
    }
}
