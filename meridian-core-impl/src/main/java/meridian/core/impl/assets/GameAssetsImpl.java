package meridian.core.impl.assets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import meridian.core.api.GameAssets;
import meridian.protocol.Asset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GameAssets} for one connection: the process's {@link GameInstallation}, and the client
 * files this connection's server announced ({@code WorldSettings.requiredAssets}: name and
 * sha256). A server file the game does not have as is comes from where the game keeps what it
 * downloaded ({@code UserData/CachedAssets}), checked against its hash; nothing is asked of the
 * server.
 */
public final class GameAssetsImpl implements GameAssets {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");
    private static final String COMMON = "Common/";

    private final CompletableFuture<Optional<GameInstallation>> installation;
    /** Client file name → sha256, as this connection's server announced it. */
    private final Map<String, String> announced = new ConcurrentHashMap<>();
    private final java.util.Set<String> missingReported = ConcurrentHashMap.newKeySet();

    /** Starts finding the installation now, on {@code executor}; reads wait for it. */
    public GameAssetsImpl(Optional<Path> game, Optional<Path> user, Executor executor) {
        this.installation = CompletableFuture.supplyAsync(() -> GameInstallation.find(game, user), executor);
    }

    /** What this connection's server said it has: {@code WorldSettings.requiredAssets}. */
    public void announced(Asset[] assets) {
        announced.clear();
        if (assets == null) {
            return;
        }
        for (Asset a : assets) {
            if (a != null && a.name != null && a.hash != null && !a.name.isEmpty()) {
                announced.put(a.name, a.hash.toLowerCase());
            }
        }
    }

    private Optional<GameInstallation> installation() {
        try {
            return installation.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean available() {
        return installation().isPresent();
    }

    @Override
    public Optional<String> gameVersion() {
        return installation().map(i -> i.version);
    }

    @Override
    public Optional<Path> gameDirectory() {
        return installation().map(i -> i.gameDirectory);
    }

    @Override
    public Optional<byte[]> read(String path) {
        if (path == null) {
            return Optional.empty();
        }
        Optional<GameInstallation> inst = installation();
        String serverHash = serverOwn(path, inst);
        if (serverHash != null) {
            Optional<byte[]> own = inst.flatMap(i -> downloaded(i, serverHash));
            if (own.isEmpty() && missingReported.add(path)) {
                log.info("meridian-core: the server's own {} is not among the game's downloads - not at hand", path);
            }
            return own;
        }
        return inst.flatMap(i -> i.read(path));
    }

    @Override
    public List<String> list(String prefix) {
        String p = prefix == null ? "" : prefix;
        TreeSet<String> out = new TreeSet<>();
        installation().ifPresent(i -> {
            for (String e : i.entries()) {
                if (e.startsWith(p)) {
                    out.add(e);
                }
            }
        });
        for (String name : announced.keySet()) {
            String path = COMMON + name;
            if (path.startsWith(p)) {
                out.add(path);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Source source(String path) {
        if (path == null) {
            return Source.NONE;
        }
        Optional<GameInstallation> inst = installation();
        if (serverOwn(path, inst) != null) {
            return Source.SERVER;
        }
        return inst.filter(i -> i.has(path)).isPresent() ? Source.GAME : Source.NONE;
    }

    /**
     * The sha256 of the server's own file at {@code path} - one it announced that the installed game
     * does not have as is - or {@code null} when the game's file is the one.
     */
    private String serverOwn(String path, Optional<GameInstallation> inst) {
        if (!path.startsWith(COMMON)) {
            return null;
        }
        String name = path.substring(COMMON.length());
        String hash = announced.get(name);
        if (hash == null) {
            return null;
        }
        String game = inst.map(i -> i.commonHash(name)).orElse(null);
        return hash.equals(game) ? null : hash;
    }

    /** A file the game downloaded, by its sha256 - if it is there and still that file. */
    private static Optional<byte[]> downloaded(GameInstallation inst, String sha256) {
        if (sha256.length() < 3) {
            return Optional.empty();
        }
        Path file = inst.downloaded(sha256);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(file);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return actual.equalsIgnoreCase(sha256) ? Optional.of(bytes) : Optional.empty();
        } catch (IOException | NoSuchAlgorithmException e) {
            return Optional.empty();
        }
    }
}
