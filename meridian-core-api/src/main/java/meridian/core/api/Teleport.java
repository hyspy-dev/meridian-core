package meridian.core.api;

/**
 * Taking the player somewhere, as a module that owns teleporting offers it.
 *
 * <p>{@link Player#teleport} is the mechanism - it forges the packets. This is the <em>service</em>
 * on top of it: whoever provides it decides what a teleport means here, including the part a map
 * cannot know, which is how high the ground is at a column. A map has an X and a Z and nothing
 * else; the provider works out the Y.
 *
 * <p>Optional by nature: ask the registry with {@code get} rather than {@code require}, and offer
 * no teleport when nobody provides one.
 */
public interface Teleport {

    /**
     * Takes the player to this column, at whatever height the ground is.
     *
     * <p>For a place picked off a map. When the ground there is not loaded, the provider decides
     * what to do - usually keeping the player's current height.
     */
    void toColumn(int blockX, int blockZ);

    /** Takes the player to exactly this point. */
    void to(Vec3 where);
}
