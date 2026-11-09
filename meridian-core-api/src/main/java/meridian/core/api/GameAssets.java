package meridian.core.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The game's own files, read from this machine - the way a module gets a texture, an icon, an
 * asset's JSON, without carrying a copy of any of them.
 *
 * <p>Paths are those of the game's {@code Assets.zip}: {@code Common/...} for what the client draws
 * and plays ({@code Common/BlockTextures/Soil_Dirt.png}, {@code Common/UI/WorldMap/MapMarkers/Home.png}),
 * {@code Server/...} for the game's definitions ({@code Server/Item/Items/Food/Food_Bread.json}).
 * The names the server gives client files - a block type's textures, an asset it announces - are
 * {@code Common/} paths without the {@code Common/}; {@link #common} takes those.
 *
 * <p>A {@code Common/} file is the one this server uses: when the server announced a file of
 * that name that is not the game's own (a mod's, or one it replaced), it is that file, as the game
 * downloaded it; otherwise it is the installed game's. {@code Server/} files are the installed
 * game's alone - a server's own definitions never leave it.
 *
 * <p>The installation is the one whose protocol is the one this proxy speaks: the build the
 * launcher started, or else one found in the usual places, the newest if several speak it. With
 * none on this machine (a bot on a bare server) nothing is {@link #available()}, and every read
 * is empty: a module then does without.
 *
 * <p>Nothing read here is the module's to hand on: what it makes of a file (a texture cut into a
 * frame) goes to this player's own client, never into a jar or a download.
 */
public interface GameAssets {

    /** Where a file comes from. */
    enum Source {
        /** The server's own: a file it announced that the installed game does not have as is. */
        SERVER,
        /** The installed game's. */
        GAME,
        /** Nowhere this machine has. */
        NONE
    }

    /** Whether an installation of this game version is at hand. */
    boolean available();

    /** The installed game's version ({@code 0.8.0-pre.4}), when one is at hand. */
    Optional<String> gameVersion();

    /** The installation's game folder ({@code .../package/game/latest}), when one is at hand. */
    Optional<Path> gameDirectory();

    /** A file by its path in the game's assets; empty when this machine does not have it. */
    Optional<byte[]> read(String path);

    /** A client file by the name the server gives it ({@code BlockTextures/Soil_Dirt.png}). */
    default Optional<byte[]> common(String name) {
        return read("Common/" + name);
    }

    /** The paths that start with {@code prefix} ({@code Common/BlockTextures/}), sorted. */
    List<String> list(String prefix);

    /** Where {@link #read} would take {@code path} from. */
    Source source(String path);

    /**
     * The game's files from an installation named by hand - a module's "where the game is" setting -
     * in place of the one core found. {@code where} may be a build's game folder, its {@code
     * Assets.zip}, a build folder ({@code .../install/<build>}), a folder of builds ({@code
     * .../install}) or the Hytale folder above it. Of the builds there, the one that speaks this
     * proxy's protocol is taken (the newest, if several); when none does, the newest there all the
     * same, with a warning in the log - the person named it, and the protocol only picks among what
     * they named. Nothing is {@link #available()} when the path holds no build. The server's own
     * client files are this connection's, as here.
     *
     * <p>Opened once per path for the process; this view is the caller's to keep.
     */
    default GameAssets at(Path where) {
        return this;
    }
}
