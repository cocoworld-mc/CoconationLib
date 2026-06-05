package org.leralix.lib.utils.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigUtilTest {

    private static String PATH = "utils/config/";

    /**
     * Test of the {@link ConfigUtil#mergeAndPreserveLines(List, List)} method.
     * <p>
     * 2 similar text files should return an empty optional.
     */
    @Test
    void sameTextLinesTest() {

        ClassLoader classLoader = getClass().getClassLoader();
        List<String> textInFolder = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "input-townUpgrade.txt"));

        Optional<List<String>> fileToWrite = ConfigUtil.mergeAndPreserveLines(textInFolder, textInFolder);

        assertTrue(fileToWrite.isEmpty());
    }

    /**
     * Test of the {@link ConfigUtil#mergeAndPreserveLines(List, List)} method.
     * <p>
     * 2 different text files should return the current text with the missing lines from the reference text.
     */
    @Test
    void missingCommentTest() {

        ClassLoader classLoader = getClass().getClassLoader();
        List<String> currentText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "input-townUpgrade.txt"));
        List<String> referenceText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "input-townUpgradeReference.txt"));

        Optional<List<String>> fileToWrite = ConfigUtil.mergeAndPreserveLines(referenceText, currentText);

        assertTrue(fileToWrite.isPresent());
        assertEquals(referenceText.get(0), fileToWrite.get().get(0));
    }


    /**
     * Test of the {@link ConfigUtil#mergeAndPreserveLines(List, List)} method.
     * <p>
     * 2 different text files should return the current text with the missing lines from the reference text.
     * Added lines should be overwritten because there is no blacklisted word.
     */
    @Test
    void modifyingValueTest() {

        ClassLoader classLoader = getClass().getClassLoader();
        List<String> currentText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "input-townUpgrade.txt"));
        List<String> referenceText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "input-townUpgradeReference.txt"));

        Optional<List<String>> fileToWrite = ConfigUtil.mergeAndPreserveLines(referenceText, currentText);

        assertTrue(fileToWrite.isPresent());
        assertNotEquals(referenceText.get(referenceText.size() -1), fileToWrite.get().get(fileToWrite.get().size() - 1));
        assertEquals(referenceText.size(), fileToWrite.get().size());
    }

    /**
     * Test of the {@link ConfigUtil#mergeAndPreserveLines(List, List)} method.
     * <p>
     * 2 different text files should return the current text with the missing lines from the reference text.
     * Added lines should be overwritten because there is no blacklisted word.
     */
    @Test
    void blackListAllowingMoreData() {

        ClassLoader classLoader = getClass().getClassLoader();
        List<String> blackList = List.of("upgrades");
        List<String> currentText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "input-townUpgrade.txt"));
        List<String> referenceText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "input-townUpgradeReference.txt"));

        Optional<List<String>> fileToWrite = ConfigUtil.mergeAndPreserveLines(referenceText, currentText, blackList);

        assertTrue(fileToWrite.isPresent());
        assertNotEquals(referenceText.get(referenceText.size() -1), fileToWrite.get().get(fileToWrite.get().size() - 1));
        assertEquals(referenceText.size() + 1, fileToWrite.get().size());
    }

    /**
     * Test of the {@link ConfigUtil#mergeAndPreserveLines(List, List)} method.
     * <p>
     * 2 different text files should return the current text with the missing lines from the reference text.
     * Added lines should be overwritten because there is no blacklisted word.
     */
    @Test
    void blackListWithMissingLineAtTheEnd() {

        ClassLoader classLoader = getClass().getClassLoader();
        List<String> blackList = List.of("upgrades");
        List<String> currentText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "input-townUpgrade.txt"));
        List<String> referenceText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "input-townUpgradeReferenceWithOneMissingLine.txt"));

        Optional<List<String>> fileToWrite = ConfigUtil.mergeAndPreserveLines(referenceText, currentText, blackList);

        assertTrue(fileToWrite.isPresent());
        assertEquals(referenceText.get(referenceText.size() -1), fileToWrite.get().get(fileToWrite.get().size() - 1));
        assertEquals(referenceText.size(), fileToWrite.get().size());
    }


    /**
     * Test on the configuration file of ExoticTrades.
     */
    @Test
    void exoticTradesConfigTest() {

        ClassLoader classLoader = getClass().getClassLoader();
        List<String> currentText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "exotictrades/firstInput.yml"));
        List<String> referenceText = ConfigUtil.loadFileAsList(classLoader.getResourceAsStream(PATH + "exotictrades/modifiedInput.yml"));

        List<String> blackList = new ArrayList<>();
        blackList.add("rareRessources");
        blackList.add("stockMarket");
        blackList.add("marketItem");
        Optional<List<String>> fileToWrite = ConfigUtil.mergeAndPreserveLines(referenceText, currentText, blackList);

        assertTrue(fileToWrite.isPresent());
        assertTrue(fileToWrite.get().contains("    enabled: true"));
    }

    /**
     * {@link ConfigUtil#detectCollisions(List)} — disjoint key sets must report no collision
     * (the invariant for the C8 split: one top-level key lives in exactly one module file).
     */
    @Test
    void detectCollisionsNoneWhenDisjoint() {
        Set<String> collisions = ConfigUtil.detectCollisions(List.of(
                List.of("database", "redis", "cache"),
                List.of("townCost", "townCreation"),
                List.of("fortCost", "EnableWar")));
        assertTrue(collisions.isEmpty());
    }

    /**
     * {@link ConfigUtil#detectCollisions(List)} — a key present in two sources must be reported.
     */
    @Test
    void detectCollisionsFindsDuplicate() {
        Set<String> collisions = ConfigUtil.detectCollisions(List.of(
                List.of("database", "townCost"),
                List.of("townCost", "EnableWar"),
                List.of("nexo")));
        assertEquals(Set.of("townCost"), collisions);
    }

    /**
     * {@link ConfigUtil#backupFile(File)} — must copy the current content to a {@code .bak} sibling.
     */
    @Test
    void backupFileCopiesCurrentContent(@TempDir Path tmp) throws Exception {
        File file = tmp.resolve("towns.yml").toFile();
        Files.writeString(file.toPath(), "townCost: 1000\n", StandardCharsets.UTF_8);

        ConfigUtil.backupFile(file);

        File backup = tmp.resolve("towns.yml.bak").toFile();
        assertTrue(backup.exists());
        assertEquals("townCost: 1000\n", Files.readString(backup.toPath(), StandardCharsets.UTF_8));
    }

    /** {@link ConfigUtil#backupFile(File)} — absent file is a silent no-op (no exception). */
    @Test
    void backupFileNoOpWhenAbsent(@TempDir Path tmp) {
        File absent = tmp.resolve("does-not-exist.yml").toFile();
        assertDoesNotThrow(() -> ConfigUtil.backupFile(absent));
        assertFalse(tmp.resolve("does-not-exist.yml.bak").toFile().exists());
    }

    /**
     * {@link ConfigUtil#mergeTopLevel(List, Set)} — merges disjoint top-level keys; on a duplicated
     * key the first source wins and the duplicate is reported (C8 aggregation contract).
     */
    @Test
    void mergeTopLevelMergesAndReportsCollision() throws Exception {
        YamlConfiguration infra = new YamlConfiguration();
        infra.loadFromString("database:\n  type: sqlite\ntownCost: 1000\n");
        YamlConfiguration wars = new YamlConfiguration();
        wars.loadFromString("EnableWar: true\ntownCost: 2000\n"); // townCost collides with infra

        Set<String> collisions = new LinkedHashSet<>();
        FileConfiguration merged = ConfigUtil.mergeTopLevel(List.of(infra, wars), collisions);

        assertEquals("sqlite", merged.getString("database.type"));
        assertTrue(merged.getBoolean("EnableWar"));
        assertEquals(1000, merged.getInt("townCost")); // first occurrence kept
        assertEquals(Set.of("townCost"), collisions);
    }
}
