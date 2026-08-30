package meridian.core.impl;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import meridian.api.session.ProxySession;
import meridian.core.api.ClientAssets;
import meridian.protocol.Asset;
import meridian.protocol.packets.setup.AssetFinalize;
import meridian.protocol.packets.setup.AssetInitialize;
import meridian.protocol.packets.setup.AssetPart;
import meridian.protocol.packets.setup.RemoveAssets;
import meridian.protocol.packets.setup.RequestCommonAssetsRebuild;

/**
 * The one place blobs reach the client from.
 *
 * <p>Mirrors the server's own pack streaming: SHA-256 of the bytes is the asset id, the bytes go
 * out as {@code AssetInitialize} → {@code AssetPart}* → {@code AssetFinalize}, split at the
 * size the server uses.
 *
 * <p>Names are content-addressed, which makes an asset immutable: the same bytes always get the
 * same name, and a name's bytes never change. That is what allows the dedup — a reference handed
 * to one module can never mutate under another — and it is also what keeps the client alive,
 * since a second push of an in-flight hash disconnects it.
 */
final class ClientAssetsImpl implements ClientAssets {

    /** The server splits at 2.5 MB ({@code ArrayUtil.split(bytes, 2621440)}). */
    private static final int PART_SIZE = 2_621_440;

    private final SessionHolder session;
    /** content hash → the name it was pushed under, for this session. */
    private final Map<String, String> pushed = new ConcurrentHashMap<>();
    /** and back again, so a name can be freed for new content. */
    private final Map<String, String> byName = new ConcurrentHashMap<>();
    /** what every connection should carry, handed over while the client is still loading. */
    private final Map<String, byte[]> atConnect = new ConcurrentHashMap<>();

    ClientAssetsImpl(SessionHolder session) {
        this.session = session;
    }

    @Override
    public Optional<String> push(byte[] bytes, String extension) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        return push("a_" + sha256Hex(bytes)
                + (extension == null || extension.isBlank() ? "" : "." + extension), bytes);
    }

    @Override
    public Optional<String> push(String name, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || name == null || name.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(bytes);
        String existing = pushed.get(hash);
        if (existing != null) {
            return Optional.of(existing);   // already on the client; pushing again would kill it
        }
        ProxySession live = session.get().orElse(null);
        if (live == null) {
            return Optional.empty();
        }
        // Claim the name before sending: two threads pushing the same content must not both
        // start a download of that hash.
        if (pushed.putIfAbsent(hash, name) != null) {
            return Optional.of(pushed.get(hash));
        }
        byName.put(name, hash);
        live.sendToClient(new AssetInitialize(new Asset(hash, name), bytes.length));
        for (int offset = 0; offset < bytes.length; offset += PART_SIZE) {
            int end = Math.min(offset + PART_SIZE, bytes.length);
            byte[] part = new byte[end - offset];
            System.arraycopy(bytes, offset, part, 0, part.length);
            live.sendToClient(new AssetPart(part));
        }
        live.sendToClient(new AssetFinalize());
        return Optional.of(name);
    }

    @Override
    public Optional<String> replace(String name, byte[] bytes) {
        if (name == null || name.isBlank() || bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        String had = byName.remove(name);
        if (had != null) {
            // Off the client first. Until the name is free, the picture behind it cannot change:
            // the client resolved it once and kept what it found.
            pushed.remove(had);
            session.get().ifPresent(live ->
                    live.sendToClient(new RemoveAssets(new Asset[]{new Asset(had, name)})));
        }
        return push(name, bytes);
    }

    @Override
    public void remove(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        String had = byName.remove(name);
        if (had != null) {
            // Free the hash too, so the same content can be pushed afresh if it comes back.
            pushed.remove(had);
            session.get().ifPresent(live ->
                    live.sendToClient(new RemoveAssets(new Asset[]{new Asset(had, name)})));
        }
    }

    @Override
    public void provideAtConnect(String name, byte[] bytes) {
        if (name != null && !name.isBlank() && bytes != null && bytes.length > 0) {
            atConnect.put(name, bytes);
        }
    }

    /**
     * Hands over everything registered for connect time, on the session the loading traffic is
     * arriving on. Called once per connection, while the client is still taking assets in.
     */
    void sendAtConnect(ProxySession live) {
        atConnect.forEach((name, bytes) -> {
            String hash = sha256Hex(bytes);
            pushed.putIfAbsent(hash, name);
            byName.put(name, hash);
            live.sendToClient(new AssetInitialize(new Asset(hash, name), bytes.length));
            for (int offset = 0; offset < bytes.length; offset += PART_SIZE) {
                int end = Math.min(offset + PART_SIZE, bytes.length);
                byte[] part = new byte[end - offset];
                System.arraycopy(bytes, offset, part, 0, part.length);
                live.sendToClient(new AssetPart(part));
            }
            live.sendToClient(new AssetFinalize());
        });
    }

    boolean hasConnectAssets() {
        return !atConnect.isEmpty();
    }

    @Override
    public void requestRebuild() {
        session.get().ifPresent(live -> live.sendToClient(new RequestCommonAssetsRebuild()));
    }

    @Override
    public boolean isPushed(byte[] bytes) {
        return bytes != null && bytes.length > 0 && pushed.containsKey(sha256Hex(bytes));
    }

    /** Records a hash the server itself pushed, so we never push it a second time. */
    void noteServerPush(String hash, String name) {
        if (hash != null) {
            pushed.putIfAbsent(hash, name);
        }
    }

    /** Whether this hash has been seen this session, from either side. */
    boolean seen(String hash) {
        return hash != null && pushed.containsKey(hash);
    }

    /** A new session starts with an empty client blob store. */
    void reset() {
        pushed.clear();
        byName.clear();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
