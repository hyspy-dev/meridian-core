package meridian.core.impl;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.protocol.BlockBreaking;
import meridian.protocol.BlockType;
import meridian.protocol.ItemBase;
import meridian.protocol.ItemToolSpec;
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
    /** The server's items and block types - what tool gathers what. */
    private final ItemRegistry items;
    private final WorldStateImpl worldState;
    /** Set by {@link WorldImpl} after the world finishes building; needed
     *  to scan blocks for {@link #lookedAtBlock}. */
    private volatile World world;

    PlayerImpl(EntityTrackerImpl entities, InventoryTracker inventory,
               InteractionControl interactions, MovementControlImpl movement,
               java.util.function.DoubleSupplier eyeHeight, ItemRegistry items, WorldStateImpl worldState) {
        this.entities = entities;
        this.inventory = inventory;
        this.interactions = interactions;
        this.movement = movement;
        this.eyeHeight = eyeHeight;
        this.items = items;
        this.worldState = worldState;
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
    public String hotbarItem(int slot) {
        return inventory.hotbarItem(slot);
    }

    @Override
    public int bestToolSlot(BlockView block) {
        BlockBreaking breaking = breaking(block);
        if (breaking == null) {
            return -1;
        }
        Map<Integer, String> hotbar = new java.util.TreeMap<>(inventory.hotbarItems());
        int best = strongest(hotbar, breaking, id -> true);
        if (best >= 0 || breaksAsHand(items.item(hotbar.get(inventory.activeHotbarSlot())).orElse(null))) {
            return best;
        }
        // No tool for it, and what is held does not break it at all - a weapon, a builder tool, a
        // tool for other blocks: the bare hand, or an item that is no tool, does what a hand does.
        for (int slot = 0; slot < inventory.hotbarCapacity(); slot++) {
            if (!hotbar.containsKey(slot)) {
                return slot;
            }
        }
        for (Map.Entry<Integer, String> e : hotbar.entrySet()) {
            if (breaksAsHand(items.item(e.getValue()).orElse(null))) {
                return e.getKey();
            }
        }
        return -1;
    }

    @Override
    public int bestToolSlot(BlockView block, java.util.function.Predicate<String> among) {
        BlockBreaking breaking = breaking(block);
        return breaking == null || among == null ? -1
                : strongest(new java.util.TreeMap<>(inventory.hotbarItems()), breaking, among);
    }

    @Override
    public int bestWeaponSlot(java.util.function.Predicate<String> among) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Integer, String> e : new java.util.TreeMap<>(inventory.hotbarItems()).entrySet()) {
            String id = e.getValue();
            ItemBase item = items.item(id).orElse(null);
            if (item == null || item.weapon == null || (among != null && !among.test(id))
                    || inventory.hotbarBroken(e.getKey())) {
                continue;
            }
            java.util.OptionalDouble reach = interactions.hitReach(id);
            if (reach.isEmpty()) {
                continue;     // a bow, a shield: no swing that hits
            }
            double score = item.itemLevel * 100.0 + item.qualityIndex + reach.getAsDouble()
                    - (inventory.hotbarSpent(e.getKey()) ? 1e6 : 0);   // repaired as far as it goes: last
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        return best;
    }

    @Override
    public boolean placesBlock(String itemId) {
        ItemBase item = itemId == null ? null : items.item(itemId).orElse(null);
        return item != null && item.blockId > 0 && !item.consumable && item.tool == null && item.weapon == null;
    }

    @Override
    public double toolPower(int slot, BlockView block) {
        BlockBreaking breaking = breaking(block);
        if (breaking == null) {
            return 0;
        }
        ItemBase item = items.item(inventory.hotbarItem(slot)).orElse(null);
        double p = power(item, breaking);
        return p < 0 ? HAND_POWER : p;
    }

    /** What a bare hand is taken to hit with: the server's default specs are not sent to clients. */
    private static final double HAND_POWER = 0.1;

    /** The block's breaking, from the server's type for it; {@code null} when it takes none. */
    private BlockBreaking breaking(BlockView block) {
        // The server's getSpecPowerDamageBlock: the spec of the block's gather type, at no less than
        // its quality, gives the power; a weapon or a builder tool gathers nothing.
        BlockType type = block == null ? null : worldState.blockTypeById(block.id());
        BlockBreaking breaking = type == null || type.gathering == null ? null : type.gathering.breaking;
        return breaking == null || breaking.gatherType == null ? null : breaking;
    }

    /**
     * The slot of the strongest tool for the breaking among the items {@code among} accepts, the
     * faster on a tie; {@code -1} for none.
     */
    private int strongest(Map<Integer, String> hotbar, BlockBreaking breaking,
                          java.util.function.Predicate<String> among) {
        java.util.List<ToolChoice> candidates = new java.util.ArrayList<>();
        for (Map.Entry<Integer, String> e : hotbar.entrySet()) {
            if (!among.test(e.getValue())) {
                continue;
            }
            ItemBase item = items.item(e.getValue()).orElse(null);
            double p = power(item, breaking);
            if (p <= 0) {
                continue;                           // no tool for it, or none at all
            }
            candidates.add(new ToolChoice(e.getKey(), kindOf(e.getValue()), p, item.tool.speed,
                    inventory.hotbarBroken(e.getKey()), inventory.hotbarSpent(e.getKey())));
        }
        return ToolChoice.pick(candidates);
    }

    /** A tool's kind: its id up to the material - {@code Tool_Pickaxe_} of {@code Tool_Pickaxe_Iron}. */
    static String kindOf(String itemId) {
        int first = itemId.indexOf('_');
        int second = first < 0 ? -1 : itemId.indexOf('_', first + 1);
        return second < 0 ? itemId : itemId.substring(0, second + 1);
    }

    /**
     * The power the item breaks with: its spec for the gather type at no less than the quality; 0
     * for a weapon, a builder tool, or a tool without such a spec - it breaks nothing; -1 for no item
     * or one that is no tool - it breaks as a bare hand does.
     */
    private static double power(ItemBase item, BlockBreaking breaking) {
        if (breaksAsHand(item)) {
            return -1;
        }
        if (item.weapon != null || item.builderToolData != null || item.tool == null || item.tool.specs == null) {
            return 0;
        }
        for (ItemToolSpec spec : item.tool.specs) {
            if (spec != null && breaking.gatherType.equals(spec.gatherType)) {
                return spec.quality >= breaking.quality ? spec.power : 0;
            }
        }
        return 0;
    }

    /**
     * Whether an item breaks blocks as the bare hand does - the server's default spec for the gather
     * type: no item, or one that is neither a tool, a weapon nor a builder tool. A tool breaks only
     * the blocks its own specs name.
     */
    private static boolean breaksAsHand(ItemBase item) {
        return item == null || (item.tool == null && item.weapon == null && item.builderToolData == null);
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
