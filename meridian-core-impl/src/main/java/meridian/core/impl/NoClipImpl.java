package meridian.core.impl;

import meridian.api.session.ProxySession;
import meridian.core.api.NoClip;
import meridian.protocol.packets.player.SetNoClip;

/**
 * Client-side no-clip: forges the client's own no-clip switch on the Default channel.
 *
 * <p>The server is authoritative for real no-clip and gates it on a permission, so this is the
 * client half alone - the client stops colliding, the server does not. Kept on against the server:
 * see {@link NoClipHandler}, which turns a server {@code SetNoClip(false)} back on while this wants
 * it on, the same way the map's controls are held open.
 *
 * <p>0.6 line and later - {@code SetNoClip} is not in an older protocol, and this class goes with
 * it. Absent on the 0.5.9 line, where the service is simply never provided.
 */
final class NoClipImpl implements NoClip {

    private final SessionHolder session;
    private volatile boolean enabled;

    NoClipImpl(SessionHolder session) {
        this.session = session;
    }

    @Override
    public void setEnabled(boolean on) {
        enabled = on;
        assertState();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Tells the client the current answer, if there is a client to tell. */
    void assertState() {
        ProxySession live = session.get().orElse(null);
        if (live != null) {
            live.sendToClient(new SetNoClip(enabled, false));   // false: no on-screen notification
        }
    }
}
