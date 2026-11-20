package meridian.core.impl.movement;

import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.Movement;
import meridian.protocol.Direction;
import meridian.protocol.ModelTransform;
import meridian.protocol.MovementStates;
import meridian.protocol.Position;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.ClientTeleport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A game's body, taken by core ({@link Movement#take}): while core holds it, the game's movement
 * reports stop at the proxy, the body's go to the server in their place, and the game is put where
 * the body is every tick - a {@code ClientTeleport}, the packet the server moves a player with,
 * looking where the body looks - so the player sees themselves go.
 *
 * <p>The server takes a player's movement as sent ({@code ClientMovement} is checked for finite
 * numbers only), so the body's reports are the player's. The game acknowledges every teleport it is
 * shown, and the server ends the connection on an acknowledgement of a teleport it did not send:
 * those of core's stop here ({@link ClientTeleports}), after the body is given back as well. While
 * the server waits for the game to acknowledge a teleport of its own it takes no movement, so the
 * body is not taken then.
 *
 * <p>The body goes back to the game on a module's release, and by itself on what a player would
 * want it back for, or what the server does with the player: a key that moves them, a teleport of
 * the server's, a death, another world, the end of the session.
 */
final class GameWheel {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** Shows the game the body this often at least while it stands still, in ticks. */
    private static final int SHOW_EVERY = 10;

    private final Body body;
    private final ClientTeleports teleports;

    /** The game's Default stream, where it reports its movement. */
    private volatile ProxySession session;

    // The game's pose as it last reported it.
    private volatile Position at;
    private volatile Direction look;
    private volatile Direction facing;
    private volatile boolean onGround;
    private volatile boolean flying;
    private volatile int mountedTo;

    /** What core's own C2S observers make of a report the body sends (the tracker, the forges, the spoof). */
    private volatile Consumer<ClientMovement> observers = m -> { };

    /** Held while core has the body; {@code null} while the game has. */
    private volatile Held wheel;

    // What the game was last shown: the body's tick thread only.
    private double[] shown;
    private int sinceShown;

    private final Body.Driver driver = new Body.Driver() {
        @Override
        public void report(ClientMovement m) {
            ProxySession s = session;
            if (s == null) {
                return;
            }
            observers.accept(m);
            s.sendToServer(m);
            reported.accept(m);
        }

        @Override
        public void ticked(BodyState s) {
            show(s);
        }
    };

    GameWheel(Body body, ClientTeleports teleports) {
        this.body = body;
        this.teleports = teleports;
    }

    private Consumer<ClientMovement> reported = m -> { };

    /** The report has been passed to the server connection. */
    void reported(Consumer<ClientMovement> callback) { reported = callback; }

    /** What core's own observers of the client's movement make of the body's reports. */
    void observers(Consumer<ClientMovement> observers) {
        this.observers = observers == null ? m -> { } : observers;
    }

    /**
     * Whether the game's body can be taken: it has said where it is, rides nothing, and the server
     * waits for no acknowledgement of a teleport (it takes no movement until then).
     */
    boolean canTake() {
        return session != null && at != null && mountedTo == 0 && !teleports.serverPending();
    }

    boolean held() {
        return wheel != null;
    }

    /** The body's driver while core holds it, else {@code null}. */
    Body.Driver driver() {
        return wheel == null ? null : driver;
    }

    Optional<Movement.Wheel> take() {
        Held w;
        Position p;
        synchronized (this) {
            w = wheel;
            if (w != null) {
                return Optional.of(w.hold());
            }
            p = at;
            if (p == null || !canTake()) {
                return Optional.empty();
            }
            w = new Held();
            Direction l = look;
            Direction f = facing;
            shown = null;
            sinceShown = 0;
            wheel = w;
            body.takeOver(p.x, p.y, p.z, l == null ? 0f : l.yaw, l == null ? 0f : l.pitch,
                    f != null ? f.yaw : l != null ? l.yaw : 0f, onGround, flying);
        }
        log.info("meridian-core: took the player's body at ({}, {}, {})", p.x, p.y, p.z);
        synchronized (this) {
            // Lost again before the first hold was made: nothing to hold.
            return wheel == w ? Optional.of(w.hold()) : Optional.empty();
        }
    }

    /** The game has the body back, for this reason; nothing when it has it already. */
    void lost(Movement.Release why) {
        end(null, why);
    }

    /** Ends {@code expected}'s hold ({@code null}: whichever is held). */
    private void end(Held expected, Movement.Release why) {
        Held w;
        synchronized (this) {
            w = wheel;
            if (w == null || (expected != null && w != expected)) {
                return;
            }
            wheel = null;
        }
        body.letGo();
        log.info("meridian-core: the player's body is the game's again ({})", why);
        w.done.complete(why);
        List<Hold> holds;
        synchronized (this) {
            holds = List.copyOf(w.holds);
            w.holds.clear();
        }
        for (Hold h : holds) {
            h.own.complete(why);
        }
    }

