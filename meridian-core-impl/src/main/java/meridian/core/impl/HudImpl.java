package meridian.core.impl;

import java.util.ArrayList;
import java.util.List;
import meridian.api.session.ProxySession;
import meridian.core.api.Hud;
import meridian.protocol.packets.interface_.CustomHud;
import meridian.protocol.packets.interface_.CustomUICommand;
import meridian.protocol.packets.interface_.CustomUICommandType;

/**
 * Turns UI commands into the packets the client understands.
 *
 * <p>The wire form of a property assignment is a positional argument object — the server sends
 * {@code {"0": <value>}} — so values are wrapped here rather than in every caller. Strings are
 * escaped for JSON, because a waypoint or world name can contain a quote and a broken document
 * is silently ignored by the client, which is a miserable thing to debug.
 */
final class HudImpl implements Hud {

    private final SessionHolder session;

    HudImpl(SessionHolder session) {
        this.session = session;
    }

    @Override
    public Batch batch() {
        return new BatchImpl();
    }

    @Override
    public void hide(String hudId) {
        session.get().ifPresent(live ->
                live.sendToClient(new CustomHud(hudId, 0, true, new CustomUICommand[0])));
    }

    private final class BatchImpl implements Batch {

        private final List<CustomUICommand> commands = new ArrayList<>();

        @Override
        public Batch append(String documentPath) {
            return add(CustomUICommandType.Append, null, null, documentPath);
        }

        @Override
        public Batch appendTo(String selector, String documentPath) {
            return add(CustomUICommandType.Append, selector, null, documentPath);
        }

        @Override
        public Batch appendInline(String markup) {
            return add(CustomUICommandType.AppendInline, null, null, markup);
        }

        @Override
        public Batch appendInlineTo(String selector, String markup) {
            return add(CustomUICommandType.AppendInline, selector, null, markup);
        }

        @Override
        public Batch insertBefore(String selector, String documentPath) {
            return add(CustomUICommandType.InsertBefore, selector, null, documentPath);
        }

        @Override
        public Batch insertBeforeInline(String selector, String markup) {
            return add(CustomUICommandType.InsertBeforeInline, selector, null, markup);
        }

        @Override
        public Batch set(String selector, String value) {
            return add(CustomUICommandType.Set, selector, "{\"0\":\"" + escape(value) + "\"}", null);
        }

        @Override
        public Batch set(String selector, int value) {
            return add(CustomUICommandType.Set, selector, "{\"0\":" + value + "}", null);
        }

        @Override
        public Batch set(String selector, boolean value) {
            return add(CustomUICommandType.Set, selector, "{\"0\":" + value + "}", null);
        }

        @Override
        public Batch setRaw(String selector, String json) {
            return add(CustomUICommandType.Set, selector, "{\"0\":" + json + "}", null);
        }

        @Override
        public Batch remove(String selector) {
            return add(CustomUICommandType.Remove, selector, null, null);
        }

        @Override
        public Batch clear() {
            return add(CustomUICommandType.Clear, null, null, null);
        }

        @Override
        public void show(String hudId, int zOrder) {
            send(hudId, zOrder, true);
        }

        @Override
        public void patch(String hudId, int zOrder) {
            send(hudId, zOrder, false);
        }

        private Batch add(CustomUICommandType type, String selector, String data, String document) {
            commands.add(new CustomUICommand(type, selector, data, document));
            return this;
        }

        private void send(String hudId, int zOrder, boolean replace) {
            if (commands.isEmpty()) {
                return;
            }
            ProxySession live = session.get().orElse(null);
            if (live == null) {
                return;
            }
            live.sendToClient(new CustomHud(hudId, zOrder, replace,
                    commands.toArray(new CustomUICommand[0])));
        }
    }

    /** JSON string escaping — the client drops a document it cannot parse, without a word. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
