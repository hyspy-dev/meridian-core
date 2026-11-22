package meridian.core.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The fight as this client is told of it - a meridian-core Layer-1 service: who hurt the player,
 * and who begins an attack. Facts only, read off what the server sends any game; whom to fight is a
 * module's call.
 *
 * <ul>
 *   <li><b>A hit on the player.</b> The server tells a player hurt by an entity how much, of what
 *       kind, and where that entity stood when it struck ({@code DamageInfo}), so the game can
 *       show where it came from. Core names the entity it tracks standing there.</li>
 *   <li><b>An attack begun.</b> The server plays every interaction another entity starts - an
 *       NPC's attack, another player's swing - to the players who see it
 *       ({@code PlayInteractionFor}), so their games can animate it. The first of a
 *       {@code Primary} or {@code Secondary} chain is an attack begun; a mob's swing lands a
 *       little after it starts (a zombie's 0.2 s), which is the time a player has to raise a
 *       guard.</li>
 * </ul>
 *
 * <p>Nothing here says whether a mob is hostile: the server sends no attitude. An entity that hit
 * the player, or that began an attack near them, is the best a client knows.
 *
 * <p>Listeners are called on the proxy's network threads; keep them short.
 */
public interface Combat {

    /**
     * A hit the player took from an entity.
     *
     * @param at       when it came, {@link System#currentTimeMillis()}
     * @param attacker the network id of the entity core saw standing where the blow came from;
     *                 {@code -1} when none was there to see
     * @param from     where the attacker stood, as the server said
     * @param amount   the damage, after armour and guard, as the server dealt it
     * @param cause    the kind of damage ({@code Physical}, {@code Projectile}...), or {@code null}
     */
    record Hit(long at, int attacker, Vec3 from, double amount, String cause) {}

    /**
     * An attack another entity began.
     *
     * @param at     when core heard of it, {@link System#currentTimeMillis()}
     * @param entity the attacker's network id
     * @param type   the chain's kind: {@code Primary} or {@code Secondary}
     * @param chain  the server's id of the chain, to tell one attack from the next
     */
    record Swing(long at, int entity, String type, int chain) {}

    /** Hears the fight as it goes. */
    interface Listener {

        /** The player was hit. */
        default void hit(Hit hit) {}

        /** Another entity began an attack. */
        default void swing(Swing swing) {}
    }

    /** The hits the player took within {@code within} of now, oldest first. */
    List<Hit> hits(Duration within);

    /** The attacks other entities began within {@code within} of now, oldest first. */
    List<Swing> swings(Duration within);

    /** The last attack {@code entityId} began, if it began one within {@code within} of now. */
    default Optional<Swing> swingOf(int entityId, Duration within) {
        List<Swing> all = swings(within);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).entity() == entityId) {
                return Optional.of(all.get(i));
            }
        }
        return Optional.empty();
    }

    /** Whether {@code entityId} hit the player within {@code within} of now. */
    /**
     * What an NPC of a kind senses, as the installed game's own role files have it
     * ({@code Server/NPC/Roles}: {@code ViewRange}, {@code ViewSector}, {@code HearingRange},
     * {@code AlertedRange}, {@code MaxSpeed}, {@code AttackDistance}) - the keenest of the roles that
     * wear the kind's model. A
     * server running roles of its own does not send them: this is the game's word, not that server's.
     *
     * @param viewRange    how far it sees, in blocks
     * @param viewSector   the angle it sees in, in degrees, about where it faces; {@code 0} when not said
     * @param hearingRange how far it hears, whichever way it faces; {@code 0} when not said
     * @param alertedRange how far it goes on minding what alerted it; {@code 0} when not said
     * @param maxSpeed     its top speed, in blocks a second; {@code 0} when not said
     * @param attackDistance how far off it attacks from - a blow's two or three blocks, a flame's six,
     *                     an arrow's fifteen; {@code 0} when not said
     */
    record Senses(double viewRange, double viewSector, double hearingRange, double alertedRange, double maxSpeed,
                  double attackDistance) {
    }

    /**
     * What the entity's kind senses ({@link Senses}): whether the player standing where it is will
     * be noticed is a matter of these, not of a radius somebody chose.
     *
     * @return empty for a kind the game's files do not have (a player, a server's own NPC), before the
     *         files are read, when no game is installed, and for a core too old to tell
     */
    default Optional<Senses> sensesOf(int entityId) {
        return Optional.empty();
    }

    /**
     * What goes off, and how far it reaches: a thrown bomb, as the game's own files have it.
     *
     * @param radius      how far from it whatever lives is hurt, in blocks
     * @param damage      what it takes off those within it
     * @param fuseSeconds how long after it was thrown it goes off by itself; {@code 0} for one that
     *                    goes off only where it lands or hits
     */
    record Blast(double radius, double damage, double fuseSeconds) {
    }

    /**
     * The blast of an entity that explodes - a goblin lobber's bomb in the air or on the ground: what
     * the game's files say of the projectile wearing that model. Empty for what does not explode, where
     * the files cannot be read, and on a core too old to tell.
     */
    default Optional<Blast> blastOf(int entityId) {
        return Optional.empty();
    }

    default boolean hitBy(int entityId, Duration within) {
        for (Hit h : hits(within)) {
            if (h.attacker() == entityId) {
                return true;
            }
        }
        return false;
    }

    void subscribe(Listener listener);

    void unsubscribe(Listener listener);
}
