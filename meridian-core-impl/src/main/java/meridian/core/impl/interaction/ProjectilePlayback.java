package meridian.core.impl.interaction;

import meridian.protocol.InteractionSyncData;
import meridian.protocol.ProjectileInteraction;
import meridian.protocol.packets.interaction.SyncInteractionChains;

/** Connects a live interaction walk to its predicted projectiles, once per operation. */
public interface ProjectilePlayback {
    /** The world's current time scale, as received from the server. */
    default double timeDilation() { return 1; }

    /** Fills the launch snapshot and prediction UUID on the operation's first tick. */
    void prepare(ProjectileInteraction node, InteractionSyncData data, double chargeSeconds);

    /** Starts flight only after the packet containing its launch has gone to the server. */
    void sent(SyncInteractionChains packet);
}
