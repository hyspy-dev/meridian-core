package meridian.core.impl;

import java.util.List;
import meridian.core.api.PastePreview;
import meridian.protocol.packets.interface_.BlockChange;
import meridian.protocol.packets.interface_.FluidChange;

/**
 * Blocks and fluids of ours as the wire carries them: the one place a {@link PastePreview.Change}
 * becomes a {@code BlockChange} - for the paste preview, the paste tool's clipboard and the prefab
 * list's spinning preview alike.
 */
final class Changes {

    private Changes() {
    }

    static BlockChange[] blocks(List<PastePreview.Change> blocks) {
        BlockChange[] out = new BlockChange[blocks == null ? 0 : blocks.size()];
        for (int i = 0; i < out.length; i++) {
            PastePreview.Change c = blocks.get(i);
            out[i] = new BlockChange(c.dx(), c.dy(), c.dz(), c.blockId(), (byte) c.rotation());
        }
        return out;
    }

    static FluidChange[] fluids(List<PastePreview.FluidChange> fluids) {
        FluidChange[] out = new FluidChange[fluids == null ? 0 : fluids.size()];
        for (int i = 0; i < out.length; i++) {
            PastePreview.FluidChange f = fluids.get(i);
            out[i] = new FluidChange(f.dx(), f.dy(), f.dz(), f.fluidId(), (byte) f.level());
        }
        return out;
    }
}
