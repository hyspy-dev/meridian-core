package meridian.core.impl.assets;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import meridian.core.api.Combat;
import meridian.core.api.GameAssets;

/**
 * What a kind of NPC senses, read from role files shaped as the game's are: a template with
 * {@code Parameters}, a variant that changes some ({@code Modify}), a variant of a variant, two roles
 * wearing one model.
 */
public class NpcRolesTest {

    static GameAssets game(Map<String, String> files) {
        return new GameAssets() {
            @Override public boolean available() { return true; }
            @Override public Optional<String> gameVersion() { return Optional.of("test"); }
            @Override public Optional<Path> gameDirectory() { return Optional.of(Path.of("test")); }
            @Override public Optional<byte[]> read(String path) {
                String s = files.get(path);
                return s == null ? Optional.empty() : Optional.of(s.getBytes(StandardCharsets.UTF_8));
            }
            @Override public List<String> list(String prefix) {
                return files.keySet().stream().filter(p -> p.startsWith(prefix)).sorted().toList();
            }
            @Override public Source source(String path) { return files.containsKey(path) ? Source.GAME : Source.NONE; }
        };
    }

    public void testAVariantSensesAsItsTemplateSaveWhatItChanges() {
        Map<String, String> files = new HashMap<>();
        files.put("Server/NPC/Roles/_Core/Template_Predator.json", """
                {"Type": "Abstract", "Appearance": {"Compute": "Appearance"}, "DefaultPlayerAttitude": "Hostile",
                 "Parameters": {"Appearance": {"Value": "Fox"}, "ViewRange": {"Value": 24}, "ViewSector": {"Value": 120},
                                "HearingRange": {"Value": 12}, "AlertedRange": {"Value": 28}, "MaxSpeed": {"Value": 8}}}""");
        files.put("Server/NPC/Roles/Undead/Zombie.json", """
                {"Type": "Variant", "Reference": "Template_Predator",
                 "Modify": {"Appearance": "Zombie", "ViewRange": 12, "HearingRange": 8, "MaxSpeed": 9, "AttackDistance": 2,
                            "NameTranslationKey": {"Compute": "NameTranslationKey"}}}""");
        files.put("Server/NPC/Roles/Undead/Zombie_Burnt.json", """
                {"Type": "Variant", "Reference": "Zombie", "Modify": {"Appearance": "Zombie_Burnt", "ViewRange": 16}}""");
        files.put("Server/NPC/Roles/Undead/Zombie_Elder.json", """
                {"Type": "Variant", "Reference": "Zombie", "Modify": {"ViewRange": 20}}""");
        files.put("Server/NPC/Roles/Critter/Sheep.json", """
                {"Type": "Generic", "Appearance": "Sheep", "MaxSpeed": 4,
                 "Parameters": {"ViewRange": {"Value": 10}}}""");
        // A turret wearing the zombie's model, throwing from afar: the zombie is none the farther for it.
        files.put("Server/NPC/Roles/Undead/Zombie_Turret.json", """
                {"Type": "Variant", "Reference": "Zombie", "Modify": {"AttackDistance": 25, "ViewRange": 15}}""");
        // ... and a model no role is named after: the least any of them says.
        files.put("Server/NPC/Roles/Undead/Ghoul_Small.json", """
                {"Type": "Variant", "Reference": "Zombie", "Modify": {"Appearance": "Ghoul", "AttackDistance": 3}}""");
        files.put("Server/NPC/Roles/Undead/Ghoul_Thrower.json", """
                {"Type": "Variant", "Reference": "Zombie", "Modify": {"Appearance": "Ghoul", "AttackDistance": 14}}""");
        files.put("Server/NPC/Roles/Critter/Broken.json", "{ not json");
        files.put("Server/NPC/Roles/Critter/Rock.json", """
                {"Type": "Generic", "Appearance": "Rock"}""");
        Map<String, Combat.Senses> senses = NpcRoles.read(game(files));

        Combat.Senses zombie = senses.get("zombie");
        check(zombie != null, "no zombie: " + senses.keySet());
        // Two roles wear the zombie's model - the plain one (12) and the elder (20): the keenest.
        check(zombie.viewRange() == 20 && zombie.hearingRange() == 8 && zombie.maxSpeed() == 9, "the zombie: " + zombie);
        check(zombie.viewSector() == 120 && zombie.alertedRange() == 28, "not its template's where it changes nothing: " + zombie);
        check(zombie.attackDistance() == 2, "how far off it attacks from - its own role's, not the turret's: " + zombie);
        Combat.Senses ghoul = senses.get("ghoul");
        check(ghoul != null && ghoul.attackDistance() == 3, "a model no role is named after: the least - " + ghoul);
        Combat.Senses burnt = senses.get("zombie_burnt");
        check(burnt != null && burnt.viewRange() == 16 && burnt.hearingRange() == 8, "a variant of a variant: " + burnt);
        Combat.Senses fox = senses.get("fox");
        check(fox != null && fox.viewRange() == 24 && fox.maxSpeed() == 8, "the template's own model: " + fox);
        Combat.Senses sheep = senses.get("sheep");
        check(sheep != null && sheep.viewRange() == 10 && sheep.maxSpeed() == 4, "a role with plain values: " + sheep);
        check(!senses.containsKey("rock"), "a role that sees nothing is no kind to mind");
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
