package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.ComponentUpdate;
import meridian.protocol.EntityUpdate;
import meridian.protocol.FormattedMessage;
import meridian.protocol.NameplateUpdate;
import meridian.protocol.ParamValue;
import meridian.protocol.PlayerSkinUpdate;
import meridian.protocol.StringParamValue;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.interface_.AddToServerPlayerList;
import meridian.protocol.packets.interface_.CustomHud;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.interface_.CustomUICommand;
import meridian.protocol.packets.interface_.KillFeedMessage;
import meridian.protocol.packets.interface_.Notification;
import meridian.protocol.packets.interface_.ServerMessage;
import meridian.protocol.packets.interface_.ServerPlayerListPlayer;
import meridian.protocol.packets.worldmap.MapMarker;
import meridian.protocol.packets.worldmap.UpdateWorldMap;

/**
 * "Anonymise players": each player's name, wherever the game shows it, turned into as many stars as
 * it has letters - over heads, in the player list, in chat and the server's messages (a death among
 * them), in the kill feed, in notifications, on the map's markers and in what the labels of a
 * server window or HUD show (the death screen's reason). The player's own name too.
 *
 * <p>Only what goes on to the game is changed: the handler is core's last, at MONITOR, after core's
 * own readers, so core's services still see the real names (an escort by name still finds its
 * player). Names are learnt from the player list, from players' nameplates and from who we are; a
 * name is starred anywhere in a text as a whole word, case aside.
 *
 * <p>Messages are not changed in place: a starred copy is put in the packet, so nothing that kept
 * the original (a marker kept by core) is starred behind its back.
 */
final class Anonymizer {

    private final Supplier<String> ownName;
    private final java.util.function.IntPredicate isPlayer;
    private final Set<String> names = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled;
    private volatile Pattern pattern;

    Anonymizer(Supplier<String> ownName, java.util.function.IntPredicate isPlayer) {
        this.ownName = ownName;
        this.isPlayer = isPlayer;
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean on) {
        enabled = on;
    }

    /** The names known so far. */
    Set<String> names() {
        learn(ownName.get());
        return Set.copyOf(names);
    }

