package org.leralix.lib.data;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.leralix.lib.utils.config.ConfigTag;
import org.leralix.lib.utils.config.ConfigUtil;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SoundStorage {

    private SoundStorage() {
        throw new IllegalStateException("Utility class");
    }

    private static final Map<SoundEnum, SoundData> soundMap = new EnumMap<>(SoundEnum.class);

    public static void init(){
        ConfigurationSection soundsSection = ConfigUtil.getCustomConfig(ConfigTag.MAIN).getConfigurationSection("sounds");
        if (soundsSection == null) {
            return;
        }
        for (String key : soundsSection.getKeys(false)) {
            SoundEnum soundEnum;
            try {
                soundEnum = SoundEnum.valueOf(key);
            } catch (IllegalArgumentException e) {
                warn("Unknown sound entry '" + key + "' in config (sounds), skipping.");
                continue;
            }

            List<String> soundValues = soundsSection.getStringList(key);
            if (soundValues.size() < 3) {
                warn("Sound entry '" + key + "' must define [name, volume, pitch], skipping.");
                continue;
            }

            Sound sound = resolveSound(soundValues.get(0));
            if (sound == null) {
                warn("Unknown sound '" + soundValues.get(0) + "' for entry '" + key + "', skipping.");
                continue;
            }

            try {
                int volume = Integer.parseInt(soundValues.get(1));
                float pitch = Float.parseFloat(soundValues.get(2));
                soundMap.put(soundEnum, new SoundData(sound, volume, pitch));
            } catch (NumberFormatException e) {
                warn("Invalid volume/pitch for sound entry '" + key + "', skipping.");
            }
        }
    }

    /**
     * Resolve a configured sound name to a {@link Sound} on the registry-based API (Minecraft 26.1,
     * where {@code Sound} is no longer a plain enum).
     * Accepts both modern namespaced keys ({@code entity.player.levelup}, {@code minecraft:...})
     * and legacy enum-constant names ({@code ENTITY_PLAYER_LEVELUP}) for backward compatibility.
     *
     * @param raw the configured sound identifier
     * @return the matching {@link Sound}, or {@code null} if it is unknown
     */
    private static Sound resolveSound(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            NamespacedKey key = NamespacedKey.fromString(trimmed.toLowerCase(Locale.ROOT));
            if (key != null) {
                Sound byKey = Registry.SOUNDS.get(key);
                if (byKey != null) {
                    return byKey;
                }
            }
            // Legacy enum-constant names (current config format) via Paper's OldEnum compatibility shim.
            return Sound.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void warn(String message) {
        Bukkit.getLogger().warning("[CocoNationLib] " + message);
    }

    public static SoundData getSoundData(SoundEnum soundName){
        return soundMap.get(soundName);
    }
}
