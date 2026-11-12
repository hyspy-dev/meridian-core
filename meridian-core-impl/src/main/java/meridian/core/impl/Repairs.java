package meridian.core.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.Containers;
import meridian.core.api.InteractionControl;
import meridian.core.api.PlayerState;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.interface_.CustomPageEvent;
import meridian.protocol.packets.interface_.CustomPageEventType;
import meridian.protocol.packets.interface_.CustomUICommand;
import meridian.protocol.packets.interface_.CustomUICommandType;
import meridian.protocol.packets.interface_.CustomUIEventBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A repair as a player makes it ({@link InteractionControl#repair}): a repair kit from the hotbar is
 * used, the server answers with its list of worn items ({@code ItemRepairPage}: one button per item,
 * most worn first), and the button of the item is pressed - the server uses up a kit and gives the
 * item its durability back.
 *
 * <p>The server takes a page's button only once the client has acknowledged the page
 * ({@code PageManager}: a data event while an acknowledgement is owed is dropped), so the press waits
 * for the acknowledgement to go out - the game's own, or the one core gives for a headless client.
 */
final class Repairs {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** The document the server builds its repair list from. */
    static final String PAGE = "Pages/ItemRepairPage.ui";
    /** The set every repair kit belongs to ({@code Tool_Repair_Kit_Crude}, inherited by the others). */
    static final String KIT_SET = "Repair_Kit";
    /** How long the server's list and its answer are waited for. */
    static final long WAIT_MS = 4000;
    private static final Pattern ICON = Pattern.compile("#ElementList\\[(\\d+)\\] #Icon\\.ItemId");
    private static final Pattern BUTTON = Pattern.compile("#ElementList\\[(\\d+)\\]");

    private final SessionHolder session;
    private final InventoryTracker inventory;
    private final ItemRegistry items;
    private final PlayerState player;
    private volatile InteractionControl hands;
    /** The repair list a repair under way waits for; {@code null} when none does. */
    private volatile CompletableFuture<CustomPage> listed;
    /** The acknowledgement of the list going out to the server. */
    private volatile CompletableFuture<Void> acknowledged;

    Repairs(SessionHolder session, InventoryTracker inventory, ItemRegistry items, PlayerState player) {
        this.session = session;
        this.inventory = inventory;
        this.items = items;
        this.player = player;
    }

    void hands(InteractionControl hands) {
        this.hands = hands;
    }

    /** Repairs the most worn {@code itemId} the player carries with a kit from the hotbar. */
    synchronized CompletableFuture<Boolean> repair(String itemId) {
        InteractionControl h = hands;
        ProxySession live = session.get().orElse(null);
        if (h == null || live == null || itemId == null || itemId.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        int kit = kitSlot();
        double worn = worst(itemId);
        if (kit < 0 || !(worn < 1)) {
            log.info("meridian-core: repair of {} - {}", itemId, kit < 0 ? "no repair kit in the hotbar" : "nothing worn");
            return CompletableFuture.completedFuture(false);
        }
        int back = inventory.activeHotbarSlot();
        CompletableFuture<CustomPage> page = new CompletableFuture<>();
        listed = page;
        acknowledged = new CompletableFuture<>();
        CompletableFuture<Boolean> done = h.switchHotbarSlot(kit)
                .thenCompose(o -> h.useItem())
                .thenCompose(o -> page.orTimeout(WAIT_MS, TimeUnit.MILLISECONDS))
                .thenCompose(p -> press(live, p, itemId))
                .thenCompose(pressed -> pressed ? repaired(itemId, worn) : CompletableFuture.completedFuture(false))
                .exceptionally(e -> {
                    log.info("meridian-core: repair of {} did not happen: {}", itemId, e.toString());
                    return false;
                });
        return done.thenCompose(ok -> {
            listed = null;
            if (back == kit || back < 0) {
                return CompletableFuture.completedFuture(ok);
            }
            return h.switchHotbarSlot(back).handle((o, e) -> ok);
        });
    }

    /**
     * Presses the list's button for the item, once the list has been acknowledged; {@code false}
     * when the list has none for it - then the list is dismissed, as a player closes it.
     */
    private CompletableFuture<Boolean> press(ProxySession live, CustomPage page, String itemId) {
        int index = elementOf(page, itemId);
        String data = index < 0 ? null : buttonData(page, index);
        CompletableFuture<Void> ack = acknowledged;
        CompletableFuture<Void> ready = (ack == null ? CompletableFuture.<Void>completedFuture(null) : ack)
                .orTimeout(WAIT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(e -> null)
                .thenCompose(v -> CompletableFuture.runAsync(() -> { },
                        CompletableFuture.delayedExecutor(60, TimeUnit.MILLISECONDS)));
        return ready.thenApply(v -> {
            if (data == null) {
                log.info("meridian-core: the repair list has no {} - closing it", itemId);
                live.sendToServer(new CustomPageEvent(CustomPageEventType.Dismiss, null));
                return false;
            }
            log.info("meridian-core: repair - pressing {} for {}", data, itemId);
            live.sendToServer(new CustomPageEvent(CustomPageEventType.Data, data));
            return true;
        });
    }

    /** Done once the item is less worn than it was, or after a while without. */
    private CompletableFuture<Boolean> repaired(String itemId, double before) {
        CompletableFuture<Boolean> out = new CompletableFuture<>();
        long until = System.currentTimeMillis() + WAIT_MS;
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            double now = worst(itemId);
            if (now > before + 1e-6) {
                out.complete(true);
            } else if (System.currentTimeMillis() > until) {
                out.complete(false);
            } else {
                CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(poll[0]);
            }
        };
        poll[0].run();
        return out;
    }

    /** The hotbar slot of a repair kit, or {@code -1}. */
    private int kitSlot() {
        for (Map.Entry<Integer, String> e : inventory.hotbarItems().entrySet()) {
            String id = e.getValue();
            meridian.protocol.ItemBase item = items.item(id).orElse(null);
            if ((item != null && KIT_SET.equals(item.set)) || id.contains("Repair_Kit")) {
                return e.getKey();
            }
        }
        return -1;
    }

    /**
     * How worn the most worn {@code itemId} carried in the hotbar or the utility slot is - its
     * durability over its maximum; {@code 1} when none is worn (what the server's list takes).
     */
    private double worst(String itemId) {
        double worst = 1;
        for (String section : new String[] {"Hotbar", "Utility"}) {
            PlayerState.Section s = player.inventories().get(section);
            if (s == null) {
                continue;
            }
            for (Containers.Item i : s.items().values()) {
                if (i != null && itemId.equals(i.id()) && i.maxDurability() > 0) {
                    worst = Math.min(worst, i.durability() / i.maxDurability());
                }
            }
        }
        return worst;
    }

    /** The list's element whose icon is the item - the first, the most worn: the server sorts it so. */
    static int elementOf(CustomPage page, String itemId) {
        if (page.commands == null) {
            return -1;
        }
        int best = -1;
        for (CustomUICommand c : page.commands) {
            if (c == null || c.type != CustomUICommandType.Set || c.selector == null) {
                continue;
            }
            Matcher m = ICON.matcher(c.selector);
            if (m.matches() && itemId.equals(value(c.data))) {
                int i = Integer.parseInt(m.group(1));
                if (best < 0 || i < best) {
                    best = i;
                }
            }
        }
        return best;
    }

    /** What the element's button sends when pressed: its binding's data ({@code {"Index":"0"}}). */
    static String buttonData(CustomPage page, int index) {
        if (page.eventBindings != null) {
            for (CustomUIEventBinding b : page.eventBindings) {
                if (b != null && b.selector != null) {
                    Matcher m = BUTTON.matcher(b.selector);
                    if (m.matches() && Integer.parseInt(m.group(1)) == index && b.data != null) {
                        return b.data;
                    }
                }
            }
        }
        return "{\"Index\":\"" + index + "\"}";
    }

    /** A set command's value: {@code {"0":"Weapon_Sword_Iron"}} is {@code Weapon_Sword_Iron}. */
    private static String value(String data) {
        if (data == null) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(data);
            if (root.isJsonObject() && root.getAsJsonObject().has("0")) {
                JsonElement v = root.getAsJsonObject().get("0");
                return v.isJsonPrimitive() ? v.getAsString() : null;
            }
        } catch (RuntimeException ignored) {
            // not a value
        }
        return null;
    }

    /** Whether a page is the server's repair list. */
    static boolean isRepairList(CustomPage page) {
        if (page.commands == null) {
            return false;
        }
        for (CustomUICommand c : page.commands) {
            if (c != null && c.type == CustomUICommandType.Append && PAGE.equals(c.text)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // From the handler
    // ------------------------------------------------------------------

    void onServerPage(CustomPage page, boolean headless) {
        CompletableFuture<CustomPage> waiting = listed;
        if (waiting != null && !waiting.isDone() && isRepairList(page)) {
            waiting.complete(page);
            CompletableFuture<Void> ack = acknowledged;
            if (headless && ack != null) {
                // Core answers a headless client's pages itself, as this one arrives.
                ack.complete(null);
            }
        }
    }

    void onAcknowledge() {
        CompletableFuture<CustomPage> waiting = listed;
        CompletableFuture<Void> ack = acknowledged;
        if (waiting != null && waiting.isDone() && ack != null) {
            ack.complete(null);
        }
    }

    /** Watches the server's pages and the acknowledgements that go out for them. */
    PacketHandler handler() {
        return new PacketHandler() {
            @Override
            public Action handleS2C(io.netty.channel.ChannelHandlerContext ctx, Packet packet, ProxySession s) {
                if (packet instanceof CustomPage page) {
                    onServerPage(page, HeadlessClientHandler.headlessOf(s) != null);
                }
                return Action.FORWARD;
            }

            @Override
            public Action handleC2S(io.netty.channel.ChannelHandlerContext ctx, Packet packet, ProxySession s) {
                if (packet instanceof CustomPageEvent e && e.type == CustomPageEventType.Acknowledge) {
                    onAcknowledge();
                }
                return Action.FORWARD;
            }
        };
    }
}
