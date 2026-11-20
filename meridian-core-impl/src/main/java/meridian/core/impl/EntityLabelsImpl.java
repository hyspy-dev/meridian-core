package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import meridian.api.module.Scheduler;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.EntityLabels;
import meridian.protocol.Color;
import meridian.protocol.CombatTextEntityUIAnimationEventType;
import meridian.protocol.CombatTextEntityUIComponentAnimationEvent;
import meridian.protocol.CombatTextUpdate;
import meridian.protocol.ComponentUpdate;
import meridian.protocol.EntityUIComponent;
import meridian.protocol.EntityUIType;
import meridian.protocol.EntityUpdate;
import meridian.protocol.RangeVector2f;
import meridian.protocol.Rangef;
import meridian.protocol.UIComponentsUpdate;
import meridian.protocol.UpdateType;
import meridian.protocol.packets.assets.UpdateEntityUIComponents;
import meridian.protocol.packets.entities.EntityUpdates;
import org.joml.Vector2f;

/**
 * Live {@link EntityLabels}: a pop-up text component of core's own, hung on the entities that are to
 * carry a label and fired at them twice a second.
 *
 * <p>The game draws a pop-up text by the first such component in the entity's list - a mob's own
 * floats up and fades - so while a label is up, the server's own pop-up components are taken out of
 * the entity's list and ours is put in; {@link #hide} gives the server's list back. Ours has no motion,
 * lives a little longer than the period and fades in over its first quarter and out over its last, so
 * one text crosses into the next with no gap.
 *
 * <p>It reads the server's component table to take an index nobody uses (again after every new table,
 * which a join brings), and each entity's own list as the server last sent it. Forged packets pass the
 * readers too: ours are told apart - the table by identity, a list by carrying our index.
 */
final class EntityLabelsImpl implements EntityLabels, PacketHandler {

    private static final long PERIOD_MS = 500;
    /** Longer than the period: each text fades in over the one before as it fades out. */
    private static final float TEXT_SECONDS = 0.8f;
    private static final float HEIGHT_PX = -90f;

    private record Label(String text, int rgb) {
    }

    private final SessionHolder sessions;

    private volatile int maxComponent = -1;
    /** The table's size as the server gives it with every update (this line's packets carry it). */
    private volatile int serverMaxId;
    /** The server's own pop-up text components. */
    private final Set<Integer> serverTexts = ConcurrentHashMap.newKeySet();
    /** Each entity's UI list as the server last sent it. */
    private final Map<Integer, int[]> serverLists = new ConcurrentHashMap<>();

    private final Map<Integer, Label> labels = new ConcurrentHashMap<>();
    /** The list we last set on each entity: set again only when it is to change. */
    private final Map<Integer, List<Integer>> sentLists = new ConcurrentHashMap<>();
    /** The tables we sent, told apart from the server's as they pass the reader. */
    private final Set<Object> ours = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private volatile int textIndex = -1;
    /** Whether the game has our component in its table now. */
    private volatile boolean defined;
    private volatile float fontSize = 18f;

    EntityLabelsImpl(SessionHolder sessions, Scheduler scheduler) {
        this.sessions = sessions;
        scheduler.scheduleAtFixedRate(this::tick, Duration.ofMillis(PERIOD_MS), Duration.ofMillis(PERIOD_MS));
    }

    // --- EntityLabels ------------------------------------------------------------------------------

    @Override
    public boolean available() {
        return sessions.get().isPresent();
    }

    @Override
    public void show(int entityId, String text, int rgb) {
        labels.put(entityId, new Label(text == null ? "" : text, rgb & 0xFFFFFF));
    }

    @Override
    public void hide(int entityId) {
        labels.remove(entityId);
        if (sentLists.remove(entityId) != null) {
            ProxySession s = sessions.get().orElse(null);
            if (s != null) {
                s.sendToClient(new EntityUpdates(null, new EntityUpdate[] {restore(entityId)}));
            }
        }
    }

    @Override
    public void clear() {
        labels.clear();
        List<EntityUpdate> back = new ArrayList<>();
        for (int id : List.copyOf(sentLists.keySet())) {
            sentLists.remove(id);
            back.add(restore(id));
        }
        ProxySession s = sessions.get().orElse(null);
        if (s != null && !back.isEmpty()) {
            s.sendToClient(new EntityUpdates(null, back.toArray(new EntityUpdate[0])));
        }
    }

    @Override
    public void fontSize(float size) {
        float f = Math.max(6f, Math.min(96f, size));
        if (f != fontSize) {
            fontSize = f;
            defined = false;
        }
    }

