package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.HashMap;
import java.util.Map;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.Containers;
import meridian.core.api.PlayerState;
import meridian.core.api.Vec3;
import meridian.protocol.EntityStatUpdate;
import meridian.protocol.EntityStatsUpdate;
import meridian.protocol.InventorySection;
import meridian.protocol.ItemWithAllMetadata;
import meridian.protocol.NameplateUpdate;
import meridian.protocol.TransformUpdate;
import meridian.protocol.packets.assets.UpdateEntityStatTypes;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.inventory.SetActiveSlot;
import meridian.protocol.packets.inventory.UpdatePlayerInventory;
import meridian.protocol.packets.connection.Connect;
import meridian.protocol.packets.player.SetClientId;

/**
 * Feeds {@link PlayerStateImpl} everything the server says about the player themselves.
 *
 * <p>The player is an entity like any other, so their health and their position arrive in the same
 * stream as everyone else's - told apart by the id the server hands out at login. Their inventory
 * arrives on its own, because only they may see it.
 *
 * <p>Their <em>walking</em> does not arrive here at all. The server has no reason to tell the
 * client where the client is going, so the transforms seen here are the ones it does send - a
 * spawn, a teleport, a world change - and the running position comes from the movement the client
 * sends out, which {@link PlayerStateImpl} reads from the entity tracker.
 */
final class PlayerStateHandler implements PacketHandler {

    /** The panels, in the order the inventory packet carries them. */
    private static final String STORAGE = "Storage";
    private static final String ARMOR = "Armor";
    private static final String HOTBAR = "Hotbar";
    private static final String UTILITY = "Utility";
    private static final String TOOLS = "Tools";
    private static final String BACKPACK = "Backpack";
    private static final String ABILITY_SLOTS = "AbilitySlots";
    private static final String RUNE_BAG = "RuneBag";

    private final PlayerStateImpl state;
    private int localId = Integer.MIN_VALUE;

    PlayerStateHandler(PlayerStateImpl state) {
        this.state = state;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SetClientId id) {
            localId = id.clientId;
        } else if (packet instanceof UpdateEntityStatTypes types) {
            state.onStatTypes(names(types));
        } else if (packet instanceof UpdatePlayerInventory inventory) {
            panels(inventory);
        } else if (packet instanceof SetActiveSlot slot) {
            activeSlot(slot);
        } else if (packet instanceof EntityUpdates updates) {
            ours(updates);
        }
        return Action.FORWARD;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SetActiveSlot slot) {
            activeSlot(slot);
        } else if (packet instanceof Connect connect && connect.identityToken != null) {
            // The client proves who it is on the way in, and the proof says so.
            identity(connect.identityToken);
        }
        return Action.FORWARD;
    }

    /**
     * Reads who the player is out of the identity token they log in with.
     *
     * <p>The token is a signed statement by Hytale's own service that this is who they say they
     * are; the server checks that signature and we do not - we are only reading the name on a
     * document that is being presented to somebody else. Its subject is the player's id and its
     * {@code username} claim is their name.
     */
    private void identity(String token) {
        String[] parts = token.split("[.]");
        if (parts.length < 2) {
            return;
        }
        try {
            String json = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            var claims = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String subject = claims.has("sub") ? claims.get("sub").getAsString() : null;
            String username = claims.has("username") ? claims.get("username").getAsString() : null;
            state.onIdentity(subject == null ? null : java.util.UUID.fromString(subject), username);
        } catch (RuntimeException e) {
            // A token we cannot read tells us nothing; the other ways still stand.
        }
    }

    // ------------------------------------------------------------------

    /** Only the entity the server said is ours has anything to say about the player. */
    private void ours(EntityUpdates updates) {
        if (updates.updates == null || localId == Integer.MIN_VALUE) {
            return;
        }
        for (var update : updates.updates) {
            if (update == null || update.networkId != localId || update.updates == null) {
                continue;
            }
            for (var component : update.updates) {
                if (component instanceof EntityStatsUpdate stats) {
                    stats(stats);
                } else if (component instanceof NameplateUpdate nameplate) {
                    state.onName(nameplate.text);
                } else if (component instanceof TransformUpdate transform) {
                    transform(transform);
                }
            }
        }
    }

    private void stats(EntityStatsUpdate update) {
        update.entityStatUpdates.forEach((statId, changes) -> {
            if (changes == null) {
                return;
            }
            for (EntityStatUpdate change : changes) {
                // The last word wins: the server sends the value it wants us to show.
                if (change != null) {
                    state.onStat(statId, change.value);
                }
            }
        });
    }

    private void transform(TransformUpdate update) {
        var model = update.transform;
        if (model == null) {
            return;
        }
        Vec3 where = model.position == null ? null
                : new Vec3(model.position.x, model.position.y, model.position.z);
        var look = model.lookOrientation != null ? model.lookOrientation : model.bodyOrientation;
        Vec3 facing = look == null ? null : new Vec3(look.pitch, look.yaw, look.roll);
        state.onTransform(where, facing);
    }

    private void panels(UpdatePlayerInventory inventory) {
        section(STORAGE, inventory.storage);
        section(ARMOR, inventory.armor);
        section(HOTBAR, inventory.hotbar);
        section(UTILITY, inventory.utility);
        section(TOOLS, inventory.tools);
        section(BACKPACK, inventory.backpack);
        section(ABILITY_SLOTS, inventory.abilitySlots);
        section(RUNE_BAG, inventory.runeBag);
    }

    private void activeSlot(SetActiveSlot slot) {
        String panel = panel(slot.inventorySectionId);
        if (panel != null) {
            state.onActiveSlot(panel, slot.activeSlot);
        }
    }

    private void section(String panel, InventorySection section) {
        if (section == null) {
            return;
        }
        Map<Integer, Containers.Item> items = new HashMap<>();
        if (section.items != null) {
            section.items.forEach((slot, stack) -> {
                if (stack != null && stack.itemId != null && !stack.itemId.isEmpty()) {
                    items.put(slot, item(stack));
                }
            });
        }
        state.onSection(panel, new PlayerState.Section(section.capacity, items, -1));
    }

    private static Containers.Item item(ItemWithAllMetadata stack) {
        // Quality arrived with a later build; here every stack is the plain one.
        return new Containers.Item(stack.itemId, stack.quantity, stack.durability,
                stack.maxDurability, 0, stack.metadata);
    }

    /**
     * Which panel a section id belongs to. The ids are the server's own
     * ({@code InventoryComponent.*_SECTION_ID}), the ones {@code SetActiveSlot} carries in both
     * directions; an id this build does not know maps to nothing.
     */
    private static String panel(int sectionId) {
        return switch (sectionId) {
            case -1 -> HOTBAR;
            case -2 -> STORAGE;
            case -3 -> ARMOR;
            case -5 -> UTILITY;
            case -8 -> TOOLS;
            case -9 -> BACKPACK;
            case -11 -> ABILITY_SLOTS;
            case -12 -> RUNE_BAG;
            default -> null;
        };
    }

    private static Map<Integer, String> names(UpdateEntityStatTypes types) {
        Map<Integer, String> out = new HashMap<>();
        if (types.types != null) {
            types.types.forEach((id, type) -> {
                if (type != null && type.id != null) {
                    out.put(id, type.id);
                }
            });
        }
        return out;
    }
}
