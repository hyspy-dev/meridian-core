package meridian.core.impl;

import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.List;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.BuilderSelection.Box;
import meridian.protocol.packets.buildertools.BuilderToolSelectionUpdate;
import meridian.protocol.packets.buildertools.BuilderToolsEnabledTools;

/**
 * Forces the selection tool into the client's allowed list on the way down, and captures the box
 * the client drags on the way up.
 *
 * <p>Both directions in one handler because both are the one feature. The list is only ever
 * widened - the server's own allowances are kept, the selection tool is added when it is missing.
 * The selection report is read for its box and then dropped: the server gates the tool on a
 * permission and would reject a use it did not authorise, so it never hears of this one.
 */
final class BuilderSelectionHandler implements PacketHandler {

    private final BuilderSelectionImpl selection;

    BuilderSelectionHandler(BuilderSelectionImpl selection) {
        this.selection = selection;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (!selection.isToolForced() || !(packet instanceof BuilderToolsEnabledTools tools)) {
            return Action.FORWARD;
        }
        String[] ids = tools.toolIds;
        for (int i = 0; ids != null && i < ids.length; i++) {
            if (BuilderSelectionImpl.SELECTION_TOOL_ID.equals(ids[i])) {
                return Action.FORWARD;          // the server already allows it
            }
        }
        List<String> widened = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                widened.add(id);
            }
        }
        widened.add(BuilderSelectionImpl.SELECTION_TOOL_ID);
        tools.toolIds = widened.toArray(new String[0]);
        return Action.MODIFIED;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (!selection.isToolForced() || !(packet instanceof BuilderToolSelectionUpdate u)) {
            return Action.FORWARD;
        }
        int xMin = Math.min(u.xMin, u.xMax);
        int yMin = Math.min(u.yMin, u.yMax);
        int zMin = Math.min(u.zMin, u.zMax);
        int xMax = Math.max(u.xMin, u.xMax);
        int yMax = Math.max(u.yMin, u.yMax);
        int zMax = Math.max(u.zMin, u.zMax);
        selection.report(new Box(xMin, yMin, zMin, xMax, yMax, zMax));
        return Action.DROP;                     // captured; the server must not see the tool used
    }
}