    // --- what the server says ------------------------------------------------------------------------

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateEntityUIComponents ui && ui.components != null && !ours.remove(packet)) {
            table(ui);
        } else if (packet instanceof EntityUpdates updates) {
            if (updates.removed != null) {
                for (int id : updates.removed) {
                    serverLists.remove(id);
                    labels.remove(id);
                    sentLists.remove(id);
                }
            }
            if (updates.updates != null) {
                for (EntityUpdate u : updates.updates) {
                    if (u.updates == null) {
                        continue;
                    }
                    for (ComponentUpdate c : u.updates) {
                        if (c instanceof UIComponentsUpdate list && !contains(list.components, textIndex)) {
                            // the server's: ours to be set again over it
                            serverLists.put(u.networkId, list.components == null ? new int[0] : list.components);
                            sentLists.remove(u.networkId);
                        }
                    }
                }
            }
        }
        return Action.FORWARD;
    }

    private void table(UpdateEntityUIComponents ui) {
        if (ui.type == UpdateType.Init) {
            // a new table: ours is gone from the game's, and an index is to be chosen again
            maxComponent = -1;
            serverMaxId = 0;
            serverTexts.clear();
            defined = false;
            textIndex = -1;
            sentLists.clear();
        }
        serverMaxId = Math.max(serverMaxId, ui.maxId);
        for (Map.Entry<Integer, EntityUIComponent> e : ui.components.entrySet()) {
            int index = e.getKey();
            if (ui.type == UpdateType.Remove) {
                serverTexts.remove(index);
                continue;
            }
            maxComponent = Math.max(maxComponent, index);
            if (index == textIndex) {
                // the server has taken our index: choose another
                defined = false;
                textIndex = -1;
            }
            if (e.getValue() != null && e.getValue().type == EntityUIType.CombatText) {
                serverTexts.add(index);
            } else {
                serverTexts.remove(index);
            }
        }
    }

    // --- the labels, twice a second -------------------------------------------------------------------

    private void tick() {
        try {
            if (labels.isEmpty()) {
                return;
            }
            ProxySession s = sessions.get().orElse(null);
            if (s == null) {
                return;
            }
            if (!defined) {
                define(s);
            }
            List<EntityUpdate> out = new ArrayList<>();
            for (Map.Entry<Integer, Label> e : labels.entrySet()) {
                int id = e.getKey();
                Label label = e.getValue();
                Set<Integer> list = new LinkedHashSet<>();
                for (int i : serverLists.getOrDefault(id, new int[0])) {
                    if (!serverTexts.contains(i)) {
                        list.add(i);
                    }
                }
                list.add(textIndex);
                List<Integer> wanted = List.copyOf(list);
                CombatTextUpdate text = new CombatTextUpdate(0f, label.text()) /* no text colour before 0.7 */;
                if (!wanted.equals(sentLists.get(id))) {
                    sentLists.put(id, wanted);
                    out.add(new EntityUpdate(id, null, new ComponentUpdate[] {
                            new UIComponentsUpdate(wanted.stream().mapToInt(Integer::intValue).toArray()), text}));
                } else {
                    out.add(new EntityUpdate(id, null, new ComponentUpdate[] {text}));
                }
            }
            if (!out.isEmpty()) {
                s.sendToClient(new EntityUpdates(null, out.toArray(new EntityUpdate[0])));
            }
        } catch (RuntimeException e) {
            // a bad tick waits for the next one
        }
    }

    /** Our component into the game's table: at a free index, or the same one with new letters. */
    private void define(ProxySession s) {
        if (textIndex < 0) {
            textIndex = freeIndex();
            sentLists.clear();
        }
        EntityUIComponent text = new EntityUIComponent(EntityUIType.CombatText, new Vector2f(0, HEIGHT_PX), false,
                0, new RangeVector2f(new Rangef(0, 0), new Rangef(0, 0)), 100f, TEXT_SECONDS, 0f, fontSize,
                new Color((byte) -1, (byte) -1, (byte) -1), crossFade());
        Map<Integer, EntityUIComponent> one = new HashMap<>();
        one.put(textIndex, text);
        UpdateEntityUIComponents packet = table(one);
        ours.add(packet);
        s.sendToClient(packet);
        defined = true;
    }

    private int freeIndex() {
        return Math.max(maxComponent + 1, serverMaxId);
    }

    private UpdateEntityUIComponents table(Map<Integer, EntityUIComponent> components) {
        return new UpdateEntityUIComponents(UpdateType.AddOrUpdate, Math.max(serverMaxId, textIndex + 1), components);
    }

    private EntityUpdate restore(int id) {
        return new EntityUpdate(id, null, new ComponentUpdate[] {
                new UIComponentsUpdate(serverLists.getOrDefault(id, new int[0]))});
    }

    private static boolean contains(int[] list, int index) {
        if (list == null || index < 0) {
            return false;
        }
        for (int i : list) {
            if (i == index) {
                return true;
            }
        }
        return false;
    }

    private static Color color(int rgb) {
        return new Color((byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb);
    }

    /** In over the first quarter, out over the last: a text and the one after it cross. */
    private static CombatTextEntityUIComponentAnimationEvent[] crossFade() {
        CombatTextEntityUIComponentAnimationEvent in = new CombatTextEntityUIComponentAnimationEvent();
        in.type = CombatTextEntityUIAnimationEventType.Opacity;
        in.startAt = 0f;
        in.endAt = 0.25f;
        in.startOpacity = 0f;
        in.endOpacity = 1f;
        CombatTextEntityUIComponentAnimationEvent out = new CombatTextEntityUIComponentAnimationEvent();
        out.type = CombatTextEntityUIAnimationEventType.Opacity;
        out.startAt = 0.75f;
        out.endAt = 1f;
        out.startOpacity = 1f;
        out.endOpacity = 0f;
        return new CombatTextEntityUIComponentAnimationEvent[] {in, out};
    }

    // --- the command channel: core labels ... ---------------------------------------------------------

    /** {@code show <id> <rrggbb> <text...>} | {@code hide <id>} | {@code clear} | {@code font <n>} | the state. */
    Object command(List<String> args) {
        String what = args.size() >= 2 ? args.get(1).toLowerCase(java.util.Locale.ROOT) : "";
        switch (what) {
            case "show" -> show(Integer.parseInt(args.get(2)), String.join(" ", args.subList(4, args.size())),
                    Integer.parseInt(args.get(3), 16));
            case "hide" -> hide(Integer.parseInt(args.get(2)));
            case "clear" -> clear();
            case "font" -> fontSize(Float.parseFloat(args.get(2)));
            default -> {
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("labels", labels.size());
        out.put("set", sentLists.size());
        out.put("textIndex", textIndex);
        out.put("defined", defined);
        out.put("serverMaxComponent", maxComponent);
        out.put("serverTexts", new java.util.TreeSet<>(serverTexts));
        out.put("font", fontSize);
        return out;
    }
}
