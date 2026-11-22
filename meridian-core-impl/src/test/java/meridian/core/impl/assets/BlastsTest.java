package meridian.core.impl.assets;

import java.util.HashMap;
import java.util.Map;
import meridian.core.api.Combat;

/**
 * What explodes, read from files shaped as the game's are: a projectile config with a fuse and an
 * explosion that takes its radius from its parent, a config that changes only its parent's model, a
 * bomb that goes off where it hits, an arrow that does not explode at all.
 */
public class BlastsTest {

    public void testABombsBlastIsItsExplosionsWithWhatItsParentsSay() {
        Map<String, String> files = new HashMap<>();
        files.put("Server/Item/Interactions/Explosions/Explode_Generic.json", """
                {"Type": "Explode", "Config": {"DamageEntities": true, "EntityDamage": 20.0, "EntityDamageRadius": 4}}""");
        files.put("Server/Item/Interactions/Weapons/Bomb/Bomb_Explode.json", """
                {"Type": "Explode", "Parent": "Explode_Generic", "RunTime": 0.1}""");
        files.put("Server/Item/Interactions/NPCs/Lobber_Bomb_Explode.json", """
                {"Parent": "Bomb_Explode", "Config": {"DamageBlocks": false, "EntityDamage": 30.0, "EntityDamageRadius": 6}}""");
        files.put("Server/Item/Interactions/NPCs/Smoke_Puff.json", """
                {"Type": "Explode", "Parent": "Explode_Generic", "Config": {"DamageEntities": false}}""");
        // The lobber's: two seconds after the throw - or at once on whom it hits.
        files.put("Server/ProjectileConfigs/NPCs/Projectile_Config_Lobber_Bomb.json", """
                {"Model": "Bomb_Large_Fire_Goblin", "LaunchForce": 20,
                 "Interactions": {
                   "ProjectileSpawn": {"Cooldown": {"Cooldown": 0},
                     "Interactions": [{"Type": "Simple", "RunTime": 2}, "Lobber_Bomb_Explode", {"Type": "RemoveEntity"}]},
                   "ProjectileHit": {"Interactions": ["Lobber_Bomb_Explode"]}}}""");
        // A smaller one of the same: its parent's interactions, its own model.
        files.put("Server/ProjectileConfigs/NPCs/Projectile_Config_Lobber_Bomb_Small.json", """
                {"Parent": "Projectile_Config_Lobber_Bomb", "Model": "Bomb_Fire_Goblin"}""");
        // Goes off where it lands, with what the generic explosion says.
        files.put("Server/ProjectileConfigs/Weapons/Projectile_Config_Bangstick.json", """
                {"Model": "Bangstick", "Interactions": {"ProjectileMiss": {"Interactions": [
                   {"Type": "Simple", "RunTime": 0, "Next": "Bomb_Explode"}]}}}""");
        files.put("Server/ProjectileConfigs/Weapons/Projectile_Config_Arrow.json", """
                {"Model": "Arrow_Crude", "Interactions": {"ProjectileHit": {"Interactions": [{"Type": "DamageEntity"}]}}}""");
        files.put("Server/ProjectileConfigs/Weapons/Projectile_Config_Smoke.json", """
                {"Model": "Smoke_Bomb", "Interactions": {"ProjectileMiss": {"Interactions": ["Smoke_Puff"]}}}""");
        files.put("Server/ProjectileConfigs/Weapons/Broken.json", "{ not json");
        Map<String, Combat.Blast> blasts = Blasts.read(NpcRolesTest.game(files));

        Combat.Blast lobber = blasts.get("bomb_large_fire_goblin");
        check(lobber != null && lobber.radius() == 6 && lobber.damage() == 30 && lobber.fuseSeconds() == 2,
                "the lobber's bomb: " + lobber);
        Combat.Blast small = blasts.get("bomb_fire_goblin");
        check(small != null && small.radius() == 6 && small.fuseSeconds() == 2, "a config of its parent's interactions: " + small);
        Combat.Blast bang = blasts.get("bangstick");
        check(bang != null && bang.radius() == 4 && bang.damage() == 20 && bang.fuseSeconds() == 0,
                "one that goes off where it lands, by its parent's numbers: " + bang);
        check(!blasts.containsKey("arrow_crude"), "an arrow does not explode");
        check(!blasts.containsKey("smoke_bomb"), "an explosion that hurts nobody is no blast to mind");
    }

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }
}
