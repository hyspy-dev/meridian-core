package meridian.core.impl.interaction;

import java.util.Map;
import java.util.Set;
import meridian.protocol.BlockFace;
import meridian.protocol.Direction;
import meridian.protocol.GameMode;
import meridian.protocol.Interaction;
import meridian.protocol.InteractionState;
import meridian.protocol.InteractionType;
import meridian.protocol.Position;
import meridian.protocol.SelectInteraction;
import meridian.protocol.UseEntityInteraction;
import org.joml.Vector3f;

/** The main chain and each selector/use operation must agree that a distant target was not hit. */
public class EntityReachSyncTest {
    public void testSelectorOmitsOutOfReachEntity() {
        var outside = data(new SelectInteraction(), false);
        if (outside.hitEntities != null && outside.hitEntities.length != 0) {
            throw new AssertionError("reported a hit on a distant entity");
        }
        var inside = data(new SelectInteraction(), true);
        if (inside.hitEntities == null || inside.hitEntities.length != 1 || inside.hitEntities[0].networkId != 42) {
            throw new AssertionError("lost an entity that is within reach");
        }
    }

    public void testUseOmitsOutOfReachEntity() {
        if (data(new UseEntityInteraction(), false).entityId == 42) {
            throw new AssertionError("reported a distant use target");
        }
        if (data(new UseEntityInteraction(), true).entityId != 42) {
            throw new AssertionError("lost a reached use target");
        }
    }

    private static meridian.protocol.InteractionSyncData data(Interaction op, boolean reached) {
        var target = new EntityTarget(42, new Position(14, 1, 0), new Direction(), new Vector3f(),
                new Position(0, 1, 0), new Direction(), Map.of(), Set.of(), reached);
        var ctx = InteractionContext.ofBlock(InteractionType.Primary, null, null, -1, BlockFace.None,
                null, GameMode.Adventure, Map.of()).withEntity(target);
        var root = InteractionFlattener.compile(1, new int[] {1}, id -> op);
        return InteractionSimulator.syncDataFor((FlatOp.Node) root.op(0), 0, 1, InteractionState.Finished, ctx);
    }
}

