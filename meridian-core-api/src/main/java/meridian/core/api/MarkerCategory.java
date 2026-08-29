package meridian.core.api;

/**
 * What kind of thing a marker is — the grouping the game itself makes, not one we invented.
 *
 * <p>The server decides this by what a marker carries and what its id looks like: a marker
 * standing for a player's position, one a player placed (shared with everyone or kept to
 * themselves), or one the world itself put there — a spawn, a home, a point of interest, the
 * place someone died. {@link #LOCAL} is the exception: those exist only on this proxy, and the
 * server has never heard of them.
 */
public enum MarkerCategory {

    /** Where a player is right now. */
    PLAYER("player"),

    /** Placed by a player, visible to everyone. */
    USER_SHARED("shared"),

    /** Placed by a player, visible only to them. */
    USER_PRIVATE("private"),

    /** The world's own: spawn, home, points of interest, death markers, warps. */
    SERVER("server"),

    /** Ours. Forged for the client; the server does not know it exists. */
    LOCAL("local");

    private final String label;

    MarkerCategory(String label) {
        this.label = label;
    }

    /** Short lowercase name, for showing to a person. */
    public String label() {
        return label;
    }
}
