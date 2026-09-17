package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.interface_.CustomHud;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.interface_.CustomUICommand;
import meridian.protocol.packets.setup.AssetFinalize;
import meridian.protocol.packets.setup.AssetInitialize;
import meridian.protocol.packets.setup.AssetPart;

/**
 * Feeds {@link ServerAssetsImpl} what the server sends the client that is not the world.
 * Observe-only.
 *
 * <p>{@code EARLY}, like core's other feeds and for the same reason: the asset stream is one that
 * something downstream may hold back - {@code AssetDedupGuard} drops a stream the client already
 * has - and a feed of "what the server said" must not lose an event to somebody else's decision.
 */
final class ServerAssetsHandler implements PacketHandler {

    private final ServerAssetsImpl assets;

    ServerAssetsHandler(ServerAssetsImpl assets) {
        this.assets = assets;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof AssetInitialize init) {
            assets.begins(init.asset == null ? "" : init.asset.name,
                    init.asset == null ? "" : init.asset.hash, init.size);
        } else if (packet instanceof AssetPart part) {
            assets.part(part.part);
        } else if (packet instanceof AssetFinalize) {
            assets.ends();
        } else if (packet instanceof CustomPage page) {
            assets.page(page.key == null ? "" : page.key, json(page));
        } else if (packet instanceof CustomHud hud) {
            assets.page(hud.hudId, json(hud));
        }
        return Action.FORWARD;
    }

    // ------------------------------------------------------------------
    // The page, written out
    // ------------------------------------------------------------------

    private static String json(CustomPage page) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"kind\":\"page\"");
        field(out, "key", page.key);
        out.append(",\"initial\":").append(page.isInitial);
        out.append(",\"clear\":").append(page.clear);
        field(out, "lifetime", page.lifetime == null ? null : page.lifetime.name());
        commands(out, page.commands);
        bindings(out, page);
        return out.append('}').toString();
    }

    private static String json(CustomHud hud) {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"kind\":\"hud\"");
        field(out, "key", hud.hudId);
        out.append(",\"zOrder\":").append(hud.zOrder);
        out.append(",\"clear\":").append(hud.clear);
        commands(out, hud.commands);
        return out.append('}').toString();
    }

    /** The commands are the page: what was put where, and what it was told to say. */
    private static void commands(StringBuilder out, CustomUICommand[] commands) {
        if (commands == null) {
            return;
        }
        out.append(",\"commands\":[");
        for (int i = 0; i < commands.length; i++) {
            CustomUICommand command = commands[i];
            if (i > 0) {
                out.append(',');
            }
            out.append('{');
            out.append("\"type\":\"").append(command.type == null ? "" : command.type.name())
                    .append('"');
            field(out, "selector", command.selector);
            field(out, "data", command.data);
            field(out, "text", command.text);
            out.append('}');
        }
        out.append(']');
    }

    /**
     * What the page answers to. Written by name only - the binding carries how it is wired, and
     * for keeping a copy of a server's interface the wiring is what there is to know.
     */
    private static void bindings(StringBuilder out, CustomPage page) {
        if (page.eventBindings == null) {
            return;
        }
        out.append(",\"bindings\":[");
        for (int i = 0; i < page.eventBindings.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(escaped(String.valueOf(page.eventBindings[i]))).append('"');
        }
        out.append(']');
    }

    private static void field(StringBuilder out, String name, String value) {
        if (value == null) {
            return;
        }
        out.append(",\"").append(name).append("\":\"").append(escaped(value)).append('"');
    }

    /** Enough JSON escaping for text that came off a wire and is going into a file. */
    private static String escaped(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
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
