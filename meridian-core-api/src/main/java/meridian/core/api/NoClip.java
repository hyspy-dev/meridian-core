package meridian.core.api;

/**
 * Client-side no-clip: telling the client it may pass through blocks.
 *
 * <p>What this is, and is not. The server decides no-clip for itself and gates it on a permission;
 * nothing a proxy sends changes that. What core forges here is the client's own no-clip switch, so
 * the player's client stops colliding locally. The server still collides, so a player who walks
 * far into a wall is pulled back - this is the client half of no-clip, useful where the server is
 * lenient about where a player claims to be, cosmetic where it is not.
 *
 * <p>Only offered where the protocol carries the switch at all, which is the 0.6 line and later.
 * Ask with {@code get}, not {@code require}: on an older line no one provides it, and that absence
 * is the feature's own answer to "is this server new enough".
 */
public interface NoClip {

    /** Tells the client to pass through blocks, or to stop. Re-asserted on each world join. */
    void setEnabled(boolean on);

    boolean isEnabled();
}
