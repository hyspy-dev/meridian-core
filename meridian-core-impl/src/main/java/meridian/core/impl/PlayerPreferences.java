package meridian.core.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import meridian.protocol.PickupLocation;
import meridian.protocol.PlaceMode;
import meridian.protocol.packets.player.SyncPlayerPreferences;
import meridian.protocol.packets.voice.VoiceInputMode;

/**
 * The player's own preferences as the game keeps them - its {@code Settings.json} in the game's user
 * folder - made into the {@code SyncPlayerPreferences} the game sends a server on joining: the armor
 * pieces hidden from the others, where picked-up items go, the builder's settings, voice. A headless
 * client sends the same, so the server treats the player as it does when they play (their hidden
 * armor stays hidden). What the file does not hold is the server's own default.
 */
final class PlayerPreferences {

    private PlayerPreferences() {
    }

    /** The preferences from {@code <user dir>/Settings.json}; empty when there is no such file to read. */
    static Optional<SyncPlayerPreferences> fromGame(Optional<Path> userDir) {
        if (userDir == null || userDir.isEmpty()) {
            return Optional.empty();
        }
        Path file = userDir.get().resolve("Settings.json");
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);
            }
            return Optional.of(from(JsonParser.parseString(text).getAsJsonObject()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static SyncPlayerPreferences from(JsonObject settings) {
        JsonObject play = object(settings, "GameplaySettings");
        JsonObject build = object(settings, "BuilderToolsSettings");
        JsonObject voice = object(settings, "VoiceSettings");
        // What the file does not hold: the server's own defaults (PlayerSettings, PlayerCreativeSettings,
        // PlayerVoiceSettings) - what it uses for a client that sends no preferences - not the packet's zeros.
        SyncPlayerPreferences p = new SyncPlayerPreferences();
        p.creativeInteractionDistance = 10;
        p.showBuilderToolNotifications = true;
        p.placeMode = PlaceMode.Default;
        p.voiceInputMode = VoiceInputMode.PushToTalk;
        p.hideHelmet = bool(play, "HideHelmet", p.hideHelmet);
        p.hideCuirass = bool(play, "HideCuirass", p.hideCuirass);
        p.hideGauntlets = bool(play, "HideGauntlets", p.hideGauntlets);
        p.hidePants = bool(play, "HidePants", p.hidePants);
        p.armorItemsPreferredPickupLocation = pickup(play, "ArmorItemsPickupLocation", p.armorItemsPreferredPickupLocation);
        p.weaponAndToolItemsPreferredPickupLocation =
                pickup(play, "WeaponAndToolItemsPickupLocation", p.weaponAndToolItemsPreferredPickupLocation);
        p.usableItemsItemsPreferredPickupLocation =
                pickup(play, "UsableItemsPickupLocation", p.usableItemsItemsPreferredPickupLocation);
        p.solidBlockItemsPreferredPickupLocation =
                pickup(play, "SolidBlockItemsPickupLocation", p.solidBlockItemsPreferredPickupLocation);
        p.miscItemsPreferredPickupLocation = pickup(play, "MiscItemsPickupLocation", p.miscItemsPreferredPickupLocation);
        p.showEntityMarkers = bool(build, "ShowEntityMarkers", p.showEntityMarkers);
        p.allowNPCDetection = bool(build, "AllowNPCDetection", p.allowNPCDetection);
        p.respondToHit = bool(build, "RespondToHit", p.respondToHit);
        p.creativeInteractionDistance = integer(build, "CreativeInteractionDistance", p.creativeInteractionDistance);
        p.showBuilderToolNotifications = bool(build, "ShowBuilderToolsNotifications", p.showBuilderToolNotifications);
        p.noPhysics = bool(build, "NoPhysics", p.noPhysics);
        p.eraserEnabled = bool(build, "EnableEraser", p.eraserEnabled);
        JsonElement mode = build == null ? null : build.get("PlaceMode");
        if (mode != null && mode.isJsonPrimitive()) {
            try {
                p.placeMode = mode.getAsJsonPrimitive().isNumber() ? PlaceMode.fromValue(mode.getAsInt())
                        : PlaceMode.valueOf(mode.getAsString());
            } catch (RuntimeException ignored) {
                // a mode this build does not know: the default
            }
        }
        p.voiceChat = bool(voice, "VoiceChatEnabled", p.voiceChat);
        p.voiceInput = bool(voice, "VoiceInputEnabled", p.voiceInput);
        JsonElement input = voice == null ? null : voice.get("InputMode");
        if (input != null && input.isJsonPrimitive() && input.getAsJsonPrimitive().isNumber()) {
            try {
                p.voiceInputMode = VoiceInputMode.fromValue(input.getAsInt());
            } catch (RuntimeException ignored) {
                // the default
            }
        }
        return p;
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
    }

    private static int integer(JsonObject o, String key, int def) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : def;
    }

    private static PickupLocation pickup(JsonObject o, String key, PickupLocation def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return def;
        }
        try {
            return PickupLocation.fromValue(e.getAsInt());
        } catch (RuntimeException ex) {
            return def;
        }
    }
}
