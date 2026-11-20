package meridian.core.impl.movement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import meridian.protocol.Direction;

/** Waits for a look to pass through the outgoing movement path, rather than just the body tick. */
final class LookReports {
    private record Waiting(float yaw, float pitch, CompletableFuture<Boolean> done) { }
    private final List<Waiting> waiting = new ArrayList<>();
    private Direction last;

    synchronized CompletableFuture<Boolean> await(float yaw, float pitch) {
        if (matches(yaw, pitch)) return CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Waiting entry = new Waiting(yaw, pitch, done);
        waiting.add(entry);
        return done.completeOnTimeout(false, 2, TimeUnit.SECONDS).whenComplete((r, e) -> {
            synchronized (this) { waiting.remove(entry); }
        });
    }

    void reported(Direction look) {
        if (look == null) return;
        List<Waiting> complete;
        synchronized (this) {
            last = look.clone();
            complete = waiting.stream().filter(w -> matches(w.yaw(), w.pitch())).toList();
            waiting.removeAll(complete);
        }
        complete.forEach(w -> w.done().complete(true));
    }

    void reset() {
        List<Waiting> incomplete;
        synchronized (this) {
            last = null;
            incomplete = List.copyOf(waiting);
            waiting.clear();
        }
        incomplete.forEach(w -> w.done().complete(false));
    }

    private boolean matches(float yaw, float pitch) {
        return last != null && Neck.apart(last.yaw, last.pitch, yaw, pitch) <= 1e-6;
    }
}
