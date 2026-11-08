package meridian.core.impl.movement;

import io.netty.channel.ChannelHandlerContext;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import meridian.api.module.Scheduler;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.HeadlessClient;
import meridian.api.session.ProxySession;
import meridian.core.api.BlockPos;
import meridian.core.api.Movement;
import meridian.core.api.Vec3;
import meridian.protocol.packets.assets.UpdateBlockHitboxes;
import meridian.protocol.packets.player.ClientTeleport;
import meridian.protocol.packets.player.UpdateMovementSettings;

/**
 * {@link Movement}: what the ground allows ({@link Terrain}) and the body that moves on it
 * ({@link Body}), for a headless session. Hears the server's block shapes and movement settings,
 * and its teleports, which cut a move short.
 */
public final class MovementImpl implements Movement {

    private final Terrain terrain;
    private final BlockHitboxes hitboxes;
    private final BodySettings settings = new BodySettings();
    private final Body body;
    private final Supplier<HeadlessClient> client;
    private final Supplier<Vec3> trackedFeet;

    public MovementImpl(Terrain terrain, BlockHitboxes hitboxes, Scheduler scheduler,
                        Supplier<HeadlessClient> client, Supplier<Vec3> trackedFeet) {
        this.terrain = terrain;
        this.hitboxes = hitboxes;
        this.client = client;
        this.trackedFeet = trackedFeet;
        this.body = new Body(scheduler, terrain, settings, client, trackedFeet);
    }

    /** The handler that feeds it: block shapes, movement settings, teleports (S2C). */
    public PacketHandler observer() {
        return new PacketHandler() {
            @Override
            public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
                if (packet instanceof UpdateBlockHitboxes shapes) {
                    hitboxes.update(shapes);
                } else if (packet instanceof UpdateMovementSettings update) {
                    settings.update(update.movementSettings);
                } else if (packet instanceof ClientTeleport) {
                    body.teleported();
                }
                return Action.FORWARD;
            }
        };
    }

    @Override
    public boolean available() {
        return client.get() != null;
    }

    @Override
    public Capabilities capabilities() {
        return settings.capabilities();
    }

    @Override
    public OptionalDouble standAt(BlockPos cell) {
        return terrain.standAt(cell.x(), cell.y(), cell.z());
    }

    @Override
    public boolean fits(Vec3 feet, double height) {
        return terrain.fits(feet.x(), feet.y(), feet.z(), height);
    }

    @Override
    public boolean known(BlockPos cell) {
        return terrain.known(cell.x(), cell.y(), cell.z());
    }

    @Override
    public Vec3 position() {
        Vec3 p = body.position();
        return p != null ? p : trackedFeet.get();
    }

    @Override
    public boolean onGround() {
        return body.onGround();
    }

    @Override
    public CompletableFuture<MoveResult> move(Move move) {
        if (move == null || move.to() == null || move.kind() == null) {
            return CompletableFuture.completedFuture(new MoveResult(MoveResult.Status.BLOCKED, position()));
        }
        Move m = move.gait() == null ? new Move(move.kind(), move.to(), Gait.RUN) : move;
        return body.enqueue(m);
    }

    @Override
    public void stop() {
        body.stop();
    }
}
