package meridian.core.impl;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import meridian.api.session.ProxySession;
import meridian.core.api.ClientSpoof;
import meridian.protocol.ComponentUpdate;
import meridian.protocol.EntityStatOp;
import meridian.protocol.EntityStatType;
import meridian.protocol.EntityStatUpdate;
import meridian.protocol.EntityStatsUpdate;
import meridian.protocol.EntityUpdate;
import meridian.protocol.MovementSettings;
import meridian.protocol.MovementStates;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.entities.MountMovement;
import meridian.protocol.packets.entities.PlayAnimation;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.SetGameMode;
import meridian.protocol.packets.player.UpdateMovementSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ClientSpoof}: what a module wants the client shown and the server told, applied by core's
 * own rewriters - {@link ClientObserver} for the movement the client sends, {@link ClientSpoofHandler}
 * for what the server sends the client.
 *
 * <p>Nothing here keeps a copy of the truth. The server's game mode is {@link PlayerStateImpl}'s,
 * its movement settings core's movement's, the player's id the entity tracker's, the stat catalog
 * the player state's - each read off the wire before any rewrite (EARLY), so what this changes on
 * the way to the client never reaches them.
 *
 * <p>Setters run on whichever thread a module calls from; the rewrites on the network threads.
 */
final class ClientSpoofImpl implements ClientSpoof {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** The {@code CustomPage} key the server opens its death window under. */
    static final String RESPAWN_PAGE_KEY =
            "com.hypixel.hytale.server.core.entity.entities.player.pages.RespawnPage";
    private static final String STAMINA = "Stamina";
    private static final String HEALTH = "Health";

    private final SessionHolder session;
    private final EntityTrackerImpl entities;
    private final PlayerStateImpl player;
    /** The movement settings the server last sent, before any rewrite. */
    private final Supplier<MovementSettings> serverSettings;

    private volatile meridian.protocol.GameMode held;
    private volatile boolean flight;
    private volatile boolean fullStamina;
    private volatile boolean ignoreDeath;
    private volatile boolean dropFlying;
    private volatile boolean addSwimming;
    private volatile boolean noFall;

    /** The server killed the player while their death was hidden. */
    private volatile boolean deathHidden;
    /** The death window held back, shown when the death is let through. */
    private volatile CustomPage hiddenDeathPage;

    ClientSpoofImpl(SessionHolder session, EntityTrackerImpl entities, PlayerStateImpl player,
                    Supplier<MovementSettings> serverSettings) {
        this.session = session;
        this.entities = entities;
        this.player = player;
        this.serverSettings = serverSettings;
    }

    // ------------------------------------------------------------------
    // ClientSpoof
    // ------------------------------------------------------------------

    @Override
    public void holdGameMode(ClientSpoof.GameMode mode) {
        meridian.protocol.GameMode target = mode == null ? null : protocol(mode);
        held = target;
        ProxySession s = session.get().orElse(null);
        if (s == null) {
            return;
        }
        meridian.protocol.GameMode server = player.gameMode();
        if (target != null) {
            s.sendToClient(new SetGameMode(target));
            log.info("meridian-core: client held in game mode {}", target);
        } else if (server != null) {
            s.sendToClient(new SetGameMode(server));
            log.info("meridian-core: client game mode let go, back to the server's {}", server);
        }
    }

    @Override
    public Optional<ClientSpoof.GameMode> heldGameMode() {
        return Optional.ofNullable(api(held));
    }

    @Override
    public Optional<ClientSpoof.GameMode> serverGameMode() {
        return Optional.ofNullable(api(player.gameMode()));
    }

    @Override
    public void setFlight(boolean on) {
        flight = on;
        ProxySession s = session.get().orElse(null);
        MovementSettings base = serverSettings.get();
        if (s == null || base == null) {
            return;   // the next settings the server sends carry it
        }
        MovementSettings shown = new MovementSettings(base);
        shown.canFly = on || base.canFly;
        s.sendToClient(new UpdateMovementSettings(shown));
        log.info("meridian-core: client flight {} (canFly={})", on ? "on" : "off", shown.canFly);
    }

