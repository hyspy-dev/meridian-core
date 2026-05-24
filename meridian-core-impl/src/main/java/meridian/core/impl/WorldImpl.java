package meridian.core.impl;

import java.util.Optional;
import meridian.core.api.Block;
import meridian.core.api.BlockView;
import meridian.core.api.InteractionControl;
import meridian.core.api.Player;
import meridian.core.api.World;
import meridian.protocol.BlockType;

/**
 * Live {@link World} — the position-addressed facade over the proxy's trackers.
 *
 * <p>Blocks come from {@link ChunkTracker} (live ids) resolved through
 * {@link WorldStateImpl} (the type catalog); actions are forged through
 * {@link InteractionControl}. A Layer-2 module queries blocks and acts on them,
 * writing its own scan / decision logic.
 */
public final class WorldImpl implements World {
    private final ChunkTracker chunks;
    private final WorldStateImpl worldState;
    private final InteractionControl interactions;
    private final PlayerImpl player;

    public WorldImpl(ChunkTracker chunks, WorldStateImpl worldState,
                     EntityTrackerImpl entities, InventoryTracker inventory,
                     InteractionControl interactions) {
        this.chunks = chunks;
        this.worldState = worldState;
        this.interactions = interactions;
        this.player = new PlayerImpl(entities, inventory, interactions);
        // Late binding — PlayerImpl needs us for lookedAtBlock's raycast.
        // Doing this after the fields are assigned keeps the leaked-this
        // window as narrow as possible.
        this.player.bindWorld(this);
    }

    @Override
    public Block blockAt(int x, int y, int z) {
        int id = chunks.blockIdAt(x, y, z);
        BlockType blockType = id > 0 ? worldState.blockTypeById(id) : null;
        BlockView type = blockType == null ? null : new BlockViewImpl(id, blockType);
        return new BlockImpl(x, y, z, id, type, interactions);
    }

    @Override
    public Optional<Player> player() {
        return interactions.available() ? Optional.of(player) : Optional.empty();
    }
}
