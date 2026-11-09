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

    /**
     * The game mode the server has the player in - what {@code ConditionInteraction.requiredGameMode}
     * tests and the reach goes by - or {@code null} before it has said.
     */
    default meridian.protocol.GameMode selfGameMode() {
        return null;
    }

    /**
     * The player entity's own interaction roots ({@code Interactions} component) by type - what
     * the server looks at before the held item (a dodge lives here). Empty when it has none.
     */
    default java.util.Map<meridian.protocol.InteractionType, Integer> selfInteractions() {
        return java.util.Map.of();
    }
}
