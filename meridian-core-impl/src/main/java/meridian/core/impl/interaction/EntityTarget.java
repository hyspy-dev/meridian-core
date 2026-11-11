package meridian.core.impl.interaction;

import java.util.Map;
import meridian.protocol.Direction;
import meridian.protocol.InteractionType;
import meridian.protocol.Position;
import meridian.protocol.SelectedHitEntity;
import org.joml.Vector3f;

/**
 * An entity an action is aimed at, as the client reports it - and the attacker's own pose at
 * that moment.
 *
 * <p>The server takes a player's entity hits from the client: a {@code SelectInteraction} lists
 * what it hit ({@code hitEntities}: network id, hit point, where the entity stood and faced) and
 * where the attacker stood and faced ({@code attackerPos}/{@code attackerRot}); the damage that
 * follows reads its angles and knockback from these. A {@code UseEntityInteraction} names the
 * entity by id and runs the root the entity's own {@code Interactions} hold for the chain's type.
 *
 * @param networkId    the entity's network id
 * @param position     where the entity stands (its feet)
 * @param body         which way its body faces
 * @param hitLocation  the point on it the hit landed
 * @param attackerPos  where the player stands (its feet)
 * @param attackerRot  which way the player looks
 * @param interactions the entity's own interaction roots by type, as its {@code Interactions}
 *                     component last said; empty when none were seen
 * @param effects      the entity's active effects, by effect index
 * @param inReach      whether the player reaches it with what it holds - the server checks a
 *                     damage and a use against this
 */
public record EntityTarget(int networkId, Position position, Direction body, Vector3f hitLocation,
                           Position attackerPos, Direction attackerRot,
                           Map<InteractionType, Integer> interactions,
                           java.util.Set<Integer> effects, boolean inReach) {

    /** The same entity, reached or not. */
    EntityTarget withReach(boolean reached) {
        return new EntityTarget(networkId, position, body, hitLocation, attackerPos, attackerRot,
                interactions, effects, reached);
    }

    /** The squared distance from the player's eyes to where the entity stands - the server's measure. */
    double distanceSqFromEyes(double eyeHeight) {
        double dx = position.x - attackerPos.x;
        double dy = position.y - (attackerPos.y + eyeHeight);
        double dz = position.z - attackerPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The entry a {@code SelectInteraction} lists for this entity. */
    SelectedHitEntity hit() {
        SelectedHitEntity hit = new SelectedHitEntity();
        hit.networkId = networkId;
        hit.hitLocation = new Vector3f(hitLocation);
        hit.position = new Position(position.x, position.y, position.z);
        hit.bodyRotation = new Direction(body.yaw, body.pitch, body.roll);
        return hit;
    }

    /** The entity's own root for {@code type}, or {@code null} when it has none. */
    Integer rootFor(InteractionType type) {
        return interactions == null ? null : interactions.get(type);
    }
}
