package meridian.core.api;

/**
 * What the server pushes at the client that is not the world: files, and the pages it builds
 * out of them.
 *
 * <p>A server with custom content sends it over the wire — a UI document, the textures it draws
 * with, the commands that fill a page in and the bindings that answer a click. None of it is
 * written down anywhere: the client holds it for the session and forgets it. This is where
 * anybody who wants to keep a copy listens.
 *
 * <h2>Two halves, and both are needed</h2>
 *
 * <p>{@link Listener#asset} is a whole file, reassembled from the parts it arrives in — a
 * {@code .ui} document, a texture, whatever the server sends. {@link Listener#page} is what the
 * server then does with it: which page was opened, what was appended to it, what it was told to
 * say. A document without its commands is markup nobody ever filled in, and commands without the
 * document are instructions about nothing, so a keeper of either should keep both.
 *
 * <h2>Nothing is held for nobody</h2>
 *
 * <p>Assets are assembled only while somebody is subscribed. With no listener the parts go past
 * untouched and nothing is buffered - which matters, because an asset can be large and the parts
 * arrive on the connection's own thread.
 *
 * <p>For the same reason a listener must not block: it is called on that thread, as the packet
 * goes by. Take the bytes and hand them to your own executor.
 */
public interface ServerAssets {

    /** Starts listening; closing what comes back stops it. */
    AutoCloseable subscribe(Listener listener);

    /** Whether anybody is listening - for a module that wants to say so in its status line. */
    boolean watched();

    interface Listener {

        /**
         * A file the server pushed to the client, whole.
         *
         * @param name  what the server calls it, e.g. {@code UI/Custom/shop.ui}
         * @param hash  the content hash it was announced with
         * @param bytes the file; do not keep the array, copy what you need
         */
        default void asset(String name, String hash, byte[] bytes) {
        }

        /**
         * A page or a HUD the server built, as JSON: which one, and every command it was given.
         *
         * <p>JSON rather than the packet itself because this interface has to outlive the
         * protocol it came from - and because what a keeper does with it is write it down.
         *
         * @param key  the page or HUD the commands belong to
         * @param json the whole event, ready to be written to a file
         */
        default void page(String key, String json) {
        }
    }
}
