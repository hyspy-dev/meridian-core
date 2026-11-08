package meridian.core.impl.movement;

import meridian.core.api.Movement;
import meridian.protocol.MovementSettings;

/**
 * The numbers a body moves by: the server's movement settings ({@code UpdateMovementSettings}),
 * the game's defaults ({@code Server/Entity/MovementConfig/Default.json}) until they come, and what
 * the client does not get told - gravity (the server's {@code PhysicsConstants}), the player's
 * hitbox ({@code Models/Human/Player.json}) and the pull-up, which the client alone knows.
 */
final class BodySettings {

    /** The server's gravity, blocks per second squared. */
    static final double GRAVITY = 32;
    /** The client's movement tick. */
    static final double TICK = 1.0 / 60.0;
    /** A fall faster than this, when the body lands, hurts (the game's {@code MinFallSpeedToEngageRoll}). */
    static final double HURT_FALL_SPEED = 21;
    /** How long pulling up onto a ledge takes (the {@code Mantle_Up} animation: 60 frames). */
    static final double MANTLE_SECONDS = 1.0;
    /** The highest ledge a pull-up reaches: hands at the top of a jump. The client keeps its own; this is ours. */
    static final double MANTLE_HEIGHT = 3.0;
    /** What the body walks up without a jump. */
    static final double STEP_HEIGHT = 0.6;

    volatile double baseSpeed = 5.5;
    volatile double jumpForce = 11.8;
    volatile double acceleration = 0.1;
    volatile double walkMultiplier = 0.3;
    volatile double runMultiplier = 1.0;
    volatile double sprintMultiplier = 1.273;

    void update(MovementSettings s) {
        if (s == null) {
            return;
        }
        if (s.baseSpeed > 0) {
            baseSpeed = s.baseSpeed;
        }
        if (s.jumpForce > 0) {
            jumpForce = s.jumpForce;
        }
        if (s.acceleration > 0) {
            acceleration = s.acceleration;
        }
        if (s.forwardWalkSpeedMultiplier > 0) {
            walkMultiplier = s.forwardWalkSpeedMultiplier;
        }
        if (s.forwardRunSpeedMultiplier > 0) {
            runMultiplier = s.forwardRunSpeedMultiplier;
        }
        if (s.forwardSprintSpeedMultiplier > 0) {
            sprintMultiplier = s.forwardSprintSpeedMultiplier;
        }
    }

    double speed(Movement.Gait gait) {
        return baseSpeed * switch (gait) {
            case WALK -> walkMultiplier;
            case RUN -> runMultiplier;
            case SPRINT -> runMultiplier * sprintMultiplier;
        };
    }

    /** The top of a jump, above the feet. */
    double jumpApex() {
        return jumpForce * jumpForce / (2 * GRAVITY);
    }

    Movement.Capabilities capabilities() {
        double apex = jumpApex();
        // A jump onto what the feet clear with room to spare; a drop that lands under the hurt speed.
        double jump = Math.max(STEP_HEIGHT, Math.min(1.25, apex - 0.5));
        double safe = HURT_FALL_SPEED * HURT_FALL_SPEED / (2 * GRAVITY) - 0.5;
        return new Movement.Capabilities(2 * Terrain.HALF, Terrain.HEIGHT, 1.6, STEP_HEIGHT, jump,
                Math.min(MANTLE_HEIGHT, apex + 1.0), safe, speed(Movement.Gait.WALK), speed(Movement.Gait.RUN),
                speed(Movement.Gait.SPRINT));
    }
}
