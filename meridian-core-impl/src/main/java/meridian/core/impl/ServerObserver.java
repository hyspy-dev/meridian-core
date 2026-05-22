package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.player.SetClientId;

/**
 * MONITOR-position S2C handler. Feeds {@code SetClientId} and {@code EntityUpdates}
 * into {@link EntityTrackerImpl}, and captures the live {@link ProxySession} for
 * {@link CameraControlImpl}. Never mutates traffic.
 */
final class ServerObserver implements PacketHandler {
    private final EntityTrackerImpl tracker;
    private final CameraControlImpl camera;

    ServerObserver(EntityTrackerImpl tracker, CameraControlImpl camera) {
        this.tracker = tracker;
        this.camera = camera;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        // SetClientId / EntityUpdates travel on the Default channel — binding the
        // camera only on these keeps its session off the Chunks (or any other)
        // stream, so SetFlyCameraMode / SetServerCamera go out on Default.
        if (packet instanceof SetClientId setClientId) {
            camera.bind(session);
            tracker.onSetClientId(setClientId);
        } else if (packet instanceof EntityUpdates updates) {
            camera.bind(session);
            tracker.onEntityUpdates(updates);
        }
        return Action.FORWARD;
    }
}