    @Override
    public boolean flight() {
        return flight;
    }

    @Override
    public void setFullStamina(boolean on) {
        fullStamina = on;
    }

    @Override
    public boolean fullStamina() {
        return fullStamina;
    }

    @Override
    public void setIgnoreDeath(boolean on) {
        ignoreDeath = on;
        if (on || !deathHidden) {
            return;
        }
        // Off after a death it hid: let the death through, so the client is dead as the server has it.
        ProxySession s = session.get().orElse(null);
        if (s != null) {
            OptionalInt me = entities.localEntityId();
            OptionalInt health = player.statIndex(HEALTH);
            if (me.isPresent() && health.isPresent()) {
                EntityStatUpdate zero = new EntityStatUpdate();
                zero.op = EntityStatOp.Set;
                zero.value = 0.0f;
                EntityStatsUpdate stats = new EntityStatsUpdate();
                stats.entityStatUpdates.put(health.getAsInt(), new EntityStatUpdate[]{zero});
                EntityUpdate update = new EntityUpdate(me.getAsInt(), null, new ComponentUpdate[]{stats});
                s.sendToClient(new EntityUpdates(null, new EntityUpdate[]{update}));
            }
            CustomPage page = hiddenDeathPage;
            if (page != null) {
                s.sendToClient(page);
            }
            log.info("meridian-core: death no longer ignored - let the hidden death through");
        }
        deathHidden = false;
    }

    @Override
    public boolean ignoreDeath() {
        return ignoreDeath;
    }

    @Override
    public boolean deathHidden() {
        return deathHidden;
    }

    @Override
    public void setDropFlying(boolean on) {
        dropFlying = on;
    }

    @Override
    public boolean dropFlying() {
        return dropFlying;
    }

    @Override
    public void setAddSwimming(boolean on) {
        addSwimming = on;
    }

    @Override
    public boolean addSwimming() {
        return addSwimming;
    }

    @Override
    public void setNoFall(boolean on) {
        noFall = on;
    }

    @Override
    public boolean noFall() {
        return noFall;
    }

    // ------------------------------------------------------------------
    // What the server is told - from ClientObserver, after the truth is read
    // ------------------------------------------------------------------

    /** The flags and the fall speed of one of the player's movement reports; whether it changed. */
    boolean rewrite(ClientMovement m) {
        boolean changed = rewrite(m.movementStates);
        changed |= rewrite(m.riderMovementStates);
        if (noFall && m.velocity != null && m.velocity.y < 0.0) {
            // The server's fall damage goes by the speed the client says it landed with.
            m.velocity.y = 0.0;
            changed = true;
        }
        return changed;
    }

    /** The flags of a mount's movement report; whether it changed. */
    boolean rewrite(MountMovement m) {
        return rewrite(m.movementStates);
    }

