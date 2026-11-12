package meridian.core.impl.interaction;

import meridian.protocol.Interaction;
import meridian.protocol.InteractionRules;
import meridian.protocol.InteractionType;
import meridian.protocol.RootInteraction;

/**
 * When a chain started cuts short one that runs - the server's {@code InteractionManager.applyRules}
 * and {@code InteractionRules.validateInterrupts}, which the client keeps the same.
 *
 * <p>A chain of type {@code T} with root {@code R}, started while another chain runs, cuts that one
 * short when:
 * <ul>
 *   <li>the root the running chain stands in lists {@code T} in its {@code InterruptedBy}, or the
 *       interaction it stands on does (a sword's swing is {@code InterruptedBy: Secondary} until its
 *       blow) - unless {@code R} carries the tag the rule names as its bypass;</li>
 *   <li>or {@code R}'s own {@code Interrupting} lists the running chain's type - unless the running
 *       root (or interaction) carries the bypass tag.</li>
 * </ul>
 * The server checks the running root's rules first, then its operation's; interruptions come before
 * blocking. A chain it cuts short it cancels, and tells the client so ({@code CancelInteractionChain}).
 */
final class ChainRules {

    /** No bypass tag ({@code AssetRegistry.TAG_NOT_FOUND}). */
    static final int NO_TAG = Integer.MIN_VALUE;

    private ChainRules() {
    }

    /**
     * Whether a chain of {@code type} with root {@code root}, started now, cuts short a running chain
     * of {@code runningType} that stands in {@code runningRoot} on {@code runningOp}.
     *
     * @param root        the new chain's root; {@code null} when not known (its own rules then count
     *                    for nothing)
     * @param runningRoot the root the running chain stands in now - after a {@code Replace}, the root
     *                    it entered; {@code null} when not known
     * @param runningOp   the interaction the running chain stands on; {@code null} for none
     */
    static boolean interrupts(InteractionType type, RootInteraction root, InteractionType runningType,
                              RootInteraction runningRoot, Interaction runningOp) {
        if (runningRoot != null && interrupts(type, root, runningType, runningRoot.tags, runningRoot.rules)) {
            return true;
        }
        return runningOp != null && interrupts(type, root, runningType, runningOp.tags, runningOp.rules);
    }

    /** {@code InteractionRules.validateInterrupts}: the new root's rules against one set of the running chain's. */
    private static boolean interrupts(InteractionType type, RootInteraction self, InteractionType otherType,
                                      int[] otherTags, InteractionRules other) {
        int[] selfTags = self == null ? null : self.tags;
        if (other != null && contains(other.interruptedBy, type)
                && (other.interruptedByBypassIndex == NO_TAG || !contains(selfTags, other.interruptedByBypassIndex))) {
            return true;
        }
        InteractionRules mine = self == null ? null : self.rules;
        return mine != null && contains(mine.interrupting, otherType)
                && (mine.interruptingBypassIndex == NO_TAG || !contains(otherTags, mine.interruptingBypassIndex));
    }

    private static boolean contains(InteractionType[] types, InteractionType type) {
        if (types == null || type == null) {
            return false;
        }
        for (InteractionType t : types) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(int[] tags, int tag) {
        if (tags == null) {
            return false;
        }
        for (int t : tags) {
            if (t == tag) {
                return true;
            }
        }
        return false;
    }
}
