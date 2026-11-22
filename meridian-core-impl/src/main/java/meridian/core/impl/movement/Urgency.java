package meridian.core.impl.movement;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core's urgent holds on the body ({@code Movement.takeFirst}): while one is held, the work begun
 * before it - a trip, a dig, a loop of swings - waits, and goes on once the last urgent hold is given
 * back. Work tells its age by a stamp taken when it begins ({@link #stamp}); work begun while an
 * urgent hold is held is the urgent holder's own, and runs.
 */
public final class Urgency {

    private final AtomicLong clock = new AtomicLong();
    /** How many urgent holds are held. Guarded by this. */
    private int holds;
    /** The stamp the urgent period under way began at; work stamped before it waits. */
    private long since;
    /** The stamp the last urgent period began at; {@code 0} before any. */
    private volatile long lastBegan;
    /** Completes when the urgent period under way is over. */
    private CompletableFuture<Void> calm = CompletableFuture.completedFuture(null);

    /** A stamp for work beginning now. */
    public long stamp() {
        return clock.incrementAndGet();
    }

    /** Whether an urgent hold is held now. */
    public synchronized boolean active() {
        return holds > 0;
    }

    /** Whether work stamped {@code born} waits now. */
    public synchronized boolean holdsBack(long born) {
        return holds > 0 && born < since;
    }

    /** The stamp the last urgent period began at - to tell whether one came since work began. */
    public long lastBegan() {
        return lastBegan;
    }

    /** Completes when work stamped {@code born} may go on: at once when nothing holds it back. */
    public synchronized CompletableFuture<Void> turn(long born) {
        return holdsBack(born) ? calm : CompletableFuture.completedFuture(null);
    }

    /**
     * Waits, on the calling thread, until work stamped {@code born} may go on.
     *
     * @return whether it had to wait
     */
    public boolean awaitTurn(long born) throws InterruptedException {
        CompletableFuture<Void> t = turn(born);
        if (t.isDone()) {
            return false;
        }
        try {
            t.get();        // until the urgent hold is given back - however long the fight is
        } catch (ExecutionException e) {
            // over all the same
        }
        return true;
    }

    /** An urgent hold taken. */
    void begin() {
        synchronized (this) {
            if (holds++ == 0) {
                since = stamp();
                lastBegan = since;
                calm = new CompletableFuture<>();
            }
        }
    }

    /** An urgent hold given back; the work waiting goes on once none is left. */
    void end() {
        CompletableFuture<Void> over = null;
        synchronized (this) {
            if (holds > 0 && --holds == 0) {
                over = calm;
            }
        }
        if (over != null) {
            over.complete(null);
        }
    }
}
