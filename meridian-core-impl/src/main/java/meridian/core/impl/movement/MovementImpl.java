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
import meridian.protocol.packets.entities.ChangeVelocity;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.ClientTeleport;
import meridian.protocol.packets.player.JoinWorld;
import meridian.protocol.packets.player.SetMovementStates;
import meridian.protocol.packets.player.UpdateMovementSettings;
import meridian.protocol.packets.setup.UpdateFeatures;
import meridian.protocol.packets.world.ServerSetBlock;
import meridian.protocol.packets.world.ServerSetBlocks;

/**
 * {@link Movement}: what the ground allows ({@link Terrain}), the body's physics ({@link Physics})
 * and the body that lives by it ({@link Body}) - a headless session's, and a game's while core holds
 * it ({@link GameWheel}). Hears everything the client
 * moves by: the server's block shapes, movement settings, the player's model, how fluids move a
 * body (the fluids themselves are core's one catalog), effects, features, pushes and teleports;
 * and on the client side, positions the body did not send itself.
 */
public final class MovementImpl implements Movement {

    private final Terrain terrain;
    private final BlockHitboxes hitboxes;
    private final Surroundings around;
    private final Physics physics;
    private final BodySettings reach;
    private final Body body;
    private final GameWheel game;
    private final Supplier<HeadlessClient> client;
    private final Supplier<Vec3> trackedFeet;
    private final Supplier<OptionalInt> localId;
    private final IntFunction<Set<Integer>> effectsOf;

    private final ClientTeleports teleports;
    /** The urgent holds ({@link #takeFirst}), for what core holds back while one is held. */
    private final Urgency urgency = new Urgency();
    private final LookReports lookReports = new LookReports();

    public MovementImpl(Terrain terrain, BlockHitboxes hitboxes, meridian.core.impl.FluidCatalog fluids,
                        Scheduler scheduler, Supplier<HeadlessClient> client, Supplier<Vec3> trackedFeet,
                        Supplier<OptionalInt> localId, IntFunction<Set<Integer>> effectsOf,
                        ClientTeleports teleports) {
        this.teleports = teleports;
        this.around = new Surroundings(fluids);
        this.terrain = terrain;
        this.hitboxes = hitboxes;
        this.client = client;
        this.trackedFeet = trackedFeet;
        this.localId = localId;
        this.effectsOf = effectsOf;
        // The ground rules stand the player's body as its model has it, a game's player too.
        terrain.body(around::shape);
        this.physics = new Physics(terrain, around);
        this.reach = new BodySettings(physics);
        this.body = new Body(scheduler, physics, this::driver, this::effects);
        this.game = new GameWheel(body, teleports);
        this.game.reported(m -> lookReports.reported(m.lookOrientation));
    }

    /** Who moves the body now: a headless client, a game's body core holds, or nobody. */
    private Body.Driver driver() {
        HeadlessClient c = client.get();
        if (c != null) {
            return c::sendAsClient;
        }
        return game.driver();
    }

    /** A headless body is core's all along: its wheel is never given back. */
    private final Movement.Wheel headlessWheel = new Movement.Wheel() {
        private final CompletableFuture<Movement.Release> never = new CompletableFuture<>();

        @Override
        public boolean held() {
            return client.get() != null;
        }

        @Override
        public void release() {
        }

        @Override
        public CompletableFuture<Movement.Release> released() {
            return never;
        }
    };

    /**
     * EARLY, client side: a game's movement - read while the game has its body, held back while core
     * does ({@link GameWheel}).
     */
    public PacketHandler gameGate() {
        return game.gate();
    }

    /** What core's own observers of the client's movement make of the reports of a game's body core moves. */
    public void gameObservers(java.util.function.Consumer<ClientMovement> observers) {
        game.observers(observers);
    }

    /** Core's teleport of a game's player while core holds the body: the body goes there. */
    public boolean teleportHeld(double x, double y, double z) {
        return game.teleport(x, y, z);
    }

