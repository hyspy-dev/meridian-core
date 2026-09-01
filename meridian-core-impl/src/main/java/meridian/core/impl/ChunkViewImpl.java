package meridian.core.impl;

import meridian.core.api.ChunkView;

/** The two flags {@link ChunkViewHandler} acts on. Nothing else to it. */
final class ChunkViewImpl implements ChunkView {

    private volatile boolean keepLoaded;
    private volatile int viewRadius;

    @Override
    public boolean keepLoaded() {
        return keepLoaded;
    }

    @Override
    public void setKeepLoaded(boolean keep) {
        this.keepLoaded = keep;
    }

    @Override
    public int viewRadius() {
        return viewRadius;
    }

    @Override
    public void setViewRadius(int chunks) {
        this.viewRadius = Math.max(0, chunks);
    }
}
