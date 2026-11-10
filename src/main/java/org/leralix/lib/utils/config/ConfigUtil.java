package org.leralix.lib.utils.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * This class is used for config related utilities.
 */
public class ConfigUtil {
    ConfigUtil() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * This map is used to store the custom configs.
     */
    static final Map<ConfigTag, FileConfiguration> configs = new EnumMap<>(ConfigTag.class);

    /**
     * Get a custom config by its name.
     *
     * @param tag The tag of the config file.
     * @return The {@link FileConfiguration } object.
     */
    public static FileConfiguration getCustomConfig(final ConfigTag tag) {
        return configs.get(tag);
    }
    public static void addCustomConfig(Plugin plugin, String fileName, ConfigTag tag) {
        File configFile = new File(plugin.getDataFolder(), fileName);
        if (!configFile.exists()) {
            plugin.getLogger().severe(() -> fileName + " does not exist!");
            return;
        }
        addCustomConfig(configFile, tag);
    }

    /**
     * Load a custom config file into the memory
     *
     * @param file The file to load
     * @param tag  The tag to associate with
     */
    public static void addCustomConfig(File file, ConfigTag tag) {
        configs.put(tag, YamlConfiguration.loadConfiguration(file));
    }



    static boolean containsKey(Collection<String> blackListedWords, String key) {
        for (String word : blackListedWords) {
            if (key.startsWith(word)) {
                return true;
            }
        }
        return false;
    }

    public static void saveAndUpdateResource(Plugin plugin, final String fileName) {
        saveAndUpdateResource(plugin, fileName, Collections.emptyList());
    }

    /**
     * Save and update a resource file. If some lines are missing in the current file, they will be added at the correct position.
     *
     * @param fileName The name of the resource file.
     */
    public static void saveAndUpdateResource(Plugin plugin, final String fileName, Collection<String> sectionBlacklist) {
        saveAndUpdateResource(plugin, fileName, sectionBlacklist, false);
    }

