package org.leralix.lib.utils.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SafeYamlFileTest {
    @TempDir Path directory;
    private static final String ORIGINAL = "# admin café\nprice: 123.45\ncustom: [a, b]\n";
    private static final String UPDATED = ORIGINAL + "enabled: true\n";

    private Path original() throws IOException {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, ORIGINAL);
        return file;
    }

    private Path backup(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private void noCandidates() throws IOException {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test void replacementAndBackupContainExactUtf8Bytes() throws IOException {
        Path file = original();
        ConfigUtil.writeYamlSafely(file.toFile(), UPDATED, true);
        assertEquals(UPDATED, Files.readString(file));
        assertEquals(UPDATED, ConfigUtil.readYamlSafely(file.toFile()));
        assertArrayEquals(ORIGINAL.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(backup(file)));
        noCandidates();
    }

    @Test void existingPosixAccessPolicySurvivesReplacementWhenSupported() throws IOException {
        Path file = original();
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            ConfigUtil.writeYamlSafely(file.toFile(), UPDATED, true);
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals(Files.getPosixFilePermissions(file), Files.getPosixFilePermissions(backup(file)));
        }
    }

    @Test void identicalContentDoesNotTouchTargetOrPreviousBackup() throws IOException {
        Path file = original();
        Files.writeString(backup(file), "previous: backup\n");
        FileTime timestamp = FileTime.fromMillis(1234567890000L);
        Files.setLastModifiedTime(file, timestamp);
        ConfigUtil.writeYamlSafely(file.toFile(), ORIGINAL, true);
        assertEquals(timestamp, Files.getLastModifiedTime(file));
        assertEquals("previous: backup\n", Files.readString(backup(file)));
        noCandidates();
    }

    @Test void firstExtractionCreatesParentsWithoutInventingBackup() throws IOException {
        Path file = directory.resolve("modules/new/config.yml");
        ConfigUtil.writeYamlSafely(file.toFile(), UPDATED, true);
        assertEquals(UPDATED, Files.readString(file));
        assertFalse(Files.exists(backup(file)));
    }

    @Test void invalidCandidatePreservesOriginalAndPreviousBackup() throws IOException {
        Path file = original();
        Files.writeString(backup(file), "previous: backup\n");
        for (String bad : List.of("key: [", "x: 1\nx: 2\n", "- sequence\n", "x: &loop [*loop]\n")) {
            assertThrows(IllegalStateException.class, () -> ConfigUtil.writeYamlSafely(file.toFile(), bad, true));
            assertEquals(ORIGINAL, Files.readString(file));
            assertEquals("previous: backup\n", Files.readString(backup(file)));
        }
        noCandidates();
    }

    @Test void validationStackTraceKeepsLocationWithoutQuotingYamlSecrets() throws IOException {
        Path file = original();
        Files.writeString(backup(file), "previous: backup\n");
        String sentinel = "PREP01B_FICTITIOUS_PASSWORD_SENTINEL";
        for (String invalid : List.of("password: [\"" + sentinel + "\"\n",
                "password: !unsupported \"" + sentinel + "\"\n")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> ConfigUtil.writeYamlSafely(file.toFile(), invalid, true));
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            assertFalse(trace.toString().contains(sentinel));
            assertTrue(failure.getMessage().contains(file.toString()));
            assertTrue(failure.getMessage().contains("Exception"));
            assertTrue(failure.getMessage().contains("line "));
            assertTrue(failure.getMessage().contains("column "));
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(ORIGINAL, Files.readString(file));
            assertEquals("previous: backup\n", Files.readString(backup(file)));
        }
        noCandidates();
    }

    @Test void invalidOrUnreadableOriginalCannotBeReplaced() throws IOException {
        Path file = original();
        for (byte[] bad : List.of("x: [".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xc3, 0x28})) {
            Files.write(file, bad);
            assertThrows(IllegalStateException.class, () -> ConfigUtil.readYamlSafely(file.toFile()));
            assertThrows(IllegalStateException.class, () -> ConfigUtil.writeYamlSafely(file.toFile(), UPDATED, false));
            assertArrayEquals(bad, Files.readAllBytes(file));
        }
        assertThrows(IllegalStateException.class,
                () -> ConfigUtil.writeYamlSafely(directory.toFile(), UPDATED, true));
        assertFalse(Files.exists(backup(file)));
        noCandidates();
    }

    @Test void backupDestinationFailurePreservesTargetAndDestination() throws IOException {
        Path file = original();
        Files.createDirectory(backup(file));
        Files.writeString(backup(file).resolve("keep"), "previous backup evidence");
        assertThrows(IllegalStateException.class, () -> ConfigUtil.writeYamlSafely(file.toFile(), UPDATED, true));
        assertEquals(ORIGINAL, Files.readString(file));
        assertEquals("previous backup evidence", Files.readString(backup(file).resolve("keep")));
        noCandidates();
    }

    @Test void candidateCreationFailureDoesNotTouchFiles() throws IOException {
        Path file = original();
        SafeYamlFile writer = new SafeYamlFile(new SafeYamlFile.Operations() {
            @Override Path createCandidate(Path target, String stage) throws IOException {
                throw new IOException("candidate creation denied");
            }
        });
        assertThrows(IllegalStateException.class, () -> writer.write(file.toFile(), UPDATED, true));
        assertEquals(ORIGINAL, Files.readString(file));
        assertFalse(Files.exists(backup(file)));
        noCandidates();
    }

    @Test void partialCandidateWriteFailureDoesNotTruncateTarget() throws IOException {
        Path file = original();
        SafeYamlFile writer = new SafeYamlFile(new SafeYamlFile.Operations() {
            @Override void writeAndForce(Path candidate, byte[] bytes) throws IOException {
                Files.writeString(candidate, "partial: [");
                throw new IOException("disk full");
            }
        });
        assertThrows(IllegalStateException.class, () -> writer.write(file.toFile(), UPDATED, true));
        assertEquals(ORIGINAL, Files.readString(file));
        assertFalse(Files.exists(backup(file)));
        noCandidates();
    }

    @Test void candidateReadbackRejectsChangedContent() throws IOException {
        Path file = original();
        SafeYamlFile writer = new SafeYamlFile(new SafeYamlFile.Operations() {
            @Override void writeAndForce(Path candidate, byte[] bytes) throws IOException {
                super.writeAndForce(candidate, "corrupt: [".getBytes(StandardCharsets.UTF_8));
            }
        });
        assertThrows(IllegalStateException.class, () -> writer.write(file.toFile(), UPDATED, true));
        assertEquals(ORIGINAL, Files.readString(file));
        noCandidates();
    }

    @Test void backupWriteFailurePreservesExistingBackup() throws IOException {
        Path file = original();
        Files.writeString(backup(file), "previous: backup\n");
        SafeYamlFile writer = new SafeYamlFile(new SafeYamlFile.Operations() {
            @Override void writeAndForce(Path candidate, byte[] bytes) throws IOException {
                if (candidate.getFileName().toString().contains(".backup-")) {
                    Files.writeString(candidate, "partial backup");
                    throw new IOException("backup disk full");
                }
                super.writeAndForce(candidate, bytes);
            }
        });
        assertThrows(IllegalStateException.class, () -> writer.write(file.toFile(), UPDATED, true));
        assertEquals(ORIGINAL, Files.readString(file));
        assertEquals("previous: backup\n", Files.readString(backup(file)));
        noCandidates();
    }

    @Test void backupRenameFailurePreservesExistingBackup() throws IOException {
        Path file = original();
        Files.writeString(backup(file), "previous: backup\n");
        SafeYamlFile writer = new SafeYamlFile(new SafeYamlFile.Operations() {
            @Override void replace(Path candidate, Path target) throws IOException {
                if (target.equals(backup(file))) throw new IOException("backup rename denied");
                super.replace(candidate, target);
            }
        });
        assertThrows(IllegalStateException.class, () -> writer.write(file.toFile(), UPDATED, true));
        assertEquals(ORIGINAL, Files.readString(file));
        assertEquals("previous: backup\n", Files.readString(backup(file)));
        noCandidates();
    }

    @Test void targetRenameFailureKeepsOriginalBackupAndCanRetry() throws IOException {
        Path file = original();
        SafeYamlFile writer = new SafeYamlFile(new SafeYamlFile.Operations() {
            @Override void replace(Path candidate, Path target) throws IOException {
                if (target.equals(file)) throw new IOException("target rename denied");
                super.replace(candidate, target);
            }
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> writer.write(file.toFile(), UPDATED, true));
        assertTrue(failure.getMessage().contains(file.toString()));
        assertEquals(ORIGINAL, Files.readString(file));
        assertEquals(ORIGINAL, Files.readString(backup(file)));
        ConfigUtil.writeYamlSafely(file.toFile(), UPDATED, true);
        assertEquals(UPDATED, Files.readString(file));
        assertEquals(ORIGINAL, Files.readString(backup(file)));
        noCandidates();
    }

    @Test void unsupportedAtomicMoveIsExplicitAndNeverFallsBackToUnlink() throws IOException {
        Path file = original();
        SafeYamlFile writer = new SafeYamlFile(new SafeYamlFile.Operations() {
            @Override void replace(Path candidate, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(candidate.toString(), target.toString(), "test filesystem");
            }
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> writer.write(file.toFile(), UPDATED, false));
        assertInstanceOf(AtomicMoveNotSupportedException.class, failure.getCause());
        assertEquals(ORIGINAL, Files.readString(file));
        noCandidates();
    }

    @Test void originalPassedByMigrationCannotBeStale() throws IOException {
        Path file = original();
        Files.writeString(file, "price: 999\n");
        assertThrows(IllegalStateException.class,
                () -> ConfigUtil.writeYamlSafely(file.toFile(), UPDATED, true, ORIGINAL));
        assertEquals("price: 999\n", Files.readString(file));
        assertFalse(Files.exists(backup(file)));
        noCandidates();
    }

    @Test void modificationDuringCandidateWriteIsDetectedBeforeBackupAndReplacement() throws IOException {
        Path file = original();
        SafeYamlFile writer = new SafeYamlFile(new SafeYamlFile.Operations() {
            @Override void writeAndForce(Path candidate, byte[] bytes) throws IOException {
                super.writeAndForce(candidate, bytes);
                Files.writeString(file, "price: 999\n");
            }
        });
        assertThrows(IllegalStateException.class, () -> writer.write(file.toFile(), UPDATED, true));
        assertEquals("price: 999\n", Files.readString(file));
        assertFalse(Files.exists(backup(file)));
        noCandidates();
    }
}
