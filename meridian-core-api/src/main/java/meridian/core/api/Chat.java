package meridian.core.api;

/**
 * Says something to the player, in their chat.
 *
 * <p>For telling them what happened — a request the server turned down, a thing that was done
 * on their behalf. The message is local: it goes to this client only, and nobody else on the
 * server sees it.
 *
 * <pre>{@code
 * Chat chat = ctx.services().require(Chat.class);
 * chat.send("[Markers] Server refused that marker - kept it local instead.");
 * }</pre>
 */
public interface Chat {

    /** Sends a line in the colour the game uses for its own notices. */
    void send(String text);

    /**
     * Sends a line in a colour of your choosing.
     *
     * @param colourHex CSS-style {@code #rrggbb}
     */
    void send(String text, String colourHex);
}
