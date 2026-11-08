package meridian.core.impl.interaction;

import java.util.Set;

/** What the forging side needs to know of the entities around: what an action aims at, and the player's own effects. */
public interface EntityLookup {

    /** The entity as an aim, with the player's pose, or {@code null} while it is not in sight. */
    EntityTarget target(int networkId);

    /** The player's own active entity effects, by effect index. */
    Set<Integer> selfEffects();

    /** The player's own stats, as the server last said. */
    StatSheet selfStats();
}
