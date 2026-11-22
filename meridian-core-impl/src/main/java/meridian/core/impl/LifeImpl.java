package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import meridian.api.module.Scheduler;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.BlockBox;
import meridian.core.api.BlockPos;
import meridian.core.api.Goal;
import meridian.core.api.Life;
import meridian.core.api.NavResult;
import meridian.core.api.Vec3;
import meridian.core.api.Worker;
import meridian.core.impl.movement.MovementImpl;
import meridian.protocol.packets.connection.ClientDisconnect;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.interface_.CustomPageEvent;
import meridian.protocol.packets.interface_.CustomPageEventType;
import meridian.protocol.packets.interface_.CustomUIEventBinding;
import meridian.protocol.packets.interface_.CustomUIEventBindingType;
import meridian.protocol.packets.interface_.Page;
import meridian.protocol.packets.interface_.SetPage;
import meridian.protocol.packets.player.JoinWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Life}: the player's death read off the server's death window ({@code RespawnPage}), their
 * revival off its going ({@code RespawnPage.closePage}: a {@code SetPage} to none, once
 * {@code DeathComponent.respawn} is through).
 *
 * <p>A headless session has no player to press the button: core presses it - sends what the window
 * itself binds to its Respawn button ({@code RespawnPage.build}: {@code Action = Confirm} in the games
 * of late, {@code Action = Respawn} in 0.5.9; under permadeath the window has a Spectate button
 * instead, and nothing is pressed) - and, back alive, goes for what was dropped.
 */
final class LifeImpl implements Life {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** The death window's Respawn button ({@code RespawnPage.ELEMENT_BUTTON_RESPAWN}). */
    static final String RESPAWN_BUTTON = "#RespawnButton";
    /** Around where the player died, this far each way, is looked through for what they dropped. */
    static final int DROPS_AROUND = 4;

    private final MovementImpl movement;
    private final SessionHolder sessions;
    private final Scheduler scheduler;
    private final Supplier<Worker> worker;
    /**
     * How many items the player has gained in all ({@code PlayerStateImpl.gained}): what a going back
     * brought is counted by it - not by what is carried then and now: the death's own loss may be told
     * after the body has set out, and what is eaten on the way is no item less picked up.
     */
    private final java.util.function.LongSupplier gained;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean dead;
    private volatile Death last;
    private volatile UUID world;
    private volatile CompletableFuture<Boolean> reviving;
    /** What the death window up now binds to its Respawn button; {@code null} when it has none. */
    private volatile String respawnData;
    /** On the way back for what was dropped: until it is over no urgent hold takes the body. */
    private volatile boolean recovering;
    /** What the last going back came to; {@code null} before the first, and while one is under way. */
    private volatile Recovered lastRecovered;
    /** A headless session goes back for its things by itself ({@code core life goback on|off}). */
    private volatile boolean goBack = true;
    /**
     * A headless session leaves the server once a death is over - the things gone back for, or not got
     * to ({@code core life logout on|off}). The owner's word (21.11): what the body did before cannot
     * simply be gone on with - what it picked up again lies in other slots than it did, and nobody is
     * there to put it back - so it does not stand about in the world to be killed again.
     */
    private volatile boolean logOut = true;
    /**
     * The last death came on the way back for the things of the one before: whatever killed the player
     * there, or on the way, would again - they are left where they lie, and nothing is gone back for.
     */
    private volatile boolean diedGoingBack;

    LifeImpl(MovementImpl movement, SessionHolder sessions, Scheduler scheduler, Supplier<Worker> worker,
             java.util.function.LongSupplier gained) {
        this.gained = gained;
        this.movement = movement;
        this.sessions = sessions;
        this.scheduler = scheduler;
        this.worker = worker;
    }

    void goBack(boolean on) {
        goBack = on;
    }

    void logOut(boolean on) {
        logOut = on;
    }

    boolean logsOut() {
        return logOut;
    }

    boolean goesBack() {
        return goBack;
    }

    Recovered lastRecovered() {
        return lastRecovered;
    }

