package meridian.core.impl;

import meridian.protocol.ComponentUpdate;
import meridian.protocol.Direction;
import meridian.protocol.EntityUpdate;
import meridian.protocol.ItemUpdate;
import meridian.protocol.ItemWithAllMetadata;
import meridian.protocol.ModelTransform;
import meridian.protocol.NameplateUpdate;
import meridian.protocol.PlayerSkinUpdate;
import meridian.protocol.Position;
import meridian.protocol.TransformUpdate;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.JoinWorld;
import meridian.protocol.packets.player.SetClientId;

/** A world transfer must not leave ghost targets or attach old metadata to reused ids. */
public class EntityWorldChangeTest {
    public void testJoinDropsOldEntitiesAndLocalPose() {
        var tracker = new EntityTrackerImpl();
        tracker.onJoinWorld(java.util.UUID.randomUUID(), false);
        var self = new SetClientId();
        self.clientId = 1;
        tracker.onSetClientId(self);
        var movement = new ClientMovement();
        movement.absolutePosition = new Position(0, 1, 0);
        movement.lookOrientation = new Direction(0, 0, 0);
        tracker.onClientMovement(movement);
        var item = new ItemUpdate();
        item.item = new ItemWithAllMetadata();
        item.item.itemId = "Ore_Copper";
        item.item.quantity = 3;
        update(tracker, 7, position(2), new NameplateUpdate("OldPlayer"), new PlayerSkinUpdate());
        update(tracker, 8, position(3), item);
        check(tracker.isPlayer(7) && tracker.itemQuantityOf(8) == 3, "old world not populated");

        var join = new JoinWorld();
        join.worldUuid = java.util.UUID.randomUUID();
        new ServerObserver(tracker, null, null).handleS2C(null, join, null);
        check(tracker.trackedCount() == 0 && tracker.positionOf(7).isEmpty(), "ghost target survived");
        check(tracker.localEntityId().isEmpty() && tracker.localPosition().isEmpty()
                && tracker.localLookAngles() == null, "old local pose survived");
        update(tracker, 7, position(40));
        update(tracker, 8, position(41));
        check(!tracker.isPlayer(7) && tracker.nameplateOf(7).isEmpty(), "reused id inherited player");
        check(tracker.itemOf(8).isEmpty() && tracker.itemQuantityOf(8) == 0, "reused id inherited item");
        check(tracker.firstSeenAt(8).orElseThrow().x() == 41, "reused id inherited drop origin");
    }

    public void testSameWorldNoticeKeepsEntitiesUnlessClearing() {
        var tracker = new EntityTrackerImpl();
        var world = java.util.UUID.randomUUID();
        tracker.onJoinWorld(world, false);
        update(tracker, 7, position(2));
        tracker.onJoinWorld(world, false);
        check(tracker.positionOf(7).isPresent(), "non-clearing notice dropped the current world");
        tracker.onJoinWorld(world, true);
        check(tracker.positionOf(7).isEmpty(), "clearing resend retained an old entity");
    }

    private static TransformUpdate position(int x) {
        var transform = new TransformUpdate();
        transform.transform = new ModelTransform();
        transform.transform.position = new Position(x, 1, 0);
        return transform;
    }

    private static void update(EntityTrackerImpl tracker, int id, ComponentUpdate... components) {
        var entity = new EntityUpdate();
        entity.networkId = id;
        entity.updates = components;
        var packet = new EntityUpdates();
        packet.updates = new EntityUpdate[] {entity};
        tracker.onEntityUpdates(packet);
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
