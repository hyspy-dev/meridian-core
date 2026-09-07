package meridian.core.api;

import java.util.List;
import java.util.UUID;

/**
 * A module's opinion about the ground, in colour.
 *
 * <p>Whoever knows something about a column - that it has not been downloaded, that it does not
 * match what the seed would have generated there - says so by registering one of these with
 * {@link MapTints}. The map asks it while it builds a tile and paints what it is told. The
 * knowing and the drawing stay where they belong: the module owns what it knows, and the map owns
 * how a map looks.
 *
 * <p>A layer names a small, fixed set of {@link Paint states} and then answers, for any column,
 * which of them it is in - or none. The set is fixed because it is three things at once: the
 * legend the window shows, the order in which states beat each other, and the promise that a
 * column is one thing at a time.
 *
 * <h2>What a colour means here</h2>
 *
 * A state's colour is not painted over the ground; it is what the ground is pulled towards, and
 * each part of it does a different job:
 *
 * <ul>
 *   <li><b>Alpha</b> - how strongly. 0 leaves the ground alone, 255 replaces it.</li>
 *   <li><b>Hue</b> - which way to colour it.</li>
 *   <li><b>Saturation</b> - how colourful. Zero is the useful extreme: a grey state does not
 *       colour the ground at all, it drains the colour out of it, which reads as "we know less
 *       about this" rather than as a claim of its own. Grey states cost nothing to mix, so a
 *       fact that is about <em>our</em> data rather than about the world is best said in grey.</li>
 *   <li><b>Brightness</b> - whether the ground is dimmed or lifted. {@code 0x80} is neutral,
 *       darker dims, lighter lifts.</li>
 * </ul>
 *
 * <h2>When two layers speak about one column</h2>
 *
 * {@link Paint.Mode#SHARED} states add up - on the circle of hues, so that a red and a blue make
 * a purple rather than a muddy grey, and so that the answer does not depend on which module
 * happened to load first. Their strengths compound without ever passing 1.
 *
 * <p>Which means the hues have to be chosen so that no mixture lands on somebody else's meaning.
 * Space them out - three coloured facts at 120 degrees apart leave their three mixtures in the
 * gaps between them - and keep anything that is not really a colour (see saturation, above) grey.
 * The map checks this when a layer is added and says so in the log if two meanings are too close
 * to tell apart, or if one sits where another pair's mixture will land.
 *
 * <p>{@link Paint.Mode#SINGLE} is the other answer: a state that will not be mixed with. The
 * heaviest single one on a column wins and everything else on that column is dropped. For a fact
 * that would be a lie if it were blended with anything.
 */
public interface MapTint {

    /** What this layer is called, in the window's legend. */
    String name();

    /**
     * The states this layer can put a column in, weakest first.
     *
     * <p>Fixed for the life of the layer: the window reads it once for its legend, and the order
     * is what settles which state a square shows when several columns are drawn as one.
     */
    List<Paint> states();

    /**
     * Which state this column is in - an index into {@link #states()}, or -1 for no opinion.
     *
     * <p>Asked while a tile is being built, on the map's own threads, and asked often. It has to
     * be a lookup: no locks, no files, no work that could block. Whatever answering it needs must
     * already be in memory, and the answer must not change unless the layer says so through
     * {@link MapTints#changed}.
     */
    int stateOf(UUID world, int chunkX, int chunkZ);

    /** One state: what it is called, what it looks like, and how it behaves when others speak. */
    record Paint(String label, int argb, Mode mode, float weight) {

        /** A state that mixes with what other layers say about the same column. */
        public static Paint shared(String label, int argb, float weight) {
            return new Paint(label, argb, Mode.SHARED, weight);
        }

        /** A state that does not mix: the heaviest one on a column is the only one drawn. */
        public static Paint single(String label, int argb, float weight) {
            return new Paint(label, argb, Mode.SINGLE, weight);
        }

        /** How a state behaves when another layer has something to say about the same column. */
        public enum Mode {
            /** Adds to the others, on the circle of hues. */
            SHARED,
            /** Wins outright, by weight, and drops the rest. */
            SINGLE
        }
    }
}
