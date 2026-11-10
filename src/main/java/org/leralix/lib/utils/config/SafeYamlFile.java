package org.leralix.lib.utils.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Arrays;

/** One-file YAML replacement. Callers own scheduling; no Bukkit world or plugin state is touched. */
final class SafeYamlFile {
    static final SafeYamlFile SYSTEM = new SafeYamlFile(new Operations());
    private final Operations operations;

    SafeYamlFile(Operations operations) {
        this.operations = operations;
    }

    static String decode(byte[] bytes, String description) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw problem("read UTF-8 " + description, failure);
        }
    }

    static void validate(String text, String description) {
        try {
            YamlLineMerger.document(text);
            new YamlConfiguration().loadFromString(text);
        } catch (InvalidConfigurationException | RuntimeException failure) {
            throw validationProblem(description, failure);
        }
    }

    private static IllegalStateException validationProblem(String description, Exception failure) {
        String type = failure.getClass().getSimpleName();
        String position = "";
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 16; depth++, cursor = cursor.getCause()) {
            if (cursor instanceof MarkedYAMLException marked) {
                type = cursor.getClass().getSimpleName();
                Mark mark = marked.getProblemMark();
                if (mark == null) mark = marked.getContextMark();
                if (mark != null) {
                    position = ", line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
                }
                break;
            }
        }
        // Parser messages, Mark.toString(), causes and suppressed exceptions can quote YAML secrets.
        return new IllegalStateException("[Config] Cannot validate YAML " + description + " (" + type + position + ")");
    }

    static byte[] readExisting(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Symbolic configuration path refused: " + file);
        }
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    static String read(Path file) {
        try {
            byte[] bytes = readExisting(file);
            if (bytes == null) {
                throw new NoSuchFileException(file.toString());
            }
            String text = decode(bytes, file.toString());
            validate(text, file.toString());
            return text;
        } catch (IOException failure) {
            throw problem("read " + file, failure);
        }
    }

    synchronized void write(File file, String yaml, boolean backup) {
        Path target = file.toPath().toAbsolutePath().normalize();
        try {
            write(target, yaml, backup, readExisting(target));
        } catch (IOException failure) {
            throw problem("replace " + target, failure);
        }
    }

    /** Expected bytes protect the read/merge/write interval from a detected external modification. */
    synchronized void write(Path target, String yaml, boolean backup, byte[] expected) {
        Path candidate = null;
        try {
            ensureUnchanged(target, expected);
            validate(yaml, target + " candidate");
            if (expected != null) {
                validate(decode(expected, target.toString()), target.toString());
            }
            byte[] content = yaml.getBytes(StandardCharsets.UTF_8);
            if (Arrays.equals(expected, content)) {
                ensureUnchanged(target, expected);
                return;
            }
            Files.createDirectories(target.getParent());
            candidate = operations.createCandidate(target, "candidate");
            operations.writeAndForce(candidate, content);
            if (expected != null) preserveAccess(target, candidate);
            byte[] written = Files.readAllBytes(candidate);
            if (!Arrays.equals(content, written)) {
                throw new IOException("Candidate content differs: " + candidate);
            }
            validate(decode(written, candidate.toString()), candidate.toString());
            ensureUnchanged(target, expected);
            if (backup && expected != null) {
                backup(target, expected);
            }
            ensureUnchanged(target, expected);
            operations.replace(candidate, target);
            candidate = null;
        } catch (IOException failure) {
            throw problem("replace " + target, failure);
        } finally {
            discard(candidate);
        }
    }

    synchronized void backup(File file) {
        Path target = file.toPath().toAbsolutePath().normalize();
        try {
            byte[] content = readExisting(target);
            if (content != null) {
                validate(decode(content, target.toString()), target.toString());
                backup(target, content);
            }
        } catch (IOException failure) {
            throw problem("backup " + target, failure);
        }
    }

    private void backup(Path target, byte[] content) throws IOException {
        Path destination = target.resolveSibling(target.getFileName() + ".bak");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup destination is not a regular file: " + destination);
        }
        Path candidate = operations.createCandidate(target, "backup");
        try {
            operations.writeAndForce(candidate, content);
            preserveAccess(target, candidate);
            if (!Arrays.equals(content, Files.readAllBytes(candidate))) {
                throw new IOException("Backup content differs: " + candidate);
            }
            ensureUnchanged(target, content);
            operations.replace(candidate, destination);
        } finally {
            discard(candidate);
        }
    }

    private static void ensureUnchanged(Path target, byte[] expected) throws IOException {
        if (!Arrays.equals(expected, readExisting(target))) {
            throw new IOException("Configuration changed during preparation: " + target);
        }
    }

    private static void preserveAccess(Path original, Path candidate) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(original, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(candidate, posix.readAttributes().permissions());
        }
        AclFileAttributeView acl = Files.getFileAttributeView(original, AclFileAttributeView.class);
        if (acl != null) {
            Files.getFileAttributeView(candidate, AclFileAttributeView.class).setAcl(acl.getAcl());
        }
    }

    private static void discard(Path candidate) {
        if (candidate != null) {
            try {
                Files.deleteIfExists(candidate);
            } catch (IOException ignored) {
                // A leftover sibling is recoverable; never hide the original failure or touch target.
            }
        }
    }

    static IllegalStateException problem(String operation, Exception failure) {
        return new IllegalStateException("[Config] Cannot " + operation, failure);
    }

    /** Bounded disk-stage seam: tests still exercise real files, validation and backup ordering. */
    static class Operations {
        Path createCandidate(Path target, String stage) throws IOException {
            return Files.createTempFile(target.getParent(), "." + target.getFileName() + "." + stage + "-", ".tmp");
        }

        void writeAndForce(Path file, byte[] bytes) throws IOException {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        }

        void replace(Path candidate, Path target) throws IOException {
            // ATOMIC_MOVE unsupported or replacement refused: propagate, never unlink the target.
            Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
