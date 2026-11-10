package org.leralix.lib.utils.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YamlLineMergerTest {
    private String merge(String reference, String actual, String... blacklist) {
        return String.join("\n", ConfigUtil.mergeAndPreserveLines(reference.lines().toList(),
                actual.lines().toList(), List.of(blacklist)).orElse(actual.lines().toList()));
    }

    @Test void repeatedSiblingNamesCannotCauseCrossSectionOverwrite() throws Exception {
        String reference = "a:\n  price: 1\n  new: true\nb:\n  price: 2\n  new: false\n";
        String actual = "a:\n  price: 900 # premier\n  admin: 3\nb:\n  price: 600 # second\n  unknown:\n    list: [a, b]\n";
        String result = merge(reference, actual);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(result);
        assertEquals(900, yaml.getInt("a.price"));
        assertEquals(600, yaml.getInt("b.price"));
        assertTrue(yaml.getBoolean("a.new"));
        assertFalse(yaml.getBoolean("b.new"));
        assertEquals(List.of("a", "b"), yaml.getStringList("b.unknown.list"));
        assertTrue(result.contains("# premier"));
        assertTrue(result.contains("# second"));
        assertEquals(result, merge(reference, result));
    }

    @Test void exactBlacklistPathsProtectOnlyTheirOwnSubtree() throws Exception {
        String reference = "Forts:\n  fortCostByTier:\n    1: 750\n  neutral: true\nFortsExtra:\n  new: true\n";
        String actual = "Forts:\n  fortCostByTier:\n    7: 123\nFortsExtra:\n  existing: true\n";
        String result = merge(reference, actual, "Forts.fortCostByTier");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(result);
        assertEquals(123, yaml.getInt("Forts.fortCostByTier.7"));
        assertFalse(yaml.contains("Forts.fortCostByTier.1"));
        assertTrue(yaml.getBoolean("Forts.neutral"));
        assertTrue(yaml.getBoolean("FortsExtra.new"));
        assertEquals(result, merge(reference, result, "Forts.fortCostByTier"));
    }

    @Test void adjacentProtectedMappingsRemainVerbatimIncludingUnknownTail() {
        String reference = "kits:\n  default: true\narenas:\n  colisee: true\nenabled: true\n";
        String actual = "kits:\n  custom: [a,b]\narenas:\n  mine: {spawn: 3}\nunknown: custom\n";
        String result = merge(reference, actual, "kits", "arenas");
        assertEquals(actual.stripTrailing() + "\nenabled: true", result);
        assertEquals(result, merge(reference, result, "kits", "arenas"));
    }

    @Test void explicitEmptyAndFlowMapsAndListsAreAdminValues() {
        String actual = "empty: {}\nflow: {a: 42}\nitems: []\ntext: |\n  keep: this\n  and this\n";
        String reference = "empty:\n  new: 1\nflow:\n  other: 2\nitems: [default]\ntext: default\n";
        assertEquals(actual.stripTrailing(), merge(reference, actual));
    }

    @Test void quotedKeysAndCustomIndentationPreserveOriginalSpelling() throws Exception {
        String actual = "'section':\n    'value:quoted': 27 # café\n    custom: true\n";
        String reference = "section:\n  'value:quoted': 1\n  missing: true\n";
        String result = merge(reference, actual);
        assertTrue(result.contains("    'value:quoted': 27 # café"));
        assertTrue(result.contains("    missing: true"));
        assertEquals(result, merge(reference, result));
    }

    @Test void recursiveAliasesAndDuplicateKeysAreRejectedBeforeMerge() {
        for (String bad : List.of("x: 1\nx: 2\n", "x: &loop\n  nested: *loop\n")) {
            assertThrows(IllegalArgumentException.class, () -> merge("x: 1\n", bad));
        }
    }

    @Test void inheritedBlockMappingKeepsEffectiveAdminValueInsteadOfTemplatePrice() throws Exception {
        String actual = "defaults: &defaults\n  price: 42\nx:\n  <<: *defaults\n  custom: keep\n";
        String reference = "x:\n  price: 10\n  neutral: false\nenabled: true\n";
        String result = merge(reference, actual);
        assertTrue(result.contains("  <<: *defaults"));
        assertFalse(result.contains("  price: 10"));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(result);
        assertEquals(42, yaml.getInt("x.price"));
        assertEquals("keep", yaml.getString("x.custom"));
        assertTrue(yaml.getBoolean("enabled"));
        assertEquals(result, merge(reference, result));
    }
}
