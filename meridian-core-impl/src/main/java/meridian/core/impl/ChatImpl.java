package meridian.core.impl;

import meridian.core.api.Chat;
import meridian.protocol.FormattedMessage;
import meridian.protocol.packets.interface_.ServerMessage;

/**
 * Chat lines, sent to this client only.
 *
 * <p>The default colour is the orange the server uses for its own refusals and warnings, so a
 * notice from us reads as part of the same conversation rather than something bolted on.
 */
final class ChatImpl implements Chat {

    /** The colour the game uses for a system notice. */
    private static final String NOTICE = "#ffc800";

    private final SessionHolder session;

    ChatImpl(SessionHolder session) {
        this.session = session;
    }

    @Override
    public void send(String text) {
        send(text, NOTICE);
    }

    @Override
    public void send(String text, String colourHex) {
        if (text == null || text.isEmpty()) {
            return;
        }
        session.get().ifPresent(live -> {
            FormattedMessage message = new FormattedMessage();
            message.rawText = text;
            message.color = colourHex;
            ServerMessage packet = new ServerMessage();
            packet.message = message;
            live.sendToClient(packet);
        });
    }
}
