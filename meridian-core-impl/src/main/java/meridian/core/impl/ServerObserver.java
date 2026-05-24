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
    private final DebugRenderImpl debugRender;

    ServerObserver(EntityTrackerImpl tracker, CameraControlImpl camera,
                   DebugRenderImpl debugRender) {
        this.tracker = tracker;
        this.camera = camera;
        this.debugRender = debugRender;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        // SetClientId / EntityUpdates travel on the Default channel — binding the
        // camera / debug-render session only on these keeps it off the Chunks
        // (or any other) stream, so their packets go out on Default.
        if (packet instanceof SetClientId setClientId) {
            camera.bind(session);
            debugRender.bind(session);
            tracker.onSetClientId(setClientId);
        } else if (packet instanceof EntityUpdates updates) {
            camera.bind(session);
            debugRender.bind(session);
            tracker.onEntityUpdates(updates);
        }
        return Action.FORWARD;
    }
}
