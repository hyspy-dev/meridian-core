package meridian.core.impl.movement;

import meridian.protocol.MovementSettings;

/**
 * Whether a body may fly. The 0.5.9 protocol says it with {@code MovementSettings.canFly}; later
 * builds send a {@code FlyMode} of these three, and the body's code is written against that.
 */
public enum FlyMode {
    Disabled,
    Allowed,
    Forced;

    /** The settings' word: allowed or not - 0.5.9 has no forced flight. */
    public static FlyMode of(MovementSettings s) {
        return s != null && s.canFly ? Allowed : Disabled;
    }
}
