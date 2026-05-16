package meridian.core.impl.interaction;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.core.api.BlockPos;
import meridian.core.api.InteractionControl;
import meridian.core.impl.InteractionRegistry;
import meridian.core.impl.InventoryTracker;
import meridian.protocol.BlockFace;
import meridian.protocol.BlockPosition;
import meridian.protocol.InteractionChainData;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionSyncData;
import meridian.protocol.InteractionType;
import meridian.protocol.RootInteraction;
import meridian.protocol.packets.interaction.SyncInteractionChain;
import meridian.protocol.packets.interaction.SyncInteractionChains;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live {@link InteractionControl} — builds and sends forged
 * {@code SyncInteractionChains}.
 *
 * <p>A {@code Use} chain is sent bare ({@code interactionData = null}): the
 * server self-runs it, so harvesting needs nothing more. A {@code Secondary}
 * chain (water / plant) carries a populated {@code interactionData} — its
 * charge / place operations require client data — produced by flattening the
 * root interaction ({@link InteractionFlattener}) and simulating it
 * ({@link InteractionSimulator}).
 *
 * <p>The root interaction id for water / plant is server-specific; it is
 * learned by observing the player's own chains (per {@link InteractionType}).
 * Until the player performs the action once, that forge is skipped with a log.
 */
public final class InteractionControlImpl implements InteractionControl {
    private static final Logger log = LoggerFactory.getLogger("meridian-core");

    /** What the simulator should build for the chain's {@code interactionData}. */
    private enum DataKind { NONE, BLOCK, PLACEMENT }

    private final InteractionRegistry registry;
    private final InventoryTracker inventory;

    private volatile ProxySession session;
    private volatile BlockPosition targetBlock;
    private final AtomicInteger chainId = new AtomicInteger(0);
    /** interaction type &rarr; root id, learned from the player's own chains. */
    private final Map<InteractionType, Integer> observedRoot = new ConcurrentHashMap<>();

    public InteractionControlImpl(InteractionRegistry registry, InventoryTracker inventory) {
        this.registry = registry;
        this.inventory = inventory;
    }

    /** Creates the C2S observer that feeds this control — register at MONITOR. */
    public PacketHandler newObserver() {
        return new InteractionObserver(this);
    }

    // ------------------------------------------------------------------
    // Fed by InteractionObserver
    // ------------------------------------------------------------------

    void bind(ProxySession session) {
        this.session = session;
    }

    void onTargetBlock(BlockPosition block) {
        if (block != null) {
            this.targetBlock = block;
        }
    }

    /** Learns the chainId high-water mark and the root id of each interaction type. */
    void onClientChain(SyncInteractionChain chain) {
        if (chain.chainId > chainId.get()) {
            chainId.set(chain.chainId);
        }
        if (chain.initial && chain.interactionData != null) {
            for (InteractionSyncData d : chain.interactionData) {
                if (d != null) {
                    observedRoot.put(chain.interactionType, d.rootInteraction);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // InteractionControl
    // ------------------------------------------------------------------

    @Override
    public boolean available() {
        return session != null;
    }

    @Override
    public Optional<BlockPos> targetedBlock() {
        BlockPosition b = targetBlock;
        return b == null ? Optional.empty() : Optional.of(new BlockPos(b.x, b.y, b.z));
    }

    @Override
    public void useOnBlock(BlockPos pos) {
        forge(pos, InteractionType.Use, DataKind.NONE);
    }

    @Override
    public void waterBlock(BlockPos pos) {
        forge(pos, InteractionType.Secondary, DataKind.BLOCK);
    }

    @Override
    public void plantOnBlock(BlockPos pos) {
        forge(pos, InteractionType.Secondary, DataKind.PLACEMENT);
    }

    // ------------------------------------------------------------------
    // Forge
    // ------------------------------------------------------------------

    private void forge(BlockPos pos, InteractionType type, DataKind dataKind) {
        ProxySession s = session;
        if (s == null) {
            log.warn("meridian-core: interaction {} requested but no session yet", type);
            return;
        }

        SyncInteractionChain chain = new SyncInteractionChain();
        chain.activeHotbarSlot = inventory.activeHotbarSlot();
        chain.activeUtilitySlot = inventory.activeUtilitySlot();
        chain.activeToolsSlot = inventory.activeToolsSlot();
        chain.itemInHandId = inventory.itemInHandId();
        chain.utilityItemId = inventory.utilityItemId();
        chain.toolsItemId = inventory.toolsItemId();
        chain.initial = true;
        chain.desync = false;
        chain.overrideRootInteraction = Integer.MIN_VALUE;
        chain.interactionType = type;
        chain.equipSlot = inventory.activeHotbarSlot();
        chain.chainId = chainId.incrementAndGet();
        chain.forkedId = null;
        chain.state = InteractionState.Finished;
        chain.newForks = null;
        chain.operationBaseIndex = 0;

        InteractionChainData data = new InteractionChainData();
        data.entityId = -1;
        data.proxyId = new UUID(0L, 0L);
        data.blockPosition = new BlockPosition(pos.x(), pos.y(), pos.z());
        data.targetSlot = Integer.MIN_VALUE;
        chain.data = data;

        if (dataKind == DataKind.NONE) {
            chain.interactionData = null; // server self-runs the chain
        } else {
            chain.interactionData = buildInteractionData(type, dataKind);
            if (chain.interactionData == null) {
                return; // root not learned yet — logged in buildInteractionData
            }
        }

        SyncInteractionChains packet = new SyncInteractionChains();
        packet.updates = new SyncInteractionChain[] {chain};
        s.sendToServer(packet);
        log.info("meridian-core: forged {} chain {} at ({},{},{})",
                type, chain.chainId, pos.x(), pos.y(), pos.z());
    }

    /** Flattens + simulates the root for {@code type} into {@code InteractionSyncData[]}. */
    private InteractionSyncData[] buildInteractionData(InteractionType type, DataKind dataKind) {
        Integer rootId = observedRoot.get(type);
        if (rootId == null) {
            log.warn("meridian-core: no observed root for {} yet — perform the action "
                    + "once manually so core can learn it", type);
            return null;
        }
        RootInteraction root = registry.root(rootId).orElse(null);
        if (root == null) {
            log.warn("meridian-core: root {} not in registry", rootId);
            return null;
        }
        CompiledInteraction compiled = InteractionFlattener.compile(
                rootId, root.interactions, id -> registry.interaction(id).orElse(null));

        BlockPosition target = targetBlock;
        InteractionContext ctx = dataKind == DataKind.PLACEMENT
                ? InteractionContext.ofPlacement(target, BlockFace.Up)
                : InteractionContext.ofBlock(target, BlockFace.Up);

        List<InteractionSyncData> ops = InteractionSimulator.simulate(compiled, ctx);
        log.info("meridian-core: simulated root {} ({} ops) for {}", rootId, ops.size(), type);
        return ops.toArray(new InteractionSyncData[0]);
    }
}
