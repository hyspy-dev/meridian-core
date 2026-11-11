package meridian.core.impl.interaction;

import meridian.protocol.ChangeVelocityType;
import meridian.protocol.MovementDirection;
import meridian.protocol.VelocityConfig;

/**
 * The body a forged chain pushes: an {@code ApplyForceInteraction} is the client's to run - it
 * pushes its own body and tells the server how the push ended (on the ground, or the time ran
 * out). For a headless session core's body is that body; elsewhere there is none.
 */
public interface ForceBody {

    /** Pushes the body as a {@code ChangeVelocity} would. */
    void push(double x, double y, double z, ChangeVelocityType type, VelocityConfig config);

    /** On the ground, or held up as it is: in a fluid, climbing ({@code ApplyForceInteraction}'s ground check). */
    boolean grounded();

    /** The look's yaw, radians. */
    float yaw();

    /** The look's pitch, radians, up positive. */
    float pitch();

    /** Which way the movement keys are held ({@code MovementConditionInteraction} branches on it). */
    MovementDirection direction();
}
