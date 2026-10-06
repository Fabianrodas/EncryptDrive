package com.fabianrodas.repositories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fabianrodas.security.AesGcmService;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupRotatorTest {

    @TempDir
    Path metaDir;

    private final BackupRotator rotator = new BackupRotator();

    @Test
    void backupRotationKeepsExactlyThreeGenerations() throws IOException {
        Path usersFile = metaDir.resolve("users.enc");
        Path backups = metaDir.resolve("backups");

        for (int version = 1; version <= 5; version++) {
            Files.writeString(usersFile, "v" + version, UTF_8);
            rotator.rotate(usersFile, backups, 3);
        }

        assertEquals(List.of("users.enc.1", "users.enc.2", "users.enc.3"), fileNames(backups));
        assertEquals("v5", Files.readString(backups.resolve("users.enc.1"), UTF_8));
        assertEquals("v4", Files.readString(backups.resolve("users.enc.2"), UTF_8));
        assertEquals("v3", Files.readString(backups.resolve("users.enc.3"), UTF_8));
    }

    @Test
    void backupRotationNeverCopiesPlaintext() throws IOException {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String marker = "ExampleUser full name marker";
        Path manifest = metaDir.resolve("manifests").resolve("m-1.enc");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, new Gson().toJson(new AesGcmService().encrypt(
                marker.getBytes(UTF_8), key, "EncryptDrive|manifest|v1|test".getBytes(UTF_8)
        )), UTF_8);

        rotator.rotate(manifest, metaDir.resolve("backups").resolve("manifests"), 3);

        byte[] backup = Files.readAllBytes(
                metaDir.resolve("backups").resolve("manifests").resolve("m-1.enc.1")
        );
        assertArrayEquals(Files.readAllBytes(manifest), backup);
        assertFalse(new String(backup, UTF_8).contains(marker));
    }

    @Test
    void missingFileCreatesNoBackup() throws IOException {
        rotator.rotate(metaDir.resolve("users.enc"), metaDir.resolve("backups"), 3);

        assertFalse(Files.exists(metaDir.resolve("backups").resolve("users.enc.1")));
    }

    @Test
    void reseedReplacesEveryGenerationWithTheGivenContent() throws IOException {
        Path backups = metaDir.resolve("backups");
        Files.createDirectories(backups);
        Files.writeString(backups.resolve("users.enc.1"), "old 1");

        new BackupRotator().reseed("users.enc", "new state".getBytes(UTF_8), backups, 3);

        for (int generation = 1; generation <= 3; generation++) {
            assertEquals("new state", Files.readString(backups.resolve("users.enc." + generation)));
        }
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
