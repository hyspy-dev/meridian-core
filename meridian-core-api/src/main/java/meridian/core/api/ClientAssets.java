package meridian.core.api;

import java.util.Optional;

/**
 * Ships binary blobs (images, fonts, documents) to the client so UI markup can reference them.
 *
 * <p>Anything a module draws — a minimap, a map overlay, a settings page — needs its pictures
 * on the client first. The client stores them content-addressed and <b>fatally rejects</b> a
 * second push of a hash whose download already began: it disconnects. That makes pushing a
 * shared resource, not a per-module one, and this service is the single place it happens.
 *
 * <p>Pushes are therefore deduplicated by content: identical bytes yield the same reference
 * without going out twice, whichever module asks. The returned name is what markup uses
 * ({@code TexturePath}, a panel background, a command-builder argument).
 *
 * <pre>{@code
 * ClientAssets assets = ctx.services().require(ClientAssets.class);
 * String ref = assets.push(pngBytes, "png").orElse(null);   // "a_<hash>.png"
 * }</pre>
 */
public interface ClientAssets {

    /**
     * Makes {@code bytes} available on the client under {@code name}, and returns the name it
     * is actually reachable by. The name matters: the client resolves a markup reference
     * against the document's own location, so an image used from a {@code UI/Custom} document
     * has to be pushed as {@code UI/Custom/<file>@2x.png} to be found.
     *
     * <p>Content already pushed is not sent again — the client would disconnect — so the
     * returned name is the one it first arrived under, which may differ from the one asked
     * for. Reference what comes back, not what you passed in. Empty when there is no session.
     */
    Optional<String> push(String name, byte[] bytes);

    /**
     * Pushes content under a generated, content-addressed name — for blobs referenced by the
     * name they get rather than by a fixed path.
     *
     * @param extension file extension the client should see, without the dot (e.g. {@code png})
     */
    Optional<String> push(byte[] bytes, String extension);

    /**
     * Takes what stands behind a name off the client, then sends new content under it.
     *
     * <p>Do not expect the interface to notice. What a name stands for is settled the first time
     * the client resolves it, and removing the asset does not make anything already drawn look
     * again - that was measured, not assumed. Use this to take something back, not to change a
     * picture in place; there is no way to do the latter short of a whole index rebuild, and
     * that rebuild is what a player feels as a stutter.
     *
     * <p>For pictures that must simply be there and never change, hand them over during the load
     * instead: see {@link #provideAtConnect}.
     */
    Optional<String> replace(String name, byte[] bytes);

    /**
     * Takes what stands behind a name off the client, sending nothing back in its place.
     *
     * <p>Unlike {@link #replace}, this frees the name outright — the content leaves the client's
     * store and the name becomes available again for later content. Its purpose is to keep the
     * live set small: the client re-packs its whole {@code UI/Custom} texture atlas on every
     * index rebuild, over <em>everything</em> still pushed, so a module that mints textures as
     * the player moves must take the stale ones back or the rebuild grows without bound.
     *
     * <p>Nothing already drawn from the name changes; the removal shows up at the next rebuild.
     * A no-op if the name is not currently pushed.
     */
    void remove(String name);

    /**
     * Registers something the client should be given while it is still loading, rather than
     * once it is playing.
     *
     * <p>This is a different thing from {@link #push}, and the difference is the whole point.
     * A file handed over mid-session has to be announced, and announcing means the client
     * rebuilds its whole index - which the player feels. A file handed over during the loading
     * that precedes a world is taken in with everything else the server sends, indexed once
     * along with it, and costs nothing. It is how a server mod ships its own pictures - and
     * the only way to give a <em>block type</em> a picture: the client binds types to their
     * textures once, as the server declares them, from what its index holds at that moment.
     *
     * <p>Register at startup; every connection from then on carries it. Meant for what never
     * changes - an icon, a font, a document - not for anything that follows the player about.
     */
    void provideAtConnect(String name, byte[] bytes);

    /**
     * Stops handing {@code name} over at connect time. Connections already made keep what they
     * were given; the next one loads without it. A no-op for a name never provided.
     */
    void withdrawAtConnect(String name);

    /**
     * Whether {@code name} was handed to this connection's client while it loaded — that is,
     * whether it was registered with {@link #provideAtConnect} in time. Unlike {@link #isPushed}
     * this survives a world change: the client keeps a file its block types use, and so does
     * this record, until the connection ends.
     */
    boolean deliveredAtConnect(String name);

    /**
     * Asks the client to rebuild its asset index. Assets pushed mid-session are not visible to
     * markup until it does, so this follows a batch of pushes — once per batch, not per asset.
     */
    void requestRebuild();

    /** Whether this exact content has already been pushed in this session. */
    boolean isPushed(byte[] bytes);
}
