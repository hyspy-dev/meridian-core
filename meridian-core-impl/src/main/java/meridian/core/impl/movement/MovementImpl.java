package meridian.core.impl.movement;

import io.netty.channel.ChannelHandlerContext;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import meridian.api.module.Scheduler;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.HeadlessClient;
import meridian.api.session.ProxySession;
import meridian.core.api.BlockPos;
import meridian.core.api.FluidCell;
import meridian.core.api.Movement;
import meridian.core.api.Vec3;
import meridian.protocol.BlockMaterial;
import meridian.protocol.ComponentUpdate;
import meridian.protocol.EntityUpdate;
import meridian.protocol.ModelUpdate;
import meridian.protocol.packets.assets.UpdateBlockHitboxes;
import meridian.protocol.packets.assets.UpdateEntityEffects;
import meridian.protocol.packets.assets.UpdateFluidFX;
import meridian.protocol.packets.assets.UpdateFluids;
import meridian.protocol.packets.entities.ChangeVelocity;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.ClientTeleport;
import meridian.protocol.packets.player.SetGameMode;
import meridian.protocol.packets.player.SetMovementStates;
import meridian.protocol.packets.player.UpdateMovementSettings;
import meridian.protocol.packets.setup.UpdateFeatures;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.ServerSetBlocks;

/**
 * {@link Movement}: what the ground allows ({@link Terrain}), the body's physics ({@link Physics})
 * and the body that lives by it ({@link Body}), for a headless session. Hears everything the client
 * moves by: the server's block shapes, movement settings, the player's model, fluids, effects,
 * features, game mode, pushes and teleports; and on the client side, positions the body did not
 * send itself.
 */
public final class MovementImpl implements Movement {

    private final Terrain terrain;
    private final BlockHitboxes hitboxes;
    private final Surroundings around = new Surroundings();
    private final Physics physics;
    private final BodySettings reach;
    private final Body body;
    private final Supplier<HeadlessClient> client;
    private final Supplier<Vec3> trackedFeet;
    private final Supplier<OptionalInt> localId;
    private final IntFunction<Set<Integer>> effectsOf;

    public MovementImpl(Terrain terrain, BlockHitboxes hitboxes, Scheduler scheduler,
                        Supplier<HeadlessClient> client, Supplier<Vec3> trackedFeet,
                        Supplier<OptionalInt> localId, IntFunction<Set<Integer>> effectsOf) {
        this.terrain = terrain;
        this.hitboxes = hitboxes;
        this.client = client;
        this.trackedFeet = trackedFeet;
        this.localId = localId;
        this.effectsOf = effectsOf;
        this.physics = new Physics(terrain, around);
        this.reach = new BodySettings(physics);
        this.body = new Body(scheduler, physics, client, this::effects);
    }

    private Surroundings.EffectMotion effects() {
        OptionalInt id = localId.get();
        if (id.isEmpty()) {
            return null;
        }
        Set<Integer> active = effectsOf.apply(id.getAsInt());
        return active == null || active.isEmpty() ? null : around.effects(active);
    }

