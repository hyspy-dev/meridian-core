package meridian.core.impl;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.InteractionControl;
import meridian.core.api.InteractionOutcome;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;

/**
 * The local {@link Player} — position from {@link EntityTrackerImpl}, hotbar
 * from {@link InventoryTracker}, the slot-switch action via {@link InteractionControl}.
 *
 * <p>The crosshair raycast for {@link #lookedAtBlock} lives here so every
 * module gets the same "what is the player aiming at" answer without each
 * one re-implementing the DDA. {@code World} is bound after construction
 * via {@link #bindWorld} because {@link WorldImpl} creates the player and
 * a constructor-pass would leak {@code this} from the world's own ctor.
 */
final class PlayerImpl implements Player {

    /** Default reach for {@link #lookedAtBlock()} when callers don't pass
     *  an explicit range. Picked to sit just past the player's normal
     *  block-interact reach — far enough that the picker matches the
     *  in-game crosshair for ordinary use, short enough that it doesn't
     *  silently latch onto a block six tiles deeper than intended.
     *  Callers wanting a different reach use the overload. */
    private static final double DEFAULT_REACH = 7.0;
    /** Hotbar inventory section id (server {@code InventoryComponent.HOTBAR_SECTION_ID}). */
    private static final int HOTBAR_SECTION_ID = -1;

    private final EntityTrackerImpl entities;
    private final InventoryTracker inventory;
    private final InteractionControl interactions;
    private final MovementControlImpl movement;
    /** The eyes above the feet: the player's model's (core's movement keeps it). */
    private final java.util.function.DoubleSupplier eyeHeight;
    /** Set by {@link WorldImpl} after the world finishes building; needed
     *  to scan blocks for {@link #lookedAtBlock}. */
    private volatile World world;

    PlayerImpl(EntityTrackerImpl entities, InventoryTracker inventory,
               InteractionControl interactions, MovementControlImpl movement,
               java.util.function.DoubleSupplier eyeHeight) {
        this.entities = entities;
        this.inventory = inventory;
        this.interactions = interactions;
        this.movement = movement;
        this.eyeHeight = eyeHeight;
    }

    /** Late binding from {@link WorldImpl} once its own fields are set. */
    void bindWorld(World w) {
        this.world = w;
    }

    @Override
    public Vec3 position() {
        return entities.localPosition().orElse(null);
    }

    @Override
    public Vec3 eyePosition() {
        Vec3 feet = position();
        return feet == null ? null : new Vec3(feet.x(), feet.y() + eyeHeight.getAsDouble(), feet.z());
    }

    @Override
    public Vec3 lookDirection() {
        return entities.localLookDirection();
    }

    @Override
    public Optional<BlockPos> lookedAtBlock() {
        return lookedAtBlock(DEFAULT_REACH);
    }

    @Override
    public Optional<BlockPos> lookedAtBlock(double maxRange) {
        World w = world;
        Vec3 origin = eyePosition();
        Vec3 dir = lookDirection();
        if (w == null || origin == null || dir == null) return Optional.empty();
        return raycastFirstSolid(w, origin, dir, maxRange);
    }

    /**
     * Amanatides &amp; Woo voxel DDA — walks the ray cell-by-cell from the eyes, returns
     * the first non-air cell it encounters within {@code maxRange}.
     */
    private static Optional<BlockPos> raycastFirstSolid(World world, Vec3 eyes, Vec3 dir, double maxRange) {
        double ox = eyes.x(), oy = eyes.y(), oz = eyes.z();
        double dx = dir.x(), dy = dir.y(), dz = dir.z();

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int sy = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int sz = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = sx == 0 ? Double.POSITIVE_INFINITY
                : ((sx > 0 ? (x + 1 - ox) : (ox - x)) / Math.abs(dx));
        double tMaxY = sy == 0 ? Double.POSITIVE_INFINITY
                : ((sy > 0 ? (y + 1 - oy) : (oy - y)) / Math.abs(dy));
        double tMaxZ = sz == 0 ? Double.POSITIVE_INFINITY
                : ((sz > 0 ? (z + 1 - oz) : (oz - z)) / Math.abs(dz));

        double t = 0;
        int safetyCap = (int) (maxRange * 4) + 8;
        while (t <= maxRange && safetyCap-- > 0) {
            Block blk = world.blockAt(x, y, z);
            if (blk != null && blk.type() != null && !blk.isAir()) {
                return Optional.of(new BlockPos(x, y, z));
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += sx;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += sy;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += sz;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return Optional.empty();
    }

    @Override
    public String heldItem() {
        return inventory.itemInHandId();
    }

    @Override
    public int activeHotbarSlot() {
        return inventory.activeHotbarSlot();
    }

    @Override
    public void dropHeld(int quantity) {
        movement.dropItem(HOTBAR_SECTION_ID, inventory.activeHotbarSlot(), quantity);
    }

    @Override
    public int hotbarSlotOf(String substring) {
        return inventory.findHotbarSlot(substring);
    }

    @Override
    public CompletableFuture<InteractionOutcome> selectHotbarSlot(int slot) {
        return interactions.switchHotbarSlot(slot);
    }

    @Override
    public void teleport(Vec3 position) {
        movement.teleport(position.x(), position.y(), position.z());
    }

    @Override
    public void holdPosition(Vec3 target) {
        movement.holdPosition(target.x(), target.y(), target.z());
    }

    @Override
    public void clearHold() {
        movement.clearHold();
    }
}
