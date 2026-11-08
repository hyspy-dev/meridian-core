package meridian.core.api;

import java.util.List;
import java.util.Map;

/**
 * How a forged interaction went - as the server ran it, not as core guessed it.
 *
 * <p>Core plays a chain the way the client does: it predicts each operation, stands on one the
 * server alone decides until the server says how it went, and when the server ran an operation
 * differently it goes back there and replays the rest the server's way. The outcome is what the
 * server said in the end.
 *
 * <p>A chain's own end says little about whether the action did what was meant. A chain usually
 * has a branch for a step that fails - a watering can on grass plays its "nothing to water"
 * branch and ends {@link Status#FINISHED} all the same. The {@link #steps} say which operations ran
 * and which of them failed.
 *
 * @param status      how the chain ended
 * @param steps       the operations the server ran, in order
 * @param forks       the chains this one forked, by fork path ({@code "5.0"}, {@code "5.0/2.1"}),
 *                    and how each ended - the hit a swing lands, the block a gesture places
 * @param corrections how many times the server ran an operation differently from core's guess
 */
public record InteractionOutcome(Status status, List<Step> steps, Map<String, Status> forks, int corrections) {

    /** How a chain ended. */
    public enum Status {
        /** The server ran it to the end, and its last operation finished. */
        FINISHED,
        /** The server ran it to the end, and its last operation failed. */
        FAILED,
        /** The server cancelled it. */
        CANCELLED,
        /** The server never said how it ended. */
        TIMED_OUT,
        /** Nothing was sent: no session, no interaction for the held item, an entity out of sight. */
        NOT_SENT
    }

    /**
     * One operation of the chain as the server ran it.
     *
     * @param index     its operation index in the chain (one per operation run, across every
     *                  interaction the chain entered)
     * @param operation what it is: the interaction's kind and id ({@code UseEntityInteraction#412}),
     *                  or {@code jump}
     * @param failed    whether it failed
     */
    public record Step(int index, String operation, boolean failed) {}

    public InteractionOutcome {
        steps = List.copyOf(steps);
        forks = Map.copyOf(forks);
    }

    /** Nothing was sent. */
    public static InteractionOutcome notSent() {
        return new InteractionOutcome(Status.NOT_SENT, List.of(), Map.of(), 0);
    }

    /** {@code true} when the server ran the chain to the end and its last operation finished. */
    public boolean finished() {
        return status == Status.FINISHED;
    }

    /** The operations that failed, in order. */
    public List<Step> failedSteps() {
        return steps.stream().filter(Step::failed).toList();
    }
}