    /** Watches the server's side for the death window and for its going. */
    PacketHandler handler() {
        return new PacketHandler() {
            @Override
            public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
                if (packet instanceof JoinWorld join) {
                    world = join.worldUuid;
                } else if (packet instanceof CustomPage page && ClientSpoofImpl.RESPAWN_PAGE_KEY.equals(page.key)) {
                    // The death window, opened: its first packet (`isInitial`; its `clear` flag is "replace
                    // what the page showed", not "the page is gone"). Later packets only update it - one
                    // comes after the respawn itself, the button greyed out.
                    if (page.isInitial && !dead) {
                        respawnData = respawnButton(page);
                        died(session);
                    }
                } else if (packet instanceof SetPage set && set.page == Page.None && dead) {
                    revived(session);       // the death window closed: RespawnPage.closePage, after the respawn
                }
                return Action.FORWARD;
            }
        };
    }

    /** What the window sends when its Respawn button is pressed; {@code null} when it has no such button. */
    private static String respawnButton(CustomPage page) {
        if (page.eventBindings != null) {
            for (CustomUIEventBinding b : page.eventBindings) {
                if (b != null && b.type == CustomUIEventBindingType.Activating && RESPAWN_BUTTON.equals(b.selector)) {
                    return b.data;
                }
            }
        }
        return null;
    }

    private void died(ProxySession session) {
        Vec3 at = movement.position();
        Death death = new Death(at, world, System.currentTimeMillis());
        diedGoingBack = recovering;
        last = death;
        lastRecovered = null;
        dead = true;
        log.info("meridian-core: the player died at {} - every hold on the body is over", at);
        movement.died();
        for (Listener l : listeners) {
            try {
                l.died(death);
            } catch (RuntimeException e) {
                log.warn("meridian-core: a life listener failed on a death", e);
            }
        }
        if (HeadlessClientHandler.headlessOf(session) != null) {
            // Nobody to press the button: pressed for them - after this packet's own acknowledgement
            // (the headless client's, sent in this same pass), which the server wants first.
            scheduler.schedule(this::respawn, java.time.Duration.ZERO);
        }
    }

    private void revived(ProxySession session) {
        if (!dead) {
            return;
        }
        dead = false;
        Death death = last;
        movement.revived();
        log.info("meridian-core: the player is alive again");
        CompletableFuture<Boolean> r = reviving;
        reviving = null;
        if (r != null) {
            r.complete(true);
        }
        for (Listener l : listeners) {
            try {
                l.revived(death);
            } catch (RuntimeException e) {
                log.warn("meridian-core: a life listener failed on a revival", e);
            }
        }
        if (HeadlessClientHandler.headlessOf(session) == null || death == null) {
            return;         // a game's player: theirs to go back, theirs to stay or leave
        }
        if (goBack && diedGoingBack) {
            log.info("meridian-core: died on the way back for the things - they are left where they lie");
            over(session, death, new Recovered(false, 0, "died going back for them"));
        } else if (goBack) {
            recover().thenAccept(what -> over(session, death, what));
        } else {
            over(session, death, new Recovered(false, 0, "going back is switched off"));
        }
    }

    /** A headless session's death is over: what going back came to is told, and the session leaves. */
    private void over(ProxySession session, Death death, Recovered what) {
        lastRecovered = what;
        for (Listener l : listeners) {
            try {
                l.recovered(death, what);
            } catch (RuntimeException e) {
                log.warn("meridian-core: a life listener failed on a recovery", e);
            }
        }
        if (logOut && !dead) {
            leave(session, "the death is over: " + (what.arrived() ? "back where it died" : "did not get back there")
                    + " (" + what.picked() + " item(s) back)" + (what.why().isEmpty() ? "" : ": " + what.why()));
        }
    }

    @Override
    public boolean leave(String why) {
        ProxySession live = sessions.get().orElse(null);
        if (live == null) {
            return false;
        }
        leave(live, why);
        return true;
    }

    /**
     * As a player quitting does: the game's own word that it is leaving (the server takes the player
     * out of the world at once - a connection merely dropped it keeps for a minute), then the
     * connection.
     */
    private void leave(ProxySession session, String why) {
        log.info("meridian-core: leaving the server - {}", why);
        session.sendToServer(new ClientDisconnect());
        session.disconnect();
    }

    @Override
    public boolean dead() {
        return dead;
    }

    @Override
    public boolean recovering() {
        return recovering;
    }

    @Override
    public Optional<Death> lastDeath() {
        return Optional.ofNullable(last);
    }

    @Override
    public CompletableFuture<Boolean> respawn() {
        ProxySession live = sessions.get().orElse(null);
        if (!dead || live == null) {
            return CompletableFuture.completedFuture(false);
        }
        String press = respawnData;
        if (press == null) {
            log.info("meridian-core: the death window has no Respawn button (permadeath?) - nothing to press");
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> r = reviving;
        if (r == null) {
            r = new CompletableFuture<>();
            reviving = r;
        }
        log.info("meridian-core: pressing Respawn on the death window");
        live.sendToServer(new CustomPageEvent(CustomPageEventType.Data, press));
        return r;
    }

    @Override
    public CompletableFuture<Recovered> recover() {
        Death death = last;
        Worker w = worker.get();
        if (death == null || death.at() == null) {
            return CompletableFuture.completedFuture(new Recovered(false, 0, "the player has not died"));
        }
        if (dead) {
            return CompletableFuture.completedFuture(new Recovered(false, 0, "the player is dead still"));
        }
        if (w == null) {
            return CompletableFuture.completedFuture(new Recovered(false, 0, "no worker to go with"));
        }
        if (death.world() != null && world != null && !death.world().equals(world)) {
            log.info("meridian-core: died in another world ({}) - what was dropped there is not gone back for", death.world());
            return CompletableFuture.completedFuture(new Recovered(false, 0, "the player died in another world"));
        }
        BlockPos cell = new BlockPos((int) Math.floor(death.at().x()), (int) Math.floor(death.at().y() + 1e-3),
                (int) Math.floor(death.at().z()));
        log.info("meridian-core: going back to {} for what was dropped - no fight takes the body on the way", cell);
        recovering = true;
        movement.noUrgent(true);
        // Counted by what the player came to carry more of: the things are picked up by coming near
        // them, most of them on the way in, before anything is "picked up" on purpose.
        long before = gained.getAsLong();
        return w.travel(Goal.near(cell, 1.5)).thenCompose(r -> {
            if (r.status() != NavResult.Status.ARRIVED) {
                log.info("meridian-core: did not get back to {} ({})", cell, r.status());
                return CompletableFuture.completedFuture(new Recovered(false, 0, "did not get back there: " + r.status()));
            }
            BlockBox around = BlockBox.of(cell, cell).grow(DROPS_AROUND);
            return w.pickUp(around).thenApply(n -> {
                int got = (int) Math.max(0, gained.getAsLong() - before);
                log.info("meridian-core: back where the player died - {} item(s) more than before", got);
                // None picked up: nothing lay there - or it lies where the body does not get to (sunk
                // under the water it died in): the worker's log says which.
                return new Recovered(true, got, got > 0 ? "" : "nothing picked up there");
            });
        }).exceptionally(e -> {
            log.warn("meridian-core: going back for the dropped things failed", e);
            return new Recovered(false, 0, "failed: " + e);
        }).whenComplete((what, e) -> {
            recovering = false;
            movement.noUrgent(false);
        });
    }

    @Override
    public void subscribe(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }
}