    PacketHandler handler() {
        return new PacketHandler() {
            @Override
            public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
                // Learnt even while off: switched on mid-game, the names met before are known.
                learnFrom(packet);
                if (!enabled) {
                    return Action.FORWARD;
                }
                learn(ownName.get());
                return star(packet) ? Action.MODIFIED : Action.FORWARD;
            }
        };
    }

    // ------------------------------------------------------------------

    private void learnFrom(Packet packet) {
        if (packet instanceof AddToServerPlayerList list && list.players != null) {
            for (ServerPlayerListPlayer p : list.players) {
                if (p != null) {
                    learn(p.username);
                }
            }
        } else if (packet instanceof EntityUpdates updates && updates.updates != null) {
            for (EntityUpdate u : updates.updates) {
                if (u != null && u.updates != null && player(u)) {
                    for (ComponentUpdate c : u.updates) {
                        if (c instanceof NameplateUpdate n) {
                            learn(n.text);
                        }
                    }
                }
            }
        }
    }

    void learn(String name) {
        if (name == null) {
            return;
        }
        String n = name.strip();
        if (n.isEmpty() || n.chars().allMatch(ch -> ch == '*') || !names.add(n)) {
            return;
        }
        StringBuilder alt = new StringBuilder();
        names.stream().sorted((a, b) -> b.length() - a.length()).forEach(s -> {
            if (alt.length() > 0) {
                alt.append('|');
            }
            alt.append(Pattern.quote(s));
        });
        pattern = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + alt + ")(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** A player's entity: it carries a skin now, or core's tracker has seen one on it. */
    private boolean player(EntityUpdate u) {
        return carries(u, PlayerSkinUpdate.class) || isPlayer.test(u.networkId);
    }

    private static boolean carries(EntityUpdate u, Class<?> kind) {
        for (ComponentUpdate c : u.updates) {
            if (kind.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    /** Stars the names in the packet; whether anything was changed. */
    private boolean star(Packet packet) {
        if (packet instanceof EntityUpdates updates && updates.updates != null) {
            boolean changed = false;
            for (EntityUpdate u : updates.updates) {
                if (u == null || u.updates == null) {
                    continue;
                }
                boolean player = player(u);
                for (ComponentUpdate c : u.updates) {
                    if (c instanceof NameplateUpdate n && n.text != null && !n.text.isEmpty()) {
                        String s = player ? stars(n.text) : star(n.text);
                        if (!s.equals(n.text)) {
                            n.text = s;
                            changed = true;
                        }
                    }
                }
            }
            return changed;
        }
        if (packet instanceof AddToServerPlayerList list && list.players != null) {
            boolean changed = false;
            for (int i = 0; i < list.players.length; i++) {
                ServerPlayerListPlayer p = list.players[i];
                if (p != null && p.username != null) {
                    String s = star(p.username);
                    if (!s.equals(p.username)) {
                        ServerPlayerListPlayer copy = new ServerPlayerListPlayer(p);
                        copy.username = s;
                        list.players[i] = copy;
                        changed = true;
                    }
                }
            }
            return changed;
        }
        if (packet instanceof ServerMessage m) {
            FormattedMessage s = star(m.message);
            if (s != m.message) {
                m.message = s;
                return true;
            }
            return false;
        }
        if (packet instanceof KillFeedMessage k) {
            FormattedMessage killer = star(k.killer);
            FormattedMessage decedent = star(k.decedent);
            boolean changed = killer != k.killer || decedent != k.decedent;
            k.killer = killer;
            k.decedent = decedent;
            return changed;
        }
        if (packet instanceof Notification n) {
            FormattedMessage message = star(n.message);
            FormattedMessage secondary = star(n.secondaryMessage);
            boolean changed = message != n.message || secondary != n.secondaryMessage;
            n.message = message;
            n.secondaryMessage = secondary;
            return changed;
        }
        if (packet instanceof CustomPage page) {
            return star(page.commands);
        }
        if (packet instanceof CustomHud hud) {
            return star(hud.commands);
        }
        if (packet instanceof UpdateWorldMap map && map.addedMarkers != null) {
            boolean changed = false;
            for (int i = 0; i < map.addedMarkers.length; i++) {
                MapMarker marker = map.addedMarkers[i];
                if (marker == null) {
                    continue;
                }
                FormattedMessage s = star(marker.name);
                if (s != marker.name) {
                    MapMarker copy = new MapMarker(marker);
                    copy.name = s;
                    map.addedMarkers[i] = copy;
                    changed = true;
                }
            }
            return changed;
        }
        return false;
    }

    /**
     * A server window's or HUD's commands: the names starred in what a label shows (a value set on
     * its {@code Text} or {@code TextSpans} - the death screen's reason among them). Nothing else: a
     * value the client sends back to the server stays as it is, or a button would stop working.
     */
    private boolean star(CustomUICommand[] commands) {
        if (commands == null) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < commands.length; i++) {
            CustomUICommand c = commands[i];
            if (c == null || c.data == null || c.selector == null
                    || !(c.selector.endsWith(".Text") || c.selector.endsWith(".TextSpans"))) {
                continue;
            }
            String s = star(c.data);
            if (!s.equals(c.data)) {
                CustomUICommand copy = new CustomUICommand(c);
                copy.data = s;
                commands[i] = copy;
                changed = true;
            }
        }
        return changed;
    }

    /** The names in a text starred; the same string when there is none in it. */
    String star(String text) {
        Pattern p = pattern;
        if (text == null || p == null) {
            return text;
        }
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        do {
            m.appendReplacement(out, "*".repeat(m.group().length()));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    /** A player's nameplate: its name, starred - all of it, the name known or not yet. */
    private String stars(String nameplate) {
        String s = star(nameplate);
        return s.equals(nameplate) ? "*".repeat(nameplate.strip().length()) : s;
    }

    /** A message with the names in it starred: a copy, or the same message when there is none in it. */
    FormattedMessage star(FormattedMessage m) {
        if (m == null || pattern == null) {
            return m;
        }
        FormattedMessage copy = null;
        String raw = star(m.rawText);
        if (raw != m.rawText && !raw.equals(m.rawText)) {
            copy = new FormattedMessage(m);
            copy.rawText = raw;
        }
        if (m.children != null) {
            FormattedMessage[] kids = null;
            for (int i = 0; i < m.children.length; i++) {
                FormattedMessage k = star(m.children[i]);
                if (k != m.children[i]) {
                    if (kids == null) {
                        kids = m.children.clone();
                    }
                    kids[i] = k;
                }
            }
            if (kids != null) {
                copy = copy == null ? new FormattedMessage(m) : copy;
                copy.children = kids;
            }
        }
        if (m.params != null) {
            Map<String, ParamValue> params = null;
            for (Map.Entry<String, ParamValue> e : m.params.entrySet()) {
                if (e.getValue() instanceof StringParamValue v && v.value != null) {
                    String s = star(v.value);
                    if (!s.equals(v.value)) {
                        if (params == null) {
                            params = new HashMap<>(m.params);
                        }
                        params.put(e.getKey(), new StringParamValue(s));
                    }
                }
            }
            if (params != null) {
                copy = copy == null ? new FormattedMessage(m) : copy;
                copy.params = params;
            }
        }
        if (m.messageParams != null) {
            Map<String, FormattedMessage> params = null;
            for (Map.Entry<String, FormattedMessage> e : m.messageParams.entrySet()) {
                FormattedMessage s = star(e.getValue());
                if (s != e.getValue()) {
                    if (params == null) {
                        params = new HashMap<>(m.messageParams);
                    }
                    params.put(e.getKey(), s);
                }
            }
            if (params != null) {
                copy = copy == null ? new FormattedMessage(m) : copy;
                copy.messageParams = params;
            }
        }
        return copy == null ? m : copy;
    }
}
