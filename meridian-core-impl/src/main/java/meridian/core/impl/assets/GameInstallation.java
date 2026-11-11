package meridian.core.impl.assets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import meridian.protocol.ProtocolSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One installed build of the game this proxy speaks to, open for reading: its {@code Assets.zip},
 * the hash of each client file in it, and where the game keeps what it downloaded.
 *
 * <p>Found once per process ({@link #find}). A build fits when its server speaks this proxy's
 * protocol - the {@code ProtocolSettings.PROTOCOL_CRC} compiled into its {@code HytaleServer.jar}
 * is ours; the constant is read off the class file, not by loading it, since the game's classes
 * may be built for a newer Java than the proxy runs on. Several builds can share a protocol (0.7.0
 * and 0.7.6): the one the proxy was told about wins, else the newest.
 */
public final class GameInstallation implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    private static final String SETTINGS_CLASS = "com/hypixel/hytale/protocol/ProtocolSettings.class";

    private static final Object LOCK = new Object();
    private static volatile Optional<GameInstallation> found;
    private static volatile String foundFor;

    final Path gameDirectory;
    final Path userDirectory;
    final String version;
    private final ZipFile assets;
    /** Client file name (as the server names it, without {@code Common/}) → its sha256. */
    private final Map<String, String> commonHashes;
    private final List<String> entries;

    private GameInstallation(Path gameDirectory, Path userDirectory, String version, ZipFile assets,
                             Map<String, String> commonHashes, List<String> entries) {
        this.gameDirectory = gameDirectory;
        this.userDirectory = userDirectory;
        this.version = version;
        this.assets = assets;
        this.commonHashes = commonHashes;
        this.entries = entries;
    }

    /**
     * The installation for this process: the one named by {@code game} (and {@code user}) if it
     * speaks our protocol, else the newest one in the usual places that does; empty with none.
     */
    public static Optional<GameInstallation> find(Optional<Path> game, Optional<Path> user) {
        String key = game.map(Path::toString).orElse("") + "|" + user.map(Path::toString).orElse("");
        Optional<GameInstallation> f = found;
        if (f != null && key.equals(foundFor)) {
            return f;
        }
        synchronized (LOCK) {
            if (found != null && key.equals(foundFor)) {
                return found;
            }
            found = locate(game, user);
            foundFor = key;
            return found;
        }
    }

    private static Optional<GameInstallation> locate(Optional<Path> game, Optional<Path> user) {
        int ours = ProtocolSettings.PROTOCOL_CRC;
        Path chosen = null;
        String chosenVersion = null;
        if (game.isPresent()) {
            Integer crc = crcOf(game.get());
            if (crc != null && crc == ours) {
                chosen = game.get();
                chosenVersion = versionOf(game.get());
            } else {
                log.warn("meridian-core: the game folder the proxy was given ({}) speaks another protocol"
                        + " ({} vs ours {}) - looking for another", game.get(), crc, ours);
            }
        }
        if (chosen == null) {
            for (Path candidate : candidates(game, user)) {
                Integer crc = crcOf(candidate);
                if (crc == null || crc != ours) {
                    continue;
                }
                String v = versionOf(candidate);
                if (chosen == null || newer(v, chosenVersion)) {
                    chosen = candidate;
                    chosenVersion = v;
                }
            }
        }
        if (chosen == null) {
            log.info("meridian-core: no installed game speaks this protocol ({}) - its files are not at hand", ours);
            return Optional.empty();
        }
        Path picked = chosen;
        Path userDir = user.filter(Files::isDirectory).orElseGet(() -> rootOf(picked).resolve("UserData"));
        return open(chosen, chosenVersion, userDir);
    }

    /** Opens one build's {@code Assets.zip}; empty when it cannot be read. */
    private static Optional<GameInstallation> open(Path gameDirectory, String version, Path userDir) {
        try {
            ZipFile zip = new ZipFile(gameDirectory.resolve("Assets.zip").toFile());
            List<String> names = new ArrayList<>(zip.size());
            zip.stream().filter(e -> !e.isDirectory()).forEach(e -> names.add(e.getName()));
            Collections.sort(names);
            Map<String, String> hashes = readHashes(zip);
            log.info("meridian-core: game files from {} ({}, {} files, {} client hashes; downloads in {})",
                    gameDirectory, version, names.size(), hashes.size(), userDir);
            return Optional.of(new GameInstallation(gameDirectory, userDir, version, zip, hashes,
                    Collections.unmodifiableList(names)));
        } catch (IOException e) {
            log.warn("meridian-core: cannot open {}: {}", gameDirectory.resolve("Assets.zip"), e.toString());
            return Optional.empty();
        }
    }

    /** Installations named by hand, by the path they were named by - opened once per process. */
    private static final Map<String, Optional<GameInstallation>> NAMED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The installation a person named by hand: {@code where} is a build's game folder, its {@code
     * Assets.zip}, a build folder ({@code .../install/<build>}), a folder of builds ({@code
     * .../install}) or the Hytale folder above it. Of the builds found there the one speaking our
     * protocol is taken (the newest, if several); when none does, the newest one there all the same,
     * with a warning - the person said so, and the protocol only breaks the tie. Empty when the path
     * holds no build.
     */
    public static Optional<GameInstallation> at(Path where) {
        if (where == null) {
            return Optional.empty();
        }
        String key = where.toAbsolutePath().normalize().toString();
        return NAMED.computeIfAbsent(key, k -> locateUnder(where));
    }

    private static Optional<GameInstallation> locateUnder(Path where) {
        List<Path> builds = new ArrayList<>();
        Path p = where.toAbsolutePath().normalize();
        if (Files.isRegularFile(p) && p.getFileName().toString().equalsIgnoreCase("Assets.zip")) {
            builds.add(p.getParent());
        } else if (Files.isRegularFile(p.resolve("Assets.zip"))) {
            builds.add(p);
        } else if (Files.isRegularFile(p.resolve("package").resolve("game").resolve("latest").resolve("Assets.zip"))) {
            builds.add(p.resolve("package").resolve("game").resolve("latest"));
        } else {
            Path install = Files.isDirectory(p.resolve("install")) ? p.resolve("install") : p;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(install)) {
                for (Path build : children) {
                    Path dir = build.resolve("package").resolve("game").resolve("latest");
                    if (Files.isRegularFile(dir.resolve("Assets.zip"))) {
                        builds.add(dir);
                    }
                }
            } catch (IOException ignored) {
                // not a folder: nothing to look through
            }
        }
        if (builds.isEmpty()) {
            log.warn("meridian-core: no game build under {}", where);
            return Optional.empty();
        }
        int ours = ProtocolSettings.PROTOCOL_CRC;
        Path speaking = null, newest = null;
        String speakingVersion = null, newestVersion = null;
        for (Path build : builds) {
            String v = versionOf(build);
            Integer crc = crcOf(build);
            if (crc != null && crc == ours && (speaking == null || newer(v, speakingVersion))) {
                speaking = build;
                speakingVersion = v;
            }
            if (newest == null || newer(v, newestVersion)) {
                newest = build;
                newestVersion = v;
            }
        }
        Path chosen = speaking != null ? speaking : newest;
        String version = speaking != null ? speakingVersion : newestVersion;
        if (speaking == null) {
            log.warn("meridian-core: nothing under {} is known to speak this protocol ({}) - using {} ({}) as named",
                    where, ours, chosen, version);
        }
        return open(chosen, version, rootOf(chosen).resolve("UserData"));
    }

    /** The build folders to try: under the Hytale folders we were pointed at, then the usual ones. */
    private static List<Path> candidates(Optional<Path> game, Optional<Path> user) {
        Set<Path> roots = new LinkedHashSet<>();
        game.map(GameInstallation::rootOf).ifPresent(roots::add);
        user.map(Path::getParent).ifPresent(roots::add);
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", "");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                roots.add(Path.of(appData, "Hytale"));
            }
        } else if (os.contains("mac")) {
            roots.add(Path.of(home, "Library", "Application Support", "Hytale"));
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            roots.add(xdg != null && !xdg.isBlank() ? Path.of(xdg, "Hytale") : Path.of(home, ".local", "share", "Hytale"));
            roots.add(Path.of(home, ".var", "app", "com.hypixel.HytaleLauncher", "data", "Hytale"));
        }
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            Path install = root.resolve("install");
            if (!Files.isDirectory(install)) {
                continue;
            }
            try (DirectoryStream<Path> builds = Files.newDirectoryStream(install)) {
                for (Path build : builds) {
                    Path dir = build.resolve("package").resolve("game").resolve("latest");
                    if (Files.isRegularFile(dir.resolve("Assets.zip"))) {
                        out.add(dir);
                    }
                }
            } catch (IOException ignored) {
                // an unreadable folder is just not a candidate
            }
        }
        return out;
    }

    /** {@code <root>/install/<build>/package/game/latest} → {@code <root>}. */
    private static Path rootOf(Path gameDirectory) {
        Path p = gameDirectory.toAbsolutePath().normalize();
        for (int i = 0; i < 5 && p.getParent() != null; i++) {
            p = p.getParent();
        }
        return p;
    }

    /** The protocol CRC compiled into a build's server, or {@code null} when it cannot be read. */
    static Integer crcOf(Path gameDirectory) {
        Path jar = gameDirectory.resolve("Server").resolve("HytaleServer.jar");
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry(SETTINGS_CLASS);
            if (e == null) {
                return null;
            }
            try (InputStream in = z.getInputStream(e)) {
                return ClassConstants.intConstant(in.readAllBytes(), "PROTOCOL_CRC");
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A build's version, from its server's manifest ({@code Implementation-Version}). */
    private static String versionOf(Path gameDirectory) {
        try (JarFile jar = new JarFile(gameDirectory.resolve("Server").resolve("HytaleServer.jar").toFile())) {
            var manifest = jar.getManifest();
            String v = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
            return v == null ? "?" : v;
        } catch (IOException e) {
            return "?";
        }
    }

    /**
     * Whether version {@code a} is later than {@code b}: numeric parts compared as numbers, and a
     * build's pre-release before the build itself ({@code 0.8.0-pre.4} before {@code 0.8.0}).
     */
    static boolean newer(String a, String b) {
        if (b == null) {
            return true;
        }
        if (a == null) {
            return false;
        }
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            if (i >= x.length) {
                return y[i].startsWith("pre"); // a ended: it is the release b is a pre-release of
            }
            if (i >= y.length) {
                return !x[i].startsWith("pre"); // b ended: a is later unless it is b's pre-release
            }
            if (x[i].equals(y[i])) {
                continue;
            }
            boolean xn = isNumber(x[i]);
            boolean yn = isNumber(y[i]);
            if (xn && yn) {
                return Long.parseLong(x[i]) > Long.parseLong(y[i]);
            }
            return xn || (!yn && x[i].compareTo(y[i]) > 0);
        }
        return false;
    }

    private static boolean isNumber(String s) {
        return !s.isEmpty() && s.length() < 18 && s.chars().allMatch(Character::isDigit);
    }

    private static Map<String, String> readHashes(ZipFile zip) throws IOException {
        Map<String, String> out = new HashMap<>();
        ZipEntry index = zip.getEntry("CommonAssetsIndex.hashes");
        if (index == null) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(index), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int space = line.indexOf(' ');
                if (space > 0) {
                    out.put(line.substring(space + 1).trim(), line.substring(0, space).trim().toLowerCase());
                }
            }
        }
        return out;
    }

    /** A file of the installed game's assets, or empty. */
    Optional<byte[]> read(String path) {
        ZipEntry e = assets.getEntry(path);
        if (e == null || e.isDirectory()) {
            return Optional.empty();
        }
        try (InputStream in = assets.getInputStream(e)) {
            return Optional.of(in.readAllBytes());
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /** Whether the installed game has {@code path}. */
    boolean has(String path) {
        return assets.getEntry(path) != null;
    }

    /** The sha256 the game lists for a client file ({@code BlockTextures/...}), or {@code null}. */
    String commonHash(String name) {
        return commonHashes.get(name);
    }

    /** Every file path in the installed game's assets, sorted. */
    List<String> entries() {
        return entries;
    }

    /** Where the game keeps a file it downloaded, by its sha256. */
    Path downloaded(String sha256) {
        return userDirectory.resolve("CachedAssets").resolve(sha256.substring(0, 2)).resolve(sha256);
    }

    @Override
    public void close() throws IOException {
        assets.close();
    }
}
