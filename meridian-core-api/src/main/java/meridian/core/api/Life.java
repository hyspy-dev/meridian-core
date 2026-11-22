package meridian.core.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The player's life and death, as the server tells it: the death window it opens when the player
 * dies, the window gone once it has revived them.
 *
 * <p>A death ends every hold on the body ({@link Movement.Release#DEATH}) - a trip, a dig, a fight
 * stop where they are - and while the player is dead the body cannot be taken
 * ({@link Movement#available} is {@code false}). A session with no game behind it (a headless
 * client) presses Respawn by itself, as a player would, and then goes back for what it dropped
 * ({@link #recover}) unless that is switched off - once: a death on the way back is not gone back
 * for again, whatever killed the player there would. Then it leaves the server: what it was doing is
 * not gone on with - the things it picked up again lie in other slots than they did. A game's player
 * presses Respawn themselves, and stays.
 */
public interface Life {

    /**
     * A death.
     *
     * @param at       where the player stood when they died
     * @param world    the world they died in, or {@code null} when the server had not named it
     * @param atMillis when ({@link System#currentTimeMillis})
     */
    record Death(Vec3 at, UUID world, long atMillis) {
    }

    /**
     * What going back for the dropped things came to.
     *
     * @param arrived the body got back to where the player died
     * @param picked  how many items the player carries more of for it
     * @param why     in words, when it did not get there or there was nothing to go for
     */
    record Recovered(boolean arrived, int picked, String why) {
    }

    /** What happens to the player's life; called on the session's thread - be quick. */
    interface Listener {

        /** The player died: every hold on the body is over already. */
        default void died(Death death) {
        }

        /** The server revived the player: the body can be taken again. */
        default void revived(Death death) {
        }

        /** What going back for the dropped things came to (a headless session goes back by itself). */
        default void recovered(Death death, Recovered what) {
        }
    }

    /** The player is dead: the server's death window is up. */
    boolean dead();

    /**
     * The body is on its way back for what it dropped ({@link #recover}). Until that is over nothing
     * takes the body from it - no urgent hold is given ({@link Movement#takeFirst} is empty): what
     * attacks it on the way is run from, not fought.
     */
    boolean recovering();

    /** The player's last death in this session; empty when there was none. */
    Optional<Death> lastDeath();

    /**
     * Presses Respawn on the death window, as a player does.
     *
     * @return done with {@code true} once the server has revived the player; {@code false} at once
     *         when the player is not dead
     */
    CompletableFuture<Boolean> respawn();

    /**
     * Goes back to where the player last died and picks up what lies there - what they dropped:
     * the server keeps a dropped item for a while (ten minutes by its own setting), then it is gone.
     * A way is made there if no walk goes ({@link Worker#travel}).
     */
    CompletableFuture<Recovered> recover();

    /**
     * Leaves the server, as a player quitting does: the game's own word that it is leaving - the
     * server takes the player out of the world at once, where a connection merely dropped it keeps
     * for a minute - then the connection. A session with a game behind it ends the same way: its
     * game is left with the connection closed.
     *
     * @param why in words, for the log
     * @return {@code false} when there is no session to leave, or the core is too old to
     */
    default boolean leave(String why) {
        return false;
    }

    void subscribe(Listener listener);

    void unsubscribe(Listener listener);
}
