package meridian.core.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import meridian.api.packet.Packet;
import meridian.api.session.ProxySession;
import meridian.core.api.Pages;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.interface_.CustomPageLifetime;
import meridian.protocol.packets.interface_.CustomUICommand;
import meridian.protocol.packets.interface_.CustomUICommandType;
import meridian.protocol.packets.interface_.CustomUIEventBinding;
import meridian.protocol.packets.interface_.CustomUIEventBindingType;
import meridian.protocol.packets.interface_.SetPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pages of ours on the client, with the server none the wiser.
 *
 * <p>The client answers every page packet - and a {@code SetPage} while a page is up - with an
 * {@code Acknowledge}. The server counts the ones it is owed and treats any extra as a client
 * fault, so each acknowledgement our packets earn is taken out here before it can reach it. The
 * acknowledgements carry nothing to tell whose they are; only the count matters, and the count
 * stays exact either way.
 */
final class PagesImpl implements Pages {

    private static final Logger LOG = LoggerFactory.getLogger("meridian-core");
    private static final String KEY_PREFIX = "meridian:";

    private final SessionHolder session;
    /** Acknowledgements the client still owes for packets of ours. */
    private final AtomicInteger owedAcks = new AtomicInteger();
    private volatile PageImpl current;

    PagesImpl(SessionHolder session) {
        this.session = session;
    }

    @Override
    public Page open(String key, boolean canDismiss, Consumer<Builder> build, Listener listener) {
        ProxySession live = session.get().orElse(null);
        if (live == null) {
            return null;
        }
        PageImpl page = new PageImpl(KEY_PREFIX + key,
                canDismiss ? CustomPageLifetime.CanDismiss : CustomPageLifetime.CantClose, listener);
        PageImpl previous;
        synchronized (this) {
            previous = current;
            current = page;
        }
        if (previous != null) {
            previous.gone();
        }
        BuilderImpl b = new BuilderImpl();
        build.accept(b);
        send(live, new CustomPage(page.key, true, true, page.lifetime, b.commands(), b.bindings()));
        return page;
    }

    private void send(ProxySession live, Packet packet) {
        owedAcks.incrementAndGet();
        live.sendToClient(packet);
    }

    // ------------------------------------------------------------------
    // From the handler
    // ------------------------------------------------------------------