    /** The server's side: block shapes, settings, the model, fluids, effects, features, pushes, teleports. */
    public PacketHandler observer() {
        return new PacketHandler() {
            @Override
            public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
                if (packet instanceof UpdateBlockHitboxes shapes) {
                    hitboxes.update(shapes);
                } else if (packet instanceof UpdateMovementSettings update) {
                    around.settings(update.movementSettings);
                    body.nudge();
                } else if (packet instanceof ClientTeleport teleport) {
                    body.teleported(teleport.resetVelocity);
                } else if (packet instanceof ChangeVelocity push) {
                    body.push(push.x, push.y, push.z, push.changeType, push.config);
                } else if (packet instanceof UpdateFluids fluids) {
                    around.fluids(fluids);
                } else if (packet instanceof UpdateFluidFX fx) {
                    around.fluidFx(fx);
                } else if (packet instanceof UpdateEntityEffects effects) {
                    around.effects(effects);
                } else if (packet instanceof UpdateFeatures features) {
                    around.features(features.features);
                } else if (packet instanceof SetGameMode mode) {
                    around.gameMode(mode.gameMode);
                } else if (packet instanceof SetMovementStates states && states.movementStates != null) {
                    around.flyingSaved(states.movementStates.flying);
                    body.setFlying(states.movementStates.flying);
                } else if (packet instanceof EntityUpdates updates) {
                    model(updates);
                } else if (packet instanceof ServerSetBlock || packet instanceof ServerSetBlocks) {
                    body.nudge();
                }
                return Action.FORWARD;
            }
        };
    }

    /** The client's side: a position the body did not send (a teleport's answer, core's own teleport). */
    public PacketHandler clientObserver() {
        return new PacketHandler() {
            @Override
            public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
                if (packet instanceof ClientMovement m && client.get() != null) {
                    body.sentByOther(m);
                }
                return Action.FORWARD;
            }
        };
    }

    private void model(EntityUpdates updates) {
        OptionalInt me = localId.get();
        if (me.isEmpty() || updates.updates == null) {
            return;
        }
        for (EntityUpdate u : updates.updates) {
            if (u == null || u.networkId != me.getAsInt() || u.updates == null) {
                continue;
            }
            for (ComponentUpdate c : u.updates) {
                if (c instanceof ModelUpdate model && model.model != null) {
                    around.model(model.model, model.entityScale);
                }
            }
        }
    }

    /**
     * The body as forged chains push it ({@code ApplyForceInteraction}): pushed as by the server's
     * {@code ChangeVelocity}, looking where it looks. Nothing happens without a headless body.
     */
    public meridian.core.impl.interaction.ForceBody forceBody() {
        return new meridian.core.impl.interaction.ForceBody() {
            @Override
            public void push(double x, double y, double z, meridian.protocol.ChangeVelocityType type,
                             meridian.protocol.VelocityConfig config) {
                if (client.get() != null) {
                    body.push((float) x, (float) y, (float) z, type, config);
                }
            }

            @Override
            public boolean grounded() {
                BodyState s = body.snapshot();
                return s != null && (s.onGround || s.inFluid || s.climbing);
            }

            @Override
            public float yaw() {
                BodyState s = body.snapshot();
                return s == null ? 0f : s.yaw;
            }

            @Override
            public float pitch() {
                BodyState s = body.snapshot();
                return s == null ? 0f : s.pitch;
            }

            @Override
            public meridian.protocol.MovementDirection direction() {
                Controls k = body.lastControls();
                if (k == null) {
                    return meridian.protocol.MovementDirection.None;
                }
                int f = k.forward() > 0.3 ? 1 : k.forward() < -0.3 ? -1 : 0;
                int r = k.strafe() > 0.3 ? 1 : k.strafe() < -0.3 ? -1 : 0;
                return switch (f * 3 + r) {
                    case 3 -> meridian.protocol.MovementDirection.Forward;
                    case -3 -> meridian.protocol.MovementDirection.Back;
                    case -1 -> meridian.protocol.MovementDirection.Left;
                    case 1 -> meridian.protocol.MovementDirection.Right;
                    case 2 -> meridian.protocol.MovementDirection.ForwardLeft;
                    case 4 -> meridian.protocol.MovementDirection.ForwardRight;
                    case -4 -> meridian.protocol.MovementDirection.BackLeft;
                    case -2 -> meridian.protocol.MovementDirection.BackRight;
                    default -> meridian.protocol.MovementDirection.None;
                };
            }
        };
    }

    @Override
    public boolean available() {
        return client.get() != null && body.placed() && around.settings() != null;
    }

    @Override
    public Capabilities capabilities() {
        return reach.capabilities();
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
    public Cell cell(BlockPos cell) {
        Terrain.Placed b = terrain.blockAt(cell.x(), cell.y(), cell.z());
        boolean solid = b != null && b.type().material == BlockMaterial.Solid;
        boolean climbable = b != null && b.type().movementSettings != null && b.type().movementSettings.isClimbable;
        FluidCell f = terrain.fluidAt(cell.x(), cell.y(), cell.z());
        boolean swim = f != null && around.fluid(f.id()) != null;
        Door door = terrain.door(b);
        BlockPos at = door == Door.NONE ? null : new BlockPos(b.x(), b.y(), b.z());
        return new Cell(solid, climbable, f == null ? null : f.name(), swim, terrain.burns(cell.x(), cell.y(), cell.z()),
                door, at);
    }

    @Override
    public Vec3 position() {
        BodyState s = body.snapshot();
        return s != null ? new Vec3(s.x, s.y, s.z) : trackedFeet.get();
    }

    @Override
    public boolean onGround() {
        BodyState s = body.snapshot();
        return s != null && s.onGround;
    }

    @Override
    public State state() {
        BodyState s = body.snapshot();
        if (s == null) {
            Vec3 at = trackedFeet.get();
            return new State(at, new Vec3(0, 0, 0), 0, 0, false, false, false, false, false, false, false, false, false,
                    false, false);
        }
        return new State(new Vec3(s.x, s.y, s.z), new Vec3(s.totalX(), s.totalY(), s.totalZ()), s.yaw, s.pitch,
                s.onGround, s.inFluid, s.swimming, s.climbing, s.flying, s.crouching, s.sprinting, s.sliding, s.rolling,
                s.mantling, s.ledge != null && s.pullTime < 0);
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

    @Override
    public void hold(Controls controls) {
        body.hold(controls);
    }

    @Override
    public Prediction predict(Move move) {
        if (move == null || move.to() == null || move.kind() == null) {
            return new Prediction(new MoveResult(MoveResult.Status.BLOCKED, position()), 0, false, 0);
        }
        return predict(java.util.List.of(move), null);
    }

    @Override
    public Prediction predict(java.util.List<Move> moves) {
        return predict(moves, null);
    }

    /** A prediction with its ticks written out, for looking into a move. */
    public Prediction predict(java.util.List<Move> moves, java.util.List<String> trace) {
        java.util.List<Move> ms = moves.stream()
                .map(m -> m.gait() == null ? new Move(m.kind(), m.to(), Gait.RUN) : m).toList();
        return body.predict(ms, trace);
    }
}
