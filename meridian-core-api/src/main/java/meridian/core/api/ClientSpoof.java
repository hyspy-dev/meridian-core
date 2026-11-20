package meridian.core.api;

import java.util.Optional;

/**
 * What the client is shown of itself, and what the server is told of the client's movement, when
 * a module wants either to differ from the truth.
 *
 * <p>A module says what it wants; core applies it inside the one place it rewrites each packet,
 * and reads the true values out of that packet first. So everything core knows stays the server's
 * word while a spoof is on: {@link PlayerState#stats()} reads the real stamina and health while the
 * client is shown full bars, and core's own game mode, movement settings and player id are the
 * ones the server set. Two modules rewriting one packet each undo the other - this is the one
 * owner of these rewrites.
 *
 * <p>Two halves. What the client is <em>shown</em> - its game mode, whether it may fly, its stamina,
 * its death - changes only what the client draws and lets the player do on their own screen; the
 * server stays authoritative for all of it. What the server is <em>told</em> - the flying and
 * swimming flags and the fall speed in the client's movement reports - is what the server goes by.
 *
 * <p>Everything starts off. The setters take effect at once (anything the client has to be told is
 * sent then) and hold against whatever the server sends later.
 */
public interface ClientSpoof {

    /** A game mode the client can be held in. */
    enum GameMode { ADVENTURE, CREATIVE }

    // ------------------------------------------------------------------
    // What the client is shown
    // ------------------------------------------------------------------

    /**
     * Holds the client in {@code mode}: told at once, and every later change of mode the server
     * sends is rewritten to it. {@code null} lets go and gives the client the server's own mode
     * back.
     */
    void holdGameMode(GameMode mode);

    /** The mode the client is held in, or empty when it is shown the server's. */
    Optional<GameMode> heldGameMode();

    /** The game mode the server has the player in, or empty before it has said. */
    Optional<GameMode> serverGameMode();

    /**
     * Lets the client fly where the server has flight disabled; flight the server forces stays
     * forced. Told at once and held against later movement settings; off gives the client the
     * server's own settings back.
     */
    void setFlight(boolean on);

    boolean flight();

    /** Shows every entity's stamina as full: a change that would lower it raises it to the top. */
    void setFullStamina(boolean on);

    boolean fullStamina();

    /**
     * Hides the player's death from the client: their health never shows under 1, and the death
     * window and the death animations are held back. Turned off after a death it hid, the death is
     * let through - health 0 and the death window - so the client catches up with the server.
     */
    void setIgnoreDeath(boolean on);

    boolean ignoreDeath();

    /** Whether the server killed the player while {@link #setIgnoreDeath} was hiding it. */
    boolean deathHidden();

    // ------------------------------------------------------------------
    // What the server is told
    // ------------------------------------------------------------------

    /** Clears the flying flag from every movement report, the player's and a mount's. */
    void setDropFlying(boolean on);

    boolean dropFlying();

    /**
     * Sets the swimming flag on the movement reports where the client says it flies - the server
     * told "swimming" in place of "flying" (with {@link #setDropFlying} the flight itself is gone
     * too). A walk, a jump or a fall is left as it is.
     */
    void setAddSwimming(boolean on);

    boolean addSwimming();

    /**
     * Reports no downward speed. The server hurts a landing by the fall speed the client reported,
     * so with none there is no fall damage; a jump's upward speed is left alone.
     */
    void setNoFall(boolean on);

    boolean noFall();
}
