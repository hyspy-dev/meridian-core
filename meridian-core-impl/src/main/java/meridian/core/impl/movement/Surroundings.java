package meridian.core.impl.movement;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import meridian.protocol.packets.setup.ClientFeature;
import meridian.protocol.EntityEffect;
import meridian.protocol.FlyMode;
import meridian.protocol.Fluid;
import meridian.protocol.FluidFX;
import meridian.protocol.FluidFXMovementSettings;
import meridian.protocol.GameMode;
import meridian.protocol.Hitbox;
import meridian.protocol.Model;
import meridian.protocol.MovementEffects;
import meridian.protocol.MovementSettings;
import meridian.protocol.UpdateType;
import meridian.protocol.packets.assets.UpdateEntityEffects;
import meridian.protocol.packets.assets.UpdateFluidFX;
import meridian.protocol.packets.assets.UpdateFluids;

/**
 * Everything the server tells the client that its movement depends on, besides the blocks: the
 * movement settings, the player's own body (its model's hitbox, eyes, crouch), how fluids move a
 * body ({@code FluidFX}), what effects do to movement, the features turned on (mantling, sliding,
 * rolling, split velocity), the game mode and whether flying is allowed or forced.
 */
final class Surroundings {

    /** The player's body from its model. */
    record BodyShape(double width, double depth, double height, double eyeHeight, double crouchOffset) {
        double halfX() {
            return width / 2;
        }

        double halfZ() {
            return depth / 2;
        }
    }

    /** What a fluid does to a body moving in it. */
    record FluidMotion(double swimUp, double swimDown, double sink, double horizontalMultiplier, double entryMultiplier,
                       int maxLevel) {}

    /** What an effect does to movement. */
    record EffectMotion(double speedMultiplier, MovementEffects movement) {}

    private volatile MovementSettings settings;
    private volatile BodyShape shape;
    private volatile GameMode gameMode = GameMode.Adventure;
    private volatile boolean flyingSaved;
    private final Map<ClientFeature, Boolean> features = new ConcurrentHashMap<>();
    private final Map<Integer, Fluid> fluids = new ConcurrentHashMap<>();
    private final Map<Integer, FluidFX> fluidFx = new ConcurrentHashMap<>();
    private final Map<Integer, EntityEffect> effects = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Fed by the server
    // ------------------------------------------------------------------

    void settings(MovementSettings s) {
        if (s != null) {
            settings = s;
        }
    }

    void model(Model m, float entityScale) {
        if (m == null || m.hitbox == null) {
            return;
        }
        Hitbox h = m.hitbox;
        double scale = entityScale > 0 ? entityScale : 1;
        shape = new BodyShape((h.maxX - h.minX) * scale, (h.maxZ - h.minZ) * scale, (h.maxY - h.minY) * scale,
                m.eyeHeight * scale, m.crouchOffset * scale);
    }

    void gameMode(GameMode mode) {
        if (mode != null) {
            gameMode = mode;
        }
    }

    void flyingSaved(boolean flying) {
        flyingSaved = flying;
    }

    void features(Map<ClientFeature, Boolean> update) {
        if (update != null) {
            features.putAll(update);
        }
    }

    void fluids(UpdateFluids u) {
        merge(fluids, u.type, u.fluids);
    }

    void fluidFx(UpdateFluidFX u) {
        merge(fluidFx, u.type, u.fluidFX);
    }

    void effects(UpdateEntityEffects u) {
        merge(effects, u.type, u.entityEffects);
    }

    private static <T> void merge(Map<Integer, T> into, UpdateType type, Map<Integer, T> update) {
        if (type == UpdateType.Init) {
            into.clear();
        }
        if (update == null) {
            return;
        }
        if (type == UpdateType.Remove) {
            update.keySet().forEach(into::remove);
        } else {
            update.forEach((k, v) -> {
                if (v != null) {
                    into.put(k, v);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Read by the body
    // ------------------------------------------------------------------

    /** The settings, or {@code null} before the server sent them: until then the body does not move. */
    MovementSettings settings() {
        return settings;
    }

    /** The body's shape, or the player's default model's until the server sends the model. */
    BodyShape shape() {
        BodyShape s = shape;
        return s != null ? s : DEFAULT_SHAPE;
    }

    /** {@code Server/Models/Human/Player.json} - only until the server sends the player's model. */
    static final BodyShape DEFAULT_SHAPE = new BodyShape(0.65, 0.65, 1.85, 1.6, -0.4);

    boolean feature(ClientFeature f) {
        return features.getOrDefault(f, false);
    }

    GameMode gameMode() {
        return gameMode;
    }

    boolean flyingSaved() {
        return flyingSaved;
    }

    /** Whether this body may fly: the settings' {@code fly}. */
    FlyMode flyMode() {
        MovementSettings s = settings;
        return s == null || s.fly == null ? FlyMode.Disabled : s.fly;
    }

    /** What the fluid with this id does, or {@code null} for one that does not move a body (fire). */
    FluidMotion fluid(int id) {
        Fluid f = fluids.get(id);
        if (f == null) {
            return null;
        }
        FluidFX fx = fluidFx.get(f.fluidFXIndex);
        FluidFXMovementSettings m = fx == null ? null : fx.movementSettings;
        if (m == null) {
            return null;
        }
        return new FluidMotion(m.swimUpSpeed, m.swimDownSpeed, m.sinkSpeed, m.horizontalSpeedMultiplier,
                m.entryVelocityMultiplier, Math.max(1, f.maxFluidLevel));
    }

    /** A fluid's highest level, for its surface (1 when unknown). */
    int maxLevel(int id) {
        Fluid f = fluids.get(id);
        return f == null ? 1 : Math.max(1, f.maxFluidLevel);
    }

    /** What the effects with these ids do to movement together. */
    EffectMotion effects(Set<Integer> active) {
        double speed = 1;
        MovementEffects all = null;
        for (int id : active) {
            EntityEffect e = effects.get(id);
            if (e == null || e.applicationEffects == null) {
                continue;
            }
            float h = e.applicationEffects.horizontalSpeedMultiplier;
            if (h >= 0) {
                speed *= h;
            }
            MovementEffects m = e.applicationEffects.movementEffects;
            if (m != null) {
                if (all == null) {
                    all = new MovementEffects();
                }
                all.disableForward |= m.disableForward;
                all.disableBackward |= m.disableBackward;
                all.disableLeft |= m.disableLeft;
                all.disableRight |= m.disableRight;
                all.disableSprint |= m.disableSprint;
                all.disableJump |= m.disableJump;
                all.disableCrouch |= m.disableCrouch;
                all.speedMultiplier *= m.speedMultiplier;
            }
        }
        return new EffectMotion(speed, all);
    }
}
