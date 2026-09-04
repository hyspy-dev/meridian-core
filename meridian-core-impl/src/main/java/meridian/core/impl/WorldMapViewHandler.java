package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.MapChunk;
import meridian.protocol.packets.worldmap.TeleportToWorldMapPosition;
import meridian.protocol.packets.worldmap.UpdateWorldMap;
import meridian.protocol.packets.worldmap.UpdateWorldMapSettings;

/**
 * Lets {@link WorldMapViewImpl} edit the server's map updates on their way to the client.
 *
 * <p>NORMAL, not MONITOR: this one mutates. It records what the client is being given and
 * removes an unload for a tile the view wants kept — the mechanism that stops explored ground
 * from fading out behind the player. A packet left with nothing to say (its only content was a
 * suppressed unload, and it carries no markers) is dropped rather than sent empty.
 *
 * <p>It also owns what the map is <em>allowed</em> to be: the settings the server sends about it
 * on the way down, and the teleport the player asks for on the way up. Both are the vanilla map
 * speaking, so both belong to core rather than to whichever module happens to want them - a
 * module that reached for these packets itself would be bound to one protocol build for the sake
 * of two fields.
 */
final class WorldMapViewHandler implements PacketHandler {

    private final WorldMapViewImpl view;

    WorldMapViewHandler(WorldMapViewImpl view) {
        this.view = view;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateWorldMapSettings settings) {
            return allow(settings);
        }
        if (!(packet instanceof UpdateWorldMap m) || m.chunks == null) {
            return Action.FORWARD;
        }
        MapChunk[] filtered = view.filterServerUpdate(m.chunks);
        if (filtered == m.chunks) {
            return Action.FORWARD;      // untouched: the server's own bytes go on unchanged
        }
        if (filtered == null && m.addedMarkers == null && m.removedMarkers == null) {
            return Action.DROP;
        }
        m.chunks = filtered;
        // MODIFIED, not FORWARD: the router re-serialises only what a handler says it changed,
        // so a mutated packet forwarded as FORWARD reaches the client as it was.
        return Action.MODIFIED;
    }

    /**
     * Tells the client what its map may do, where somebody here wants more than the server gave.
     *
     * <p>The client draws the map, and the teleport control on it, from these two flags alone -
     * the server sets them from its own config and the player permissions. Neither is turned off
     * here: this only ever adds.
     */
    private Action allow(UpdateWorldMapSettings settings) {
        boolean changed = false;
        if (view.isForcedOn() && !settings.enabled) {
            settings.enabled = true;
            changed = true;
        }
        if (view.teleportOffered() && !settings.allowTeleportToCoordinates) {
            settings.allowTeleportToCoordinates = true;
            changed = true;
        }
        return changed ? Action.MODIFIED : Action.FORWARD;
    }

    /**
     * The player has asked the map to take them somewhere.
     *
     * <p>Dropped once somebody here has dealt with it, and for a good reason: the server gates
     * this on a permission and answers a request it did not authorise by disconnecting the
     * player. Unclaimed, it goes on untouched - a player who really does have the permission
     * keeps the server's own teleport.
     *
     * <p>The packet carries the two map axes: its {@code x} and {@code y} are the world's X and
     * Z, because the map is looked at from above.
     */
    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof TeleportToWorldMapPosition tp
                && view.teleportAsked(tp.x, tp.y)) {
            return Action.DROP;
        }
        return Action.FORWARD;
    }
}
