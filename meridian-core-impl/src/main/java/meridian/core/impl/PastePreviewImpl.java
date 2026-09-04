package meridian.core.impl;

import java.util.List;
import meridian.api.session.ProxySession;
import meridian.core.api.PastePreview;
import meridian.protocol.packets.interface_.BlockChange;
import meridian.protocol.packets.player.HideTriggerVolumePastePrefabPreview;
import meridian.protocol.packets.player.ShowTriggerVolumePastePrefabPreview;
import org.joml.Vector3f;

/**
 * The paste preview, forged for the one client on the Default channel.
 *
 * <p>Core owns the packets; a module hands over blocks and an anchor and never sees a packet of
 * it. Same both-line packets the game has always had, so this is offered on every line.
 */
final class PastePreviewImpl implements PastePreview {

    /** A muted green and a plain water blue - the overlay's own colours, not anything a caller sets. */
    private static final int BIOME_TINT = 0x5B9E28;
    private static final int WATER_TINT = 0x3F76E4;

    private final SessionHolder session;

    PastePreviewImpl(SessionHolder session) {
        this.session = session;
    }

    @Override
    public void show(float anchorX, float anchorY, float anchorZ, List<Change> blocks) {
        ProxySession live = session.get().orElse(null);
        if (live == null) {
            return;
        }
        BlockChange[] changes = new BlockChange[blocks.size()];
        for (int i = 0; i < changes.length; i++) {
            Change c = blocks.get(i);
            changes[i] = new BlockChange(c.dx(), c.dy(), c.dz(), c.blockId(), (byte) c.rotation());
        }
        ShowTriggerVolumePastePrefabPreview pkt = new ShowTriggerVolumePastePrefabPreview();
        pkt.position = new Vector3f(anchorX, anchorY, anchorZ);
        pkt.blocksChange = changes;
        pkt.biomeTint = BIOME_TINT;
        pkt.waterTint = WATER_TINT;
        live.sendToClient(pkt);
    }

    @Override
    public void hide() {
        session.get().ifPresent(live -> live.sendToClient(new HideTriggerVolumePastePrefabPreview()));
    }

    @Override
    public boolean isAvailable() {
        return session.get().isPresent();
    }
}
