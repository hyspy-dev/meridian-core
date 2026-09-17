package meridian.core.impl;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import meridian.core.api.ServerAssets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the server's own pushes back together and hands them on.
 *
 * <p>An asset arrives as a name and a size, then its bytes in pieces, then a full stop. One at a
 * time on the channel, so one buffer is enough - and the buffer only exists while somebody is
 * listening, because an unwatched feed that quietly assembles hundreds of megabytes is how a
 * proxy dies.
 */
final class ServerAssetsImpl implements ServerAssets {

    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /**
     * The largest asset this will hold in memory.
     *
     * <p>What the feed is for is UI documents and the pictures beside them, which are kilobytes.
     * Anything of a different order is a texture pack or a model library, and keeping it whole in
     * memory on the connection's thread would cost more than it could possibly be worth. The
     * announcement carries the size, so an oversized one is refused before a single byte is kept,
     * and it is said once in the log rather than every part.
     */
    private static final int MOST_BYTES = 64 * 1024 * 1024;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** The asset being assembled: its name, its promised size, and what has arrived. */
    private String name;
    private String hash;
    private int promised;
    private ByteArrayOutputStream bytes;

    @Override
    public AutoCloseable subscribe(Listener listener) {
        if (listener == null) {
            return () -> { };
        }
        listeners.add(listener);
        return () -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                forget();                   // nobody left to hand it to
            }
        };
    }

    @Override
    public boolean watched() {
        return !listeners.isEmpty();
    }

    // ------------------------------------------------------------------
    // The stream, as it goes by
    // ------------------------------------------------------------------

    void begins(String assetName, String assetHash, int size) {
        forget();
        if (listeners.isEmpty()) {
            return;
        }
        if (size < 0 || size > MOST_BYTES) {
            log.info("meridian-core: not keeping {} - {} bytes is past what this holds",
                    assetName, size);
            return;
        }
        name = assetName;
        hash = assetHash;
        promised = size;
        bytes = new ByteArrayOutputStream(Math.max(16, size));
    }

    void part(byte[] piece) {
        if (bytes == null || piece == null) {
            return;
        }
        if (bytes.size() + piece.length > promised) {
            // More than was promised: the announcement is what this trusts, so stop rather than
            // grow. Something is not the shape this was written for and guessing is worse.
            log.info("meridian-core: {} sent more than the {} bytes it announced - dropped",
                    name, promised);
            forget();
            return;
        }
        bytes.writeBytes(piece);
    }

    void ends() {
        if (bytes == null) {
            return;
        }
        String whatItIs = name;
        String itsHash = hash;
        byte[] whole = bytes.toByteArray();
        forget();                           // before the handing on: a listener may take a while
        for (Listener listener : listeners) {
            try {
                listener.asset(whatItIs, itsHash, whole);
            } catch (RuntimeException e) {
                log.warn("meridian-core: a server-asset listener threw on {}", whatItIs, e);
            }
        }
    }

    void page(String key, String json) {
        for (Listener listener : listeners) {
            try {
                listener.page(key, json);
            } catch (RuntimeException e) {
                log.warn("meridian-core: a server-asset listener threw on page {}", key, e);
            }
        }
    }

    private void forget() {
        name = null;
        hash = null;
        promised = 0;
        bytes = null;
    }
}
