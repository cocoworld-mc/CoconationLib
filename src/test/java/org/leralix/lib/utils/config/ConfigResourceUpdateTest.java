package org.leralix.lib.utils.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConfigResourceUpdateTest {
    @TempDir Path directory;

    private Plugin plugin(String resource) {
        return plugin(resource == null ? null : resource.getBytes(StandardCharsets.UTF_8));
    }

    private Plugin plugin(byte[] resource) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getDataFolder" -> directory.toFile();
                    case "getResource" -> resource == null ? null : new ByteArrayInputStream(resource);
                    case "getLogger" -> Logger.getLogger("ConfigResourceUpdateTest");
                    default -> throw new AssertionError("Unexpected Plugin call: " + method.getName());
                });
    }

    @Test void firstExtractionUsesValidatedWriterAndCreatesNestedParents() throws IOException {
        String yaml = "# café\nsection:\n  value: 27\n";
        ConfigUtil.saveAndUpdateResource(plugin(yaml), "modules/new/config.yml", List.of(), true);
        assertEquals(yaml, Files.readString(directory.resolve("modules/new/config.yml")));
        assertFalse(Files.exists(directory.resolve("modules/new/config.yml.bak")));
    }

    @Test void malformedOrAbsentResourceNeverCreatesTarget() {
        for (String resource : new String[]{null, "key: [", "x: 1\nx: 2\n"}) {
            assertThrows(IllegalStateException.class,
                    () -> ConfigUtil.saveAndUpdateResource(plugin(resource), "new.yml"));
            assertFalse(Files.exists(directory.resolve("new.yml")));
        }
        assertThrows(IllegalStateException.class,
                () -> ConfigUtil.saveAndUpdateResource(plugin(new byte[]{(byte) 0xc3, 0x28}), "new.yml"));
        assertFalse(Files.exists(directory.resolve("new.yml")));
    }

    @Test void invalidOrUnreadableAdminFileIsNotReplacedOrBackedUp() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "admin: [");
        assertThrows(IllegalStateException.class,
                () -> ConfigUtil.saveAndUpdateResource(plugin("admin: true\n"), "config.yml", List.of(), true));
        assertEquals("admin: [", Files.readString(file));
        assertFalse(Files.exists(directory.resolve("config.yml.bak")));
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IllegalStateException.class,
                () -> ConfigUtil.saveAndUpdateResource(plugin("admin: true\n"), "config.yml"));
        assertTrue(Files.isDirectory(file));
    }

    @Test void noMissingKeysPreserveAdminBytesAndPreviousBackupExactly() throws IOException {
        Path file = directory.resolve("config.yml");
        String actual = "# admin\r\nprice: 731\r\ncustom: {other: [a,b]}\r\n";
        Files.writeString(file, actual);
        Path backup = directory.resolve("config.yml.bak");
        Files.writeString(backup, "older: state\n");
        FileTime timestamp = FileTime.fromMillis(1234567890000L);
        Files.setLastModifiedTime(file, timestamp);
        ConfigUtil.saveAndUpdateResource(plugin("price: 2\n"), "config.yml", List.of(), true);
        assertEquals(actual, Files.readString(file));
        assertEquals(timestamp, Files.getLastModifiedTime(file));
        assertEquals("older: state\n", Files.readString(backup));
    }

    @Test void mergePreservesUnknownMapsListsScalarsAndCommentsAndIsIdempotent() throws Exception {
        Path file = directory.resolve("config.yml");
        String actual = "# mes valeurs\r\nForts:\r\n    fortCost: 876\r\n    fortCostByTier: {} # mon fallback\r\n    custom: {a: [one, two]}\r\nlist:\r\n  - custom: 1\r\n    nested: [a, b]\r\nunknown:\r\n  rate: 32 # garder\r\n";
        String reference = "Forts:\n  fortCost: 10000\n  fortCostByTier:\n    1: 750\n  radius: 10\nlist: [default]\nenabled: true\n";
        Files.writeString(file, actual);
        Plugin plugin = plugin(reference);
        ConfigUtil.saveAndUpdateResource(plugin, "config.yml", List.of("Forts.fortCostByTier"), true);
        String first = Files.readString(file);
        assertTrue(first.contains("    fortCostByTier: {} # mon fallback\r\n"));
        assertTrue(first.contains("    custom: {a: [one, two]}\r\n"));
        assertTrue(first.contains("    radius: 10\r\n"));
        assertTrue(first.contains("  rate: 32 # garder\r\n"));
        assertEquals(actual, Files.readString(directory.resolve("config.yml.bak")));
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(first);
        assertEquals(876, parsed.getInt("Forts.fortCost"));
        assertTrue(parsed.getConfigurationSection("Forts.fortCostByTier").getKeys(false).isEmpty());
        assertEquals(1, parsed.getMapList("list").size());
        for (int boot = 0; boot < 5; boot++) {
            ConfigUtil.saveAndUpdateResource(plugin, "config.yml", List.of("Forts.fortCostByTier"), true);
            assertEquals(first, Files.readString(file));
            assertEquals(actual, Files.readString(directory.resolve("config.yml.bak")));
        }
    }

    @Test void backupFailureFromPublicUpdaterPropagatesWithoutChangingAdmin() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "value: 9\n");
        Files.createDirectory(directory.resolve("config.yml.bak"));
        assertThrows(IllegalStateException.class,
                () -> ConfigUtil.saveAndUpdateResource(plugin("value: 2\nnew: true\n"), "config.yml", List.of(), true));
        assertEquals("value: 9\n", Files.readString(file));
        Files.delete(directory.resolve("config.yml.bak"));
        ConfigUtil.saveAndUpdateResource(plugin("value: 2\nnew: true\n"), "config.yml", List.of(), true);
        assertEquals("value: 9\nnew: true\n", Files.readString(file));
    }

    @Test void resourceCannotEscapePluginDataFolder() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigUtil.saveAndUpdateResource(plugin("value: 1\n"), "../outside.yml"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigUtil.saveAndUpdateResource(plugin("value: 1\n"), directory.getParent().resolve("outside.yml").toString()));
    }

    @Test void streamReadFailurePropagatesInsteadOfReturningEmptyDefaults() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException {
                throw new IOException("read denied");
            }
        };
        assertThrows(IllegalStateException.class, () -> ConfigUtil.loadFileAsList(broken));
    }

    @Test void yamlSerializationUsesSameBackupAndValidationPath() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "price: 8\n");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("price: 12\ncustom: [a,b]\n");
        ConfigUtil.saveYamlSafely(file.toFile(), yaml, true);
        assertEquals(yaml.saveToString(), Files.readString(file));
        assertEquals("price: 8\n", Files.readString(directory.resolve("config.yml.bak")));
    }
}
