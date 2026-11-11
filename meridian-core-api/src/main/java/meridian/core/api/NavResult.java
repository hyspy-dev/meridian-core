package meridian.core.api;

/**
 * How a trip went.
 *
 * @param status  how it ended
 * @param at      where the feet are
 * @param moves   the moves played
 * @param replans how often the way was looked for again (blocked, or beyond what was known)
 */
public record NavResult(Status status, Vec3 at, int moves, int replans) {

    public enum Status {
        /** At the goal. */
        ARRIVED,
        /** No way from here: the goal is shut off, or out of reach. */
        NO_PATH,
        /** Gave up after too many moves turned out blocked. */
        BLOCKED,
        /** Cancelled, or the server moved the player. */
        INTERRUPTED,
        /** The player cannot be moved here: the game has not said where it is, or the server sent no movement settings. */
        NOT_AVAILABLE
    }

    public boolean arrived() {
        return status == Status.ARRIVED;
    }
}