    /** Core's teleport while it holds the body: the body goes there. */
    boolean teleport(double x, double y, double z) {
        if (wheel == null) {
            return false;
        }
        body.placeAt(x, y, z);
        return true;
    }

    /** The game is shown the body: where it is and where it looks, every tick it changed. */
    private void show(BodyState s) {
        ProxySession g = session;
        if (g == null || wheel == null) {
            return;
        }
        double[] now = {s.x, s.y, s.z, s.yaw, s.pitch, s.bodyYaw};
        if (shown != null && java.util.Arrays.equals(shown, now) && ++sinceShown < SHOW_EVERY) {
            return;
        }
        shown = now;
        sinceShown = 0;
        ModelTransform t = new ModelTransform(new Position(s.x, s.y, s.z), new Direction(s.bodyYaw, 0f, 0f),
                new Direction(s.yaw, s.pitch, 0f));
        // 0.5.9: no field masks - the body's tilt and the rolls go as the zeros they are.
        g.sendToClient(new ClientTeleport(teleports.ours(), t, true));
    }

    /** The game's pose, from a report of its own. */
    private void pose(ClientMovement m) {
        if (m.absolutePosition != null) {
            at = m.absolutePosition;
        }
        if (m.lookOrientation != null) {
            look = m.lookOrientation;
        }
        if (m.bodyOrientation != null) {
            facing = m.bodyOrientation;
        }
        MovementStates st = m.movementStates;
        if (st != null) {
            onGround = st.onGround;
            flying = st.flying;
        }
        mountedTo = m.mountedTo;
    }

    /**
     * Keys that move the player: a walk in any direction, a jump. {@code horizontalIdle} is the walk
     * keys' alone; {@code idle} goes off in the air as well - falling, flying, on a ladder - with no
     * key at all (the recording of 08.11.2026), and a game shown the body a little over the ground
     * falls for a tick.
     */
    private static boolean keys(ClientMovement m) {
        MovementStates st = m.movementStates;
        return st != null && (!st.horizontalIdle || st.jumping);
    }

    /**
     * EARLY, client side: the game's movement - its pose read while the game has the body, held
     * back while core does, and the body handed back on a key that moves the player.
     */
    PacketHandler gate() {
        return new PacketHandler() {
            @Override
            public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession s) {
                if (!(packet instanceof ClientMovement m) || s.headless() != null) {
                    return Action.FORWARD;
                }
                session = s;
                // An acknowledgement of core's own teleport ends at the proxy: the server did not send it.
                boolean stripped = m.teleportAck != null && teleports.ackedOurs(m.teleportAck.teleportId);
                if (stripped) {
                    m.teleportAck = null;
                }
                Action pass = stripped ? Action.MODIFIED : Action.FORWARD;
                if (wheel == null) {
                    pose(m);
                    return pass;
                }
                if (keys(m)) {
                    // The player steers: the body is theirs from this report on, which starts where
                    // they were shown last - where the body is.
                    log.info("meridian-core: the player pressed a key (walk: {}, jump: {})",
                            !m.movementStates.horizontalIdle, m.movementStates.jumping);
                    lost(Movement.Release.PLAYER);
                    pose(m);
                    return pass;
                }
                return Action.DROP;
            }

            @Override
            public void onStreamInactive(ChannelHandlerContext ctx, ProxySession s) {
                if (s == session) {
                    lost(Movement.Release.GONE);
                    session = null;
                    at = null;
                }
            }
        };
    }

    /**
     * Core's hold on the body, from the first take to the game getting it back - shared by every
     * {@link Hold} on it.
     */
    private final class Held {
        final CompletableFuture<Movement.Release> done = new CompletableFuture<>();
        /** Who holds it now; the body goes back when the last lets go. Guarded by the wheel. */
        final List<Hold> holds = new ArrayList<>();

        /** A new hold on it, for one more taker. Called with the wheel's monitor held. */
        Hold hold() {
            Hold h = new Hold(this);
            holds.add(h);
            return h;
        }
    }

    /**
     * One taker's hold: its own to give back. The body is the game's again once every hold is
     * given back, or at once when the game takes it (a key, a teleport, a death, a world).
     */
    private final class Hold implements Movement.Wheel {
        final Held of;
        final CompletableFuture<Movement.Release> own = new CompletableFuture<>();

        Hold(Held of) {
            this.of = of;
        }

        @Override
        public boolean held() {
            synchronized (GameWheel.this) {
                return wheel == of && of.holds.contains(this);
            }
        }

        @Override
        public void release() {
            boolean last;
            synchronized (GameWheel.this) {
                if (wheel != of || !of.holds.remove(this)) {
                    return;
                }
                last = of.holds.isEmpty();
            }
            if (last) {
                end(of, Movement.Release.GIVEN_BACK);
            }
            own.complete(Movement.Release.GIVEN_BACK);
        }

        @Override
        public CompletableFuture<Movement.Release> released() {
            return own;
        }
    }
}