    /** The player died: a game's body core holds goes back to the game. */
    public void died() {
        game.lost(Movement.Release.DEATH);
    }

    private Surroundings.EffectMotion effects() {
        OptionalInt id = localId.get();
        if (id.isEmpty()) {
            return null;
        }
        Set<Integer> active = effectsOf.apply(id.getAsInt());
        return active == null || active.isEmpty() ? null : around.effects(active);
    }

    /**
     * The movement settings the server last sent, as it sent them, or {@code null} before it has:
     * read at EARLY, so a rewrite on the way to the client (a spoofed flight) is not in them.
     */
    public meridian.protocol.MovementSettings serverSettings() {
        return around.settings();
    }

    /** The server's side: block shapes, settings, the model, fluid FX, effects, features, pushes, teleports. */
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
                    // The server's (core's own pass no handler): a game's body core holds goes back to
                    // the game, which answers it.
                    if (session.headless() == null) {
                        teleports.server(teleport.teleportId);
                    }
                    lookReports.reset();
                    game.lost(Movement.Release.TELEPORT);
                    body.teleported(teleport.resetVelocity);
                } else if (packet instanceof JoinWorld) {
                    lookReports.reset();
                    game.lost(Movement.Release.WORLD);
                } else if (packet instanceof ChangeVelocity push) {
                    body.push(push.x, push.y, push.z, push.changeType, push.config);
                } else if (packet instanceof UpdateFluidFX fx) {
                    around.fluidFx(fx);
                } else if (packet instanceof UpdateEntityEffects effects) {
                    around.effects(effects);
                } else if (packet instanceof UpdateFeatures features) {
                    around.features(features.features);
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
                if (packet instanceof ClientMovement m) {
                    if (client.get() != null) body.sentByOther(m);
                    lookReports.reported(m.lookOrientation);
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
                if (driver() != null) {
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
        if (around.settings() == null) {
            return false;
        }
        return client.get() != null ? body.placed() : game.canTake();
    }

    @Override
    public boolean driving() {
        return driver() != null && body.placed() && around.settings() != null;
    }

    @Override
    public java.util.Optional<Movement.Wheel> take() {
        return hold().map(inner -> {
            Counted c = new Counted(inner, urgency.stamp());
            counted.add(c);
            if (inner != headlessWheel) {
                // The game took the body (a key, a teleport, a death, a world): this hold is gone.
                inner.released().thenRun(() -> counted.remove(c));
            }
            return c;
        });
    }

    /** A hold on the body, uncounted: a headless session's, or one of the game's body. */
    private java.util.Optional<Movement.Wheel> hold() {
        if (around.settings() == null) {
            return java.util.Optional.empty();
        }
        if (client.get() != null) {
            return body.placed() ? java.util.Optional.of(headlessWheel) : java.util.Optional.empty();
        }
        return game.take();
    }

    /** The ordinary holds not given back yet ({@link #holds}). */
    private final Set<Counted> counted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public int holds() {
        int n = 0;
        for (Counted c : counted) {
            // Taken while an urgent hold is held: the urgent taker's own work, not what it holds back.
            if (c.inner.held() && (!urgency.active() || urgency.holdsBack(c.born))) {
                n++;
            }
        }
        return n;
    }

    /**
     * An ordinary hold, counted until its taker gives it back or the game takes the body. It holds
     * as the hold under it does - a headless session's is held until the session ends.
     */
    private final class Counted implements Movement.Wheel {
        final Movement.Wheel inner;
        /** When it was taken, by core's urgency clock. */
        final long born;

        Counted(Movement.Wheel inner, long born) {
            this.inner = inner;
            this.born = born;
        }

        @Override
        public boolean held() {
            return inner.held();
        }

        @Override
        public void release() {
            counted.remove(this);
            inner.release();
        }

        @Override
        public CompletableFuture<Movement.Release> released() {
            return inner.released();
        }
    }

    /**
     * The server's entity effect at this index ({@code UpdateEntityEffects}) - core's one catalog
     * of them, kept for what effects do to the body - or {@code null}.
     */
    public meridian.protocol.EntityEffect effect(int index) {
        return around.effect(index);
    }

    @Override
    public java.util.Optional<Movement.Wheel> takeFirst() {
        java.util.Optional<Movement.Wheel> taken = hold();
        if (taken.isEmpty()) {
            return taken;
        }
        Movement.Wheel inner = taken.get();
        urgency.begin();
        // What the other holds had going stops where it is: their trips and digs wait.
        body.stop();
        UrgentWheel urgent = new UrgentWheel(inner);
        inner.released().thenAccept(urgent::lost);
        return java.util.Optional.of(urgent);
    }

    /** Core's urgent holds: what the navigator, the worker and the swing loops wait on while one is held. */
    public Urgency urgency() {
        return urgency;
    }

    /** An urgent hold: an ordinary one underneath, and the urgency it keeps up until it is gone. */
    private final class UrgentWheel implements Movement.Wheel {
        private final Movement.Wheel inner;
        private final CompletableFuture<Movement.Release> own = new CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicBoolean over = new java.util.concurrent.atomic.AtomicBoolean();

        UrgentWheel(Movement.Wheel inner) {
            this.inner = inner;
        }

        @Override
        public boolean held() {
            return !over.get() && inner.held();
        }

        @Override
        public void release() {
            if (over.compareAndSet(false, true)) {
                inner.release();
                urgency.end();
                own.complete(Movement.Release.GIVEN_BACK);
            }
        }

        /** The game took the body: the urgent hold is over with it. */
        void lost(Movement.Release why) {
            if (over.compareAndSet(false, true)) {
                urgency.end();
                own.complete(why);
            }
        }

        @Override
        public CompletableFuture<Movement.Release> released() {
            return own;
        }
    }

    @Override
    public boolean flightOn() {
        return flightOn.getAsBoolean();
    }

    /** Where flight being switched on is read from - client-control's Fly, through core's ClientSpoof. */
    public void flightOn(java.util.function.BooleanSupplier on) {
        this.flightOn = on == null ? () -> true : on;
        physics.flightOn = this.flightOn;
    }

    private volatile java.util.function.BooleanSupplier flightOn = () -> true;

    @Override
    public Capabilities capabilities() {
        return reach.capabilities();
    }

    /** How a sprinting jump flies after a run-up, for planning leaps; {@code null} before the settings. */
    public RunUps runUps() {
        return reach.runUps();
    }

    /**
     * The player's eyes above its feet, standing: its model's, scaled, once the server has sent it -
     * for a game's player too, whose body this does not move - and the default player model's until
     * then. Core's one source for where the eyes are.
     */
    public double eyeHeight() {
        return around.shape().eyeHeight();
    }

    @Override
    @Deprecated
    public OptionalDouble standAt(BlockPos cell) {
        return terrain.standAt(cell.x(), cell.y(), cell.z());
    }

    @Override
    @Deprecated
    public boolean fits(Vec3 feet, double height) {
        return terrain.fits(feet.x(), feet.y(), feet.z(), height);
    }

    @Override
    @Deprecated
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
                s.mantling, false);
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
    public CompletableFuture<Boolean> look(meridian.core.api.Look look) {
        return look(look, FACE_WITHIN);
    }

    @Override
    public CompletableFuture<Boolean> look(meridian.core.api.Look look, double within) {
        if (look == null || !Float.isFinite(look.yaw()) || !Float.isFinite(look.pitch()) || !Double.isFinite(within) || within < 0) {
            return CompletableFuture.completedFuture(false);
        }
        return body.look(look.yaw(), look.pitch(), within).thenCompose(turned -> {
            if (!turned) return CompletableFuture.completedFuture(false);
            BodyState s = body.snapshot();
            return s == null ? CompletableFuture.completedFuture(false) : lookReports.await(s.yaw, s.pitch);
        });
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
