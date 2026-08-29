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
     * Asks the client to rebuild its asset index. Assets pushed mid-session are not visible to
     * markup until it does, so this follows a batch of pushes — once per batch, not per asset.
     */
    void requestRebuild();

    /** Whether this exact content has already been pushed in this session. */
    boolean isPushed(byte[] bytes);
}
