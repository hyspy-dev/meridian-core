package meridian.core.impl.movement;

import meridian.protocol.Direction;

/** The turn future must not release a shot ahead of its outgoing movement. */
public final class LookReportsTest {
    public static void main(String[] args) {
        LookReports reports = new LookReports();
        var first = reports.await(1f, 0.2f);
        check(!first.isDone(), "unsent look completed");
        reports.reported(new Direction(0f, 0f, 0f));
        check(!first.isDone(), "old look completed the turn");
        reports.reported(null);
        check(!first.isDone(), "a movement without look completed the turn");
        reports.reported(new Direction(1f, 0.2f, 0f));
        check(first.join(), "matching outgoing look did not complete");
        check(reports.await(1f, 0.2f).join(), "already reported look waited again");
        var interrupted = reports.await(2f, 0f);
        reports.reset();
        check(!interrupted.join(), "world reset completed a turn successfully");
        check(!reports.await(1f, 0.2f).isDone(), "old world's look survived reset");
        System.out.println("PASS LookReports: unsent, stale, omitted, reported, unchanged and reset");
    }
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