    /** An acknowledgement from the client: ours to swallow while we are owed one. */
    boolean takeAck() {
        return owedAcks.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    /** Whether a data or dismiss event belongs to a page of ours. */
    boolean ownsPage() {
        PageImpl p = current;
        return p != null && p.open;
    }

    void onData(String raw) {
        PageImpl p = current;
        if (p == null || !p.open) {
            return;
        }
        Map<String, String> data = parse(raw);
        p.answered = false;
        try {
            p.listener.data(p, data);
        } catch (RuntimeException e) {
            LOG.warn("meridian-core: page {} listener failed on {}", p.key, raw, e);
        }
        if (p.open && !p.answered) {
            p.update(b -> { });          // the client may be holding the page for an answer
        }
    }

    void onDismiss() {
        PageImpl p;
        synchronized (this) {
            p = current;
            current = null;
        }
        if (p != null) {
            p.gone();
        }
    }

    /** The server put up a page, or took them all down: ours is no longer on screen. */
    void onServerPage(String key) {
        if (key != null && key.startsWith(KEY_PREFIX)) {
            return;                      // our own packet, passing through the pipeline
        }
        onDismiss();
    }

    /** A new session - nothing of ours is up, and no acknowledgement is owed any more. */
    void reset() {
        owedAcks.set(0);
        onDismiss();
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(raw);
            if (root.isJsonObject()) {
                JsonObject o = root.getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    JsonElement v = e.getValue();
                    out.put(e.getKey(), v == null || v.isJsonNull() ? null
                            : v.isJsonPrimitive() ? v.getAsString() : v.toString());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("meridian-core: unreadable page event {}", raw);
        }
        return out;
    }

    // ------------------------------------------------------------------

    private final class PageImpl implements Page {

        final String key;
        final CustomPageLifetime lifetime;
        final Listener listener;
        volatile boolean open = true;
        volatile boolean answered;

        PageImpl(String key, CustomPageLifetime lifetime, Listener listener) {
            this.key = key;
            this.lifetime = lifetime;
            this.listener = listener;
        }

        @Override
        public void update(Consumer<Builder> build) {
            ProxySession live = session.get().orElse(null);
            if (!open || live == null) {
                return;
            }
            BuilderImpl b = new BuilderImpl();
            build.accept(b);
            answered = true;
            send(live, new CustomPage(key, false, false, lifetime, b.commands(), b.bindings()));
        }

        @Override
        public void close() {
            synchronized (PagesImpl.this) {
                if (!open) {
                    return;
                }
                open = false;
                if (current == this) {
                    current = null;
                }
            }
            ProxySession live = session.get().orElse(null);
            if (live != null) {
                SetPage none = new SetPage();
                none.page = meridian.protocol.packets.interface_.Page.None;
                none.canCloseThroughInteraction = false;
                send(live, none);        // answered with an Acknowledge, since a page is up
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        /** Closed from the client's side or pushed off by another page - no packet of ours. */
        void gone() {
            if (!open) {
                return;
            }
            open = false;
            try {
                listener.dismissed(this);
            } catch (RuntimeException e) {
                LOG.warn("meridian-core: page {} dismiss listener failed", key, e);
            }
        }
    }

    private static final class BuilderImpl implements Builder {

        private final List<CustomUICommand> commands = new ArrayList<>();
        private final List<CustomUIEventBinding> bindings = new ArrayList<>();

        CustomUICommand[] commands() {
            return commands.toArray(new CustomUICommand[0]);
        }

        CustomUIEventBinding[] bindings() {
            return bindings.toArray(new CustomUIEventBinding[0]);
        }

        private Builder add(CustomUICommandType type, String selector, String data, String document) {
            commands.add(new CustomUICommand(type, selector, data, document));
            return this;
        }

        @Override
        public Builder append(String documentPath) {
            return add(CustomUICommandType.Append, null, null, documentPath);
        }

        @Override
        public Builder appendTo(String selector, String documentPath) {
            return add(CustomUICommandType.Append, selector, null, documentPath);
        }

        @Override
        public Builder set(String selector, String value) {
            return add(CustomUICommandType.Set, selector, "{\"0\":" + quote(value) + "}", null);
        }

        @Override
        public Builder set(String selector, int value) {
            return add(CustomUICommandType.Set, selector, "{\"0\":" + value + "}", null);
        }

        @Override
        public Builder set(String selector, boolean value) {
            return add(CustomUICommandType.Set, selector, "{\"0\":" + value + "}", null);
        }

        @Override
        public Builder setRef(String selector, String documentPath, String valueName) {
            return add(CustomUICommandType.Set, selector, "{\"0\":{\"$Document\":" + quote(documentPath)
                    + ",\"@Value\":" + quote(valueName) + "}}", null);
        }

        @Override
        public Builder clear(String selector) {
            return add(CustomUICommandType.Clear, selector, null, null);
        }

        @Override
        public Builder remove(String selector) {
            return add(CustomUICommandType.Remove, selector, null, null);
        }

        @Override
        public Builder bind(Trigger trigger, String selector, Map<String, String> data, boolean locks) {
            String json = null;
            if (data != null && !data.isEmpty()) {
                StringBuilder sb = new StringBuilder("{");
                for (Map.Entry<String, String> e : data.entrySet()) {
                    if (sb.length() > 1) {
                        sb.append(',');
                    }
                    sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
                }
                json = sb.append('}').toString();
            }
            CustomUIEventBindingType type = switch (trigger) {
                case ACTIVATING -> CustomUIEventBindingType.Activating;
                case DOUBLE_CLICKING -> CustomUIEventBindingType.DoubleClicking;
                case RIGHT_CLICKING -> CustomUIEventBindingType.RightClicking;
                case VALUE_CHANGED -> CustomUIEventBindingType.ValueChanged;
            };
            bindings.add(new CustomUIEventBinding(type, selector, json, locks));
            return this;
        }
    }

    /** A JSON string literal - a name with a quote in it must not break the document. */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 8).append('"');
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
        return out.append('"').toString();
    }
}
