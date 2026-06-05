package org.leralix.lib.utils.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.leralix.lib.SphereLib;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
     * @param sectionBlacklist Top-level sections whose on-disk content is preserved verbatim.
     * @param backup           Whether to write a {@code .bak} before rewriting.
     */
    public static void saveAndUpdateResource(Plugin plugin, final String fileName, Collection<String> sectionBlacklist, boolean backup) {
        File currentFile = new File(plugin.getDataFolder(), fileName);
        if (!currentFile.exists()) {
            plugin.saveResource(fileName, false);
            return;
        }

        InputStream baseFile = plugin.getResource(fileName);

        List<String> baseFileLines = loadFileAsList(baseFile);
        List<String> currentFileLines = loadFileAsList(currentFile);

        Optional<List<String>> test = mergeAndPreserveLines(baseFileLines, currentFileLines, sectionBlacklist);

        test.ifPresent(list -> {
            if (backup) {
                backupFile(currentFile);
            }
            writeToFile(list, currentFile);
            plugin.getLogger().info(() -> "The file " + fileName + " has been updated with missing lines.");
        });
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

    /** Copy {@code file} to {@code file + ".bak"}, overwriting any previous backup. No-op if absent. */
    static void backupFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File backup = new File(file.getParentFile(), file.getName() + ".bak");
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            SphereLib.getPlugin().getLogger().warning("[CocoNation Lib] ⚠ Erreur backup: " + file);
        }
    }

    /**
     * Load a file as a list of lines.
     *
     * @param file The input file.
     * @return A list of lines, or null if an error occurs.
     */
    static List<String> loadFileAsList(InputStream file) {
        if (file == null) return Collections.emptyList();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            SphereLib.getPlugin().getLogger().warning("[CocoNation Lib] ⚠ Erreur lecture fichier: " + file);
            return Collections.emptyList();
        }
    }

    static List<String> loadFileAsList(File file) {
        if (file == null) return Collections.emptyList();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            SphereLib.getPlugin().getLogger().warning("[CocoNation Lib] ⚠ Erreur lecture fichier: " + file);
            return Collections.emptyList();
        }
    }

    /**
     * Merge the base file lines into the current file lines, preserving order and comments.
     *
     * @param pluginFileLines The lines from the base file.
     * @param actualFileLine  The lines from the current file.
     * @return A list of lines to write to the current file, or {@link Optional#empty()} if no update is needed.
     */
    static Optional<List<String>> mergeAndPreserveLines(List<String> pluginFileLines, List<String> actualFileLine) {
        return mergeAndPreserveLines(pluginFileLines, actualFileLine, Collections.emptyList());
    }

    /**
     * Merge the base file lines into the current file lines, preserving order and comments.
     *
     * @param pluginFileLines  The lines from the base file.
     * @param actualFileLine   The lines from the current file.
     * @param sectionBlacklist A list of sections to ignore.
     * @return A list of lines to write to the current file, or {@link Optional#empty()} if no update is needed.
     */
    static Optional<List<String>> mergeAndPreserveLines(List<String> pluginFileLines, List<String> actualFileLine, Collection<String> sectionBlacklist) {
        List<String> mergedLines = new ArrayList<>();


        boolean updated = false;
        int indexActual = 0;
        int bannedSectionIndentation = 0;
        boolean inBannedSection = false;

        PluginSideBlacklist pluginSideBlacklist = new PluginSideBlacklist(sectionBlacklist);


        for (String pluginFileLine : pluginFileLines) {


            //Banned section handling. Keep adding actual lines until the end of the banned section is reached.
            if (inBannedSection) {
                while (actualFileLine.size() > indexActual && inBannedSection) {
                    String actualLine = actualFileLine.get(indexActual);

                    if (getNbIndentation(actualLine) <= bannedSectionIndentation && !actualLine.isBlank()) {
                        inBannedSection = false;
                        continue;
                    }
                    mergedLines.add(actualLine);
                    indexActual++;
                }
            }

            if (pluginSideBlacklist.isInBackListPart(pluginFileLine)) {
                continue;
            }

            //If the index is out of bonds, accept all incomming config lines
            if (indexActual >= actualFileLine.size()) {
                mergedLines.add(pluginFileLine);
                updated = true;
                continue;
            }

            String currentLine = actualFileLine.get(indexActual);

            String pluginKey = extractKey(pluginFileLine);
            String currentKey = extractKey(currentLine);

            //If the current line is empty, skip it
            if (currentLine.isBlank() && pluginFileLine.isBlank()) {
                indexActual++;
                mergedLines.add(pluginFileLine);
                continue;
            }

            //Check if the current line is the start of a blacklisted section
            if (containsKey(sectionBlacklist, pluginKey)) {
                inBannedSection = true;
                bannedSectionIndentation = getNbIndentation(pluginFileLine);
                indexActual++;
                mergedLines.add(pluginFileLine);
                if (!currentKey.equals(pluginKey)) {
                    updated = true;
                }
                continue;
            }


            int existingIndex = findLineIndexWithKey(actualFileLine, indexActual - 1, pluginKey);
            if (existingIndex != -1) {
                if (existingIndex != indexActual) {
                    updated = true;
                }
                mergedLines.add(actualFileLine.get(existingIndex));
                indexActual = existingIndex + 1;
                continue;
            }

            if (pluginKey.equals(currentKey)) {
                mergedLines.add(currentLine);
                indexActual++;
            } else {
                mergedLines.add(pluginFileLine);
                updated = true;
            }
        }

        return updated ? Optional.of(mergedLines) : Optional.empty();
    }

    private static int findLineIndexWithKey(List<String> lines, int startIndex, String key) {
        //Due to the use of blank lines, startIndex can be -1.In this case.
        if(startIndex < 0)
            startIndex = 0;

        for (int i = startIndex; i < lines.size(); i++) {
            if (extractKey(lines.get(i)).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    static int getNbIndentation(String pluginFileLine) {
        int i = 0;
        while (i < pluginFileLine.length() && pluginFileLine.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /**
     * Extracts a key from a configuration line.
     *
     * @param line The line to process.
     * @return The key if found, or null otherwise.
     */
    static String extractKey(String line) {
        if (line == null)
            return "";
        line = line.trim();
        if (line.isEmpty()) {
            return "";
        }
        if (line.startsWith("#")) {
            return line;
        }
        if (line.contains(":")) {
            return line.split(":")[0].trim();
        }
        return line;
    }

    /**
     * Write a list of lines to a file.
     *
     * @param lines       The list of lines to write.
     * @param fileToWrite The file to write to.
     */
    static void writeToFile(List<String> lines, File fileToWrite) {

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileToWrite, false))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        } catch (Exception e) {
            SphereLib.getPlugin().getLogger().warning("[CocoNation Lib] ⚠ Erreur écriture fichier: " + fileToWrite);
        }
    }
}