    /**
     * Same as {@link #saveAndUpdateResource(Plugin, String, Collection)} but, when {@code backup} is
     * true and an update is actually written, a {@code <file>.bak} copy is saved first (overwriting
     * any previous backup). Useful for auto-completion of split config files.
     *
     * @param fileName         The name of the resource file (may be a nested path, e.g. {@code modules/towns/towns.yml}).
     * @param sectionBlacklist Exact section paths whose existing on-disk subtree is preserved verbatim.
     * @param backup           Whether to write a {@code .bak} before rewriting.
     */
    public static void saveAndUpdateResource(Plugin plugin, final String fileName, Collection<String> sectionBlacklist, boolean backup) {
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Resource path escapes plugin data folder: " + fileName);
        }
        for (Path parent = target; parent != null && parent.startsWith(root); parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) {
                throw new IllegalArgumentException("Symbolic resource path refused: " + fileName);
            }
        }
        try (InputStream resource = plugin.getResource(fileName)) {
            if (resource == null) {
                throw new IllegalStateException("Missing embedded configuration resource: " + fileName);
            }
            String reference = SafeYamlFile.decode(resource.readAllBytes(), fileName + " resource");
            SafeYamlFile.validate(reference, fileName + " resource");
            byte[] original = SafeYamlFile.readExisting(target);
            String candidate = reference;
            if (original != null) {
                String actual = SafeYamlFile.decode(original, target.toString());
                SafeYamlFile.validate(actual, target.toString());
                Optional<List<String>> merged = mergeAndPreserveLines(reference.lines().toList(), actual.lines().toList(), sectionBlacklist);
                if (merged.isEmpty()) {
                    return;
                }
                String separator = actual.contains("\r\n") ? "\r\n" : "\n";
                candidate = String.join(separator, merged.get()) + separator;
            }
            SafeYamlFile.SYSTEM.write(target, candidate, backup, original);
            plugin.getLogger().info(() -> "The file " + fileName + " has been updated with missing lines.");
        } catch (IOException failure) {
            throw SafeYamlFile.problem("update resource " + target, failure);
        }
    }
    /**
     * Extract (first run) and line-merge update (subsequent runs) a whole tree of resource files in
     * a single pass. Each entry maps a data-folder-relative path to its section blacklist. A
     * {@code .bak} backup is written before any rewrite.
     *
     * @param pathToBlacklist ordered map of resource path → section blacklist (value may be null).
     */
    public static void saveAndUpdateResources(Plugin plugin, Map<String, Collection<String>> pathToBlacklist) {
        for (Map.Entry<String, Collection<String>> entry : pathToBlacklist.entrySet()) {
            Collection<String> blacklist = entry.getValue() == null ? Collections.emptyList() : entry.getValue();
            saveAndUpdateResource(plugin, entry.getKey(), blacklist, true);
        }
    }

    /**
     * Aggregate several YAML resource files (relative to the plugin data folder) into a single
     * in-memory {@link FileConfiguration}, merging TOP-LEVEL keys. Each top-level key is expected to
     * live in exactly one file; a collision keeps the first occurrence and logs a warning. Missing
     * files are skipped (warning). Nothing is read from disk at game runtime — call at boot/reload.
     *
     * @param plugin        the owning plugin (data folder + logging).
     * @param resourcePaths ordered list of data-folder-relative file paths.
     * @return the merged configuration.
     */
    public static FileConfiguration aggregate(Plugin plugin, List<String> resourcePaths) {
        List<FileConfiguration> sources = new ArrayList<>();
        for (String path : resourcePaths) {
            File file = new File(plugin.getDataFolder(), path);
            if (!file.exists()) {
                plugin.getLogger().warning(() -> "[Config] aggregate: missing file, skipped: " + path);
                continue;
            }
            sources.add(YamlConfiguration.loadConfiguration(file));
        }
        Set<String> collisions = new LinkedHashSet<>();
        FileConfiguration merged = mergeTopLevel(sources, collisions);
        for (String key : collisions) {
            plugin.getLogger().warning("[Config] Top-level key collision '" + key
                    + "' — first occurrence kept. Each key must live in exactly one module file.");
        }
        return merged;
    }

    /**
     * Merge the top-level keys of several configurations into a fresh {@link YamlConfiguration}. The
     * first occurrence of a duplicated top-level key wins; duplicates are recorded in
     * {@code collisionsOut} when it is non-null.
     */
    static FileConfiguration mergeTopLevel(List<FileConfiguration> sources, Set<String> collisionsOut) {
        YamlConfiguration merged = new YamlConfiguration();
        Set<String> seen = new HashSet<>();
        for (FileConfiguration source : sources) {
            for (String key : source.getKeys(false)) {
                if (!seen.add(key)) {
                    if (collisionsOut != null) {
                        collisionsOut.add(key);
                    }
                    continue;
                }
                merged.set(key, source.get(key));
            }
        }
        return merged;
    }

    /**
     * Pure collision detection over already-extracted key sets (Bukkit-free, unit-testable). A key
     * present in more than one source collides.
     *
     * @return colliding keys, in first-seen order.
     */
    static Set<String> detectCollisions(List<? extends Collection<String>> keySets) {
        Set<String> seen = new HashSet<>();
        Set<String> collisions = new LinkedHashSet<>();
        for (Collection<String> keys : keySets) {
            for (String key : keys) {
                if (!seen.add(key)) {
                    collisions.add(key);
                }
            }
        }
        return collisions;
    }

    /** Read UTF-8 YAML strictly before any migration or update is prepared. */
    public static String readYamlSafely(File file) {
        return SafeYamlFile.read(file.toPath().toAbsolutePath().normalize());
    }

    /**
     * Write validated UTF-8 YAML through a forced sibling candidate and an atomic replacement.
     * When requested, an exact .bak of the previous target is committed before replacement.
     * Failures (including unsupported atomic moves) are propagated as contextual unchecked errors.
     * No-op content leaves both target and backup untouched. Scheduling is the caller's responsibility.
     */
    public static void writeYamlSafely(File file, String yaml, boolean backup) {
        SafeYamlFile.SYSTEM.write(file, yaml, backup);
    }

    /** Compare the original read by a migration as well as the file just before replacement. */
    public static void writeYamlSafely(File file, String yaml, boolean backup, String expectedOriginal) {
        SafeYamlFile.SYSTEM.write(file.toPath().toAbsolutePath().normalize(), yaml, backup,
                expectedOriginal == null ? null : expectedOriginal.getBytes(StandardCharsets.UTF_8));
    }

    /** Serialize Bukkit's YAML (including comments enabled by its caller) using the same writer. */
    public static void saveYamlSafely(File file, FileConfiguration yaml, boolean backup) {
        writeYamlSafely(file, yaml.saveToString(), backup);
    }

    /** Atomic backup; absent source is a no-op, all other failures propagate. */
    static void backupFile(File file) {
        if (file != null) {
            SafeYamlFile.SYSTEM.backup(file);
        }
    }

    static List<String> loadFileAsList(InputStream file) {
        if (file == null) {
            throw new IllegalArgumentException("Missing configuration input stream");
        }
        try (file) {
            return SafeYamlFile.decode(file.readAllBytes(), "configuration stream").lines().toList();
        } catch (IOException failure) {
            throw SafeYamlFile.problem("read configuration stream", failure);
        }
    }

    static List<String> loadFileAsList(File file) {
        return SafeYamlFile.read(file.toPath()).lines().toList();
    }

    static Optional<List<String>> mergeAndPreserveLines(List<String> reference, List<String> current) {
        return mergeAndPreserveLines(reference, current, Collections.emptyList());
    }

    static Optional<List<String>> mergeAndPreserveLines(List<String> reference, List<String> current,
                                                        Collection<String> sectionBlacklist) {
        return YamlLineMerger.merge(reference, current, sectionBlacklist);
    }

    static int getNbIndentation(String line) {
        int indentation = 0;
        while (indentation < line.length() && line.charAt(indentation) == ' ') {
            indentation++;
        }
        return indentation;
    }

    static String extractKey(String line) {
        if (line == null) return "";
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return trimmed;
        int separator = trimmed.indexOf(':');
        return separator < 0 ? trimmed : trimmed.substring(0, separator).trim();
    }

    static void writeToFile(List<String> lines, File file) {
        writeYamlSafely(file, String.join("\n", lines) + "\n", false);
    }
}