    /**
     * One set of flags. Swimming is added only where the client itself said it flies - told in
     * place of the flight, never on a walk - and read before the flight is dropped. The client
     * sends its flags whole and only when they change, so the flight's end (flying false) takes
     * the added swimming away as well.
     */
    private boolean rewrite(MovementStates states) {
        if (states == null) {
            return false;
        }
        boolean flies = states.flying;
        boolean changed = false;
        if (dropFlying && flies) {
            states.flying = false;
            changed = true;
        }
        if (addSwimming && flies && !states.swimming) {
            states.swimming = true;
            changed = true;
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // What the client is shown - from ClientSpoofHandler, after the truth is read
    // ------------------------------------------------------------------

    /** Holds the client's mode against the server's; whether the packet changed. */
    boolean rewrite(SetGameMode packet) {
        meridian.protocol.GameMode target = held;
        if (target != null && packet.gameMode != target) {
            packet.gameMode = target;
            return true;
        }
        return false;
    }

    /** Lifts disabled flight; whether the packet changed. */
    boolean rewrite(UpdateMovementSettings packet) {
        MovementSettings settings = packet.movementSettings;
        if (!flight || settings == null || settings.canFly) {
            return false;
        }
        // A copy: core's movement keeps the object it read, and that one is the server's word.
        MovementSettings shown = new MovementSettings(settings);
        shown.canFly = true;
        packet.movementSettings = shown;
        return true;
    }

    /** Whether to hold this page back from the client: the death window, while death is ignored. */
    boolean hides(CustomPage page) {
        if (!ignoreDeath || !RESPAWN_PAGE_KEY.equals(page.key)) {
            return false;
        }
        if (page.clear) {
            deathHidden = false;          // the server revived the player itself
        } else {
            hiddenDeathPage = page;
            deathHidden = true;
        }
        return true;
    }

    /** Whether to hold this animation back: the player's own death, while death is ignored. */
    boolean hides(PlayAnimation animation) {
        if (!ignoreDeath) {
            return false;
        }
        OptionalInt me = entities.localEntityId();
        return me.isPresent() && animation.entityId == me.getAsInt() && isDeath(animation.animationId);
    }

    private static boolean isDeath(String id) {
        return "Death".equals(id) || "Laydown".equals(id) || "Sleep".equals(id);
    }

    /**
     * Stamina full on every entity, and - while death is ignored - the player's own health never
     * under 1. A change that would put a bar lower is raised to the floor, whatever its kind; the
     * player state has already applied the real one.
     */
    boolean rewrite(EntityUpdates packet) {
        boolean stamina = fullStamina;
        boolean health = ignoreDeath;
        if (packet.updates == null || !stamina && !health) {
            return false;
        }
        OptionalInt staminaIndex = stamina ? player.statIndex(STAMINA) : OptionalInt.empty();
        OptionalInt healthIndex = health ? player.statIndex(HEALTH) : OptionalInt.empty();
        if (staminaIndex.isEmpty() && healthIndex.isEmpty()) {
            return false;
        }
        float staminaMax = 0;
        if (staminaIndex.isPresent()) {
            EntityStatType type = player.statType(staminaIndex.getAsInt());
            staminaMax = type == null ? 0 : type.max;
        }
        OptionalInt me = entities.localEntityId();
        boolean changed = false;
        for (EntityUpdate update : packet.updates) {
            if (update == null || update.updates == null) {
                continue;
            }
            boolean mine = me.isPresent() && update.networkId == me.getAsInt();
            for (ComponentUpdate component : update.updates) {
                if (!(component instanceof EntityStatsUpdate stats) || stats.entityStatUpdates == null) {
                    continue;
                }
                Map<Integer, EntityStatUpdate[]> changes = stats.entityStatUpdates;
                if (staminaIndex.isPresent()) {
                    changed |= raise(changes.get(staminaIndex.getAsInt()), staminaMax);
                }
                if (healthIndex.isPresent() && mine) {
                    changed |= raise(changes.get(healthIndex.getAsInt()), 1.0f);
                }
            }
        }
        return changed;
    }

    /** Raises every change's value under {@code floor} to it; whether any was. */
    private static boolean raise(EntityStatUpdate[] changes, float floor) {
        if (changes == null) {
            return false;
        }
        boolean changed = false;
        for (EntityStatUpdate change : changes) {
            if (change != null && change.value < floor) {
                change.value = floor;
                changed = true;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------

    private static meridian.protocol.GameMode protocol(ClientSpoof.GameMode mode) {
        return mode == ClientSpoof.GameMode.CREATIVE
                ? meridian.protocol.GameMode.Creative : meridian.protocol.GameMode.Adventure;
    }

    private static ClientSpoof.GameMode api(meridian.protocol.GameMode mode) {
        if (mode == null) {
            return null;
        }
        return mode == meridian.protocol.GameMode.Creative
                ? ClientSpoof.GameMode.CREATIVE : ClientSpoof.GameMode.ADVENTURE;
    }
}
