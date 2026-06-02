package meridian.core.impl;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import meridian.api.module.Scheduler;
import meridian.api.session.ProxySession;
import meridian.protocol.Direction;
import meridian.protocol.ModelTransform;
import meridian.protocol.Position;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.ClientTeleport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forges the player's position. The building block behind
 * {@link PlayerImpl#teleport}, {@link PlayerImpl#holdPosition} and
 * {@link PlayerImpl#clearHold}.
 *
 * <h2>teleport — a real teleport the player sees ({@link #teleport})</h2>
 *
 * <p>Hytale movement is client-authoritative: the client renders its own
 * position and reports it each tick. Two things have to happen for a teleport:
 *
 * <ol>
 *   <li><b>Move the client's view.</b> We send a {@code ClientTeleport} to the
 *       client — the exact packet the server sends for {@code /tp}. This is
 *       proxy→client, never validated by the server ({@code PendingTeleport}
 *       only validates the {@code teleportAck} when the <i>server</i> initiated
 *       the teleport, which it didn't here).</li>
 *   <li><b>Hold the server at the target through the transition.</b> A
 *       server-initiated teleport makes the client reply with a bare
 *       {@code teleportAck} (no fresh absolute), and the client keeps streaming
 *       its <i>old</i> position for the few packets until it processes the
 *       teleport. A one-shot write to the server drowns in that stream and the
 *       server snaps the client back. So we <b>pin</b>: every outgoing
 *       {@code absolutePosition} is overwritten with the target until the client
 *       itself starts reporting the target (it caught up) or a short deadline
 *       passes. The server applies any finite absolute with no distance check
 *       ({@code ValidateUtil.isSafePosition} = finite-only), so it stays put;
 *       once the pin releases the player walks normally from the target.</li>
 * </ol>
 *
 * <p>We never forge the {@code teleportAck} upstream — that path is validated.
 *
 * <h2>holdPosition — server-side offset ({@link #holdPosition})</h2>
 *
 * <p>A continuous shift of the player's <i>reported</i> position via the outgoing
 * stream — the server (and other players) see the player at the offset while the
 * player's own client stays put. A distinct, advanced primitive; for an ordinary
 * teleport use {@link #teleport}.
 *
 * <p>{@link #rewrite} runs on the Netty event loop ({@link ClientObserver});
 * the setters run on the EDT (settings callbacks).
 */
final class MovementControlImpl {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** How long the pin holds the server at the target through the transition. */
    private static final long PIN_NANOS = 1_200_000_000L;
    /** Number of direct C2S absolute injects fired after a teleport, and their spacing. */
    private static final int INJECT_COUNT = 5;
    private static final Duration INJECT_SPACING = Duration.ofMillis(40);
    /**
     * How many {@code ClientTeleport}s to send, one per spacing. The client's own
     * move cycle overrides a single teleport after one tick (a client-side cheat
     * found "1 tick doesn't work, 5 ticks does" — FishPlusPlus' {@code DoMoveCycle}
     * hook does {@code SetPositionTeleport} for 5 ticks). The proxy can't write the
     * client's position, so we resend the teleport for several ticks instead.
     */
    private static final int TELEPORT_REPEATS = 8;
    private static final Duration TELEPORT_SPACING = Duration.ofMillis(20);

    private final Scheduler scheduler;
    /** Main-channel session, captured by {@link ClientObserver} from C2S movement. */
    private volatile ProxySession session;

    MovementControlImpl(Scheduler scheduler) {
        this.scheduler = scheduler;
    }
    /** Rolling teleport id for the forged {@code ClientTeleport}. */
    private final AtomicInteger teleportId = new AtomicInteger();
    /** Last orientations the client reported — reused so a teleport keeps facing. */
    private volatile Direction lastBody;
    private volatile Direction lastLook;

    /** Active teleport pin target (x,y,z), or {@code null} when not teleporting. */
    private volatile double[] pinTarget;
    /** {@link System#nanoTime} after which the pin force-releases. */
    private volatile long pinDeadlineNanos;

    private volatile boolean holdEnabled;
    /** Target captured when hold was switched on (x,y,z). */
    private volatile double[] holdTarget;
    /** Constant shift added to every outgoing absolute; armed lazily in {@link #rewrite}. */
    private volatile double[] holdOffset;

    /** Captured from observed C2S movement; until one arrives teleport is a no-op. */
    void bind(ProxySession session) {
        this.session = session;
    }

    // ------------------------------------------------------------------
    // API surface (called from PlayerImpl, EDT)
    // ------------------------------------------------------------------

    void teleport(double x, double y, double z) {
        ProxySession s = session;
        if (s == null) {
            log.warn("meridian-core: teleport requested but no client session yet — ignored");
            return;
        }
        // Move the player's own view with ClientTeleport — but repeated over
        // several ticks: a single one is overridden by the client's own move
        // cycle a tick later (the "1 tick doesn't work, 5 do" finding).
        for (int i = 0; i < TELEPORT_REPEATS; i++) {
            scheduler.schedule(() -> {
                ProxySession cur = session;
                if (cur != null) {
                    sendClientTeleport(cur, x, y, z);
                }
            }, TELEPORT_SPACING.multipliedBy(i));
        }

        // Arm the pin: every outgoing absolute is forced to the target for the
        // whole window (NOT released on the first matching packet — the roll-back
        // happens a few ticks later, so we must hold the server there).
        pinDeadlineNanos = System.nanoTime() + PIN_NANOS;
        pinTarget = new double[]{x, y, z};

        // Also push the absolute straight to the server several times across a
        // few ticks. Covers the gap when the client streams few packets, and
        // keeps the server's authoritative position at the target.
        for (int i = 0; i < INJECT_COUNT; i++) {
            scheduler.schedule(() -> {
                ProxySession cur = session;
                if (cur != null) {
                    cur.sendToServer(buildAbsolute(x, y, z));
                }
            }, INJECT_SPACING.multipliedBy(i));
        }

        log.info("meridian-core: teleport -> ({}, {}, {}) [{}x ClientTeleport, pin {}ms, {}x inject]",
                x, y, z, TELEPORT_REPEATS, PIN_NANOS / 1_000_000L, INJECT_COUNT);
    }

    /** Sends one {@code ClientTeleport} to the client at the target, keeping facing. */
    private void sendClientTeleport(ProxySession s, double x, double y, double z) {
        byte id = (byte) teleportId.incrementAndGet();
        ModelTransform transform = new ModelTransform(
                new Position(x, y, z),
                lastBody != null ? lastBody.clone() : null,
                lastLook != null ? lastLook.clone() : null);
        s.sendToClient(new ClientTeleport(id, transform, true));
    }

    /** A forged C2S {@code ClientMovement} carrying just the absolute (+ cached facing). */
    private ClientMovement buildAbsolute(double x, double y, double z) {
        ClientMovement mv = new ClientMovement();
        mv.absolutePosition = new Position(x, y, z);
        mv.bodyOrientation = lastBody != null ? lastBody.clone() : null;
        mv.lookOrientation = lastLook != null ? lastLook.clone() : null;
        mv.velocity = null;
        mv.wishMovement = null;
        mv.mountedTo = 0;
        return mv;
    }

    void holdPosition(double x, double y, double z) {
        holdTarget = new double[]{x, y, z};
        holdOffset = null;        // armed on the next observed absolute
        holdEnabled = true;
        log.info("meridian-core: movement hold ON -> ({}, {}, {})", x, y, z);
    }

    void clearHold() {
        holdEnabled = false;
        holdOffset = null;
        log.info("meridian-core: movement hold OFF");
    }

    // ------------------------------------------------------------------
    // Rewrite (called from ClientObserver, Netty event loop)
    // ------------------------------------------------------------------

    /**
     * Caches orientation, applies the teleport pin, then the hold offset.
     *
     * @return {@code true} if the packet was changed (caller returns
     *         {@code MODIFIED}), {@code false} to forward it untouched.
     */
    boolean rewrite(ClientMovement m) {
        if (m.bodyOrientation != null) {
            lastBody = m.bodyOrientation;
        }
        if (m.lookOrientation != null) {
            lastLook = m.lookOrientation;
        }

        // Teleport pin: force the target onto every outgoing absolute for the
        // whole window, so stale / reverting client packets can't move the
        // server off the target before the client has settled there.
        //
        // This runs BEFORE the teleport-ack guard on purpose: while we are
        // teleporting, the client replies to each of our ClientTeleports with a
        // teleport-ack packet. The server has no pending teleport of its own
        // (ours are proxy→client only), so rewriting those packets — and
        // clearing the ack — is safe (no PendingTeleport.validate path) and is
        // exactly what keeps the server pinned during the ack storm. Skipping
        // them was the bug: every packet in the window carries an ack, so the
        // pin never applied and the server followed the client back.
        double[] pin = pinTarget;
        if (pin != null) {
            // Diagnostic: what is the client actually reporting while we hold?
            if (m.absolutePosition != null) {
                log.info("meridian-core: teleport pin — client reports ({}, {}, {}), target ({}, {}, {}), ack={}",
                        m.absolutePosition.x, m.absolutePosition.y, m.absolutePosition.z,
                        pin[0], pin[1], pin[2], m.teleportAck != null);
            } else {
                log.info("meridian-core: teleport pin — client packet has no absolute "
                        + "(relative={}, teleportAck={})",
                        m.relativePosition != null, m.teleportAck != null);
            }
            if (System.nanoTime() >= pinDeadlineNanos) {
                pinTarget = null;
                log.info("meridian-core: teleport pin released (window elapsed)");
                // fall through — forward this packet (and let hold apply if set)
            } else {
                m.absolutePosition = new Position(pin[0], pin[1], pin[2]);
                m.teleportAck = null;
                m.relativePosition = null;
                m.velocity = null;
                m.wishMovement = null;
                return true;
            }
        }

        // Never rewrite a teleport-ack outside an active pin — the server
        // validates that path against its own pending teleport.
        if (m.teleportAck != null) {
            return false;
        }

        // Hold offset — only on a packet that carries an absolute. Relative-only
        // packets keep the offset implicitly (the server integrates them from
        // the already-shifted position).
        if (holdEnabled && m.absolutePosition != null) {
            double[] off = holdOffset;
            if (off == null) {
                double[] t = holdTarget;
                if (t == null) {
                    return false;
                }
                off = new double[]{
                        t[0] - m.absolutePosition.x,
                        t[1] - m.absolutePosition.y,
                        t[2] - m.absolutePosition.z};
                holdOffset = off;
                log.info("meridian-core: movement hold armed, offset ({}, {}, {})",
                        off[0], off[1], off[2]);
            }
            m.absolutePosition.x += off[0];
            m.absolutePosition.y += off[1];
            m.absolutePosition.z += off[2];
            return true;
        }

        return false;
    }
}
