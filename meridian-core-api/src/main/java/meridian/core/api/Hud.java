package meridian.core.api;

/**
 * Draws on the client: overlays, panels, whole pages.
 *
 * <p>The client renders documents in its own markup and is driven by small commands — append a
 * document, insert one before another, set a property, remove a node. This service is that
 * vocabulary without the packets, so a module can build an interface without knowing the
 * protocol, and keep working when the protocol changes.
 *
 * <p>Two ways to update: {@link Batch#show} replaces a HUD's contents, {@link Batch#patch}
 * applies the batch as a diff to what is already there. Patching is what a live overlay does
 * every frame — sending the whole tree each time would be both slower and visibly jumpy.
 *
 * <pre>{@code
 * Hud hud = ctx.services().require(Hud.class);
 * hud.batch()
 *    .appendInline("<Panel Id=\"MyRoot\" Anchor=\"(Top: 8, Left: 8, Width: 200, Height: 60)\"/>")
 *    .show("my.overlay", 100);
 *
 * hud.batch().set("#MyRoot.Visible", false).patch("my.overlay", 100);
 * }</pre>
 */
public interface Hud {

    /** Starts a batch of commands, sent as one update by {@code show} or {@code patch}. */
    Batch batch();

    /** Removes a HUD from the client entirely. */
    void hide(String hudId);

    /** A sequence of UI commands. Every method returns {@code this} so calls chain. */
    interface Batch {

        /** Appends a {@code .ui} document the client loads by path, at the root. */
        Batch append(String documentPath);

        /** Appends a {@code .ui} document the client loads by path, inside {@code selector}. */
        Batch appendTo(String selector, String documentPath);

        /**
         * Appends inline markup at the root. Inline documents have no source location on the
         * client, so relative {@code TexturePath} references inside them do not resolve — push
         * the image as an asset and reference it by the name {@link ClientAssets} returns.
         */
        Batch appendInline(String markup);

        /** Appends inline markup inside {@code selector}. */
        Batch appendInlineTo(String selector, String markup);

        /** Inserts a {@code .ui} document immediately before {@code selector}. */
        Batch insertBefore(String selector, String documentPath);

        /** Inserts inline markup immediately before {@code selector}. */
        Batch insertBeforeInline(String selector, String markup);

        /** Sets a property, e.g. {@code set("#Marker.Visible", false)}. */
        Batch set(String selector, String value);

        Batch set(String selector, int value);

        Batch set(String selector, boolean value);

        /**
         * Sets a property whose value is a structure rather than a scalar, from raw JSON —
         * {@code setRaw("#Root.Anchor", "{\"Top\":8,\"Right\":8,\"Width\":200}")}.
         *
         * <p>Structures have to be assigned whole: the client does not resolve a sub-property
         * selector like {@code Anchor.Left}, so moving one edge means sending the whole anchor.
         * Fields left out are "not set", which is how an element pins to a corner — give a
         * {@code Right} and no {@code Left}, not both.
         */
        Batch setRaw(String selector, String json);

        /** Removes the node a selector points at. */
        Batch remove(String selector);

        /** Empties the HUD without removing it. */
        Batch clear();

        /** Sends the batch as the HUD's whole contents, replacing what was there. */
        void show(String hudId, int zOrder);

        /** Sends the batch as a diff against what the client already has. */
        void patch(String hudId, int zOrder);
    }
}
