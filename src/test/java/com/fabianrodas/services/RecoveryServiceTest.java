package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @TempDir
    Path root;

    private final RecoveryService recovery = new RecoveryService();

    @Test
    void onlyOldEncryptDrivePartialFilesAreDeleted() throws Exception {
        Path oldBlobPart = file("storage/blobs/3f/3f0b8c55-0f7c-4a39-9d53-5a54e5d0a1b2.edv.part", 25);
        Path freshBlobPart = file("storage/blobs/aa/aa0b8c55-0f7c-4a39-9d53-5a54e5d0a1b2.edv.part", 1);
        Path oldMetadataTemp = file(".encryptdrive/users.enc.8812736451.tmp", 30);
        Path oldManifestTemp = file(".encryptdrive/manifests/m.enc.12345.tmp", 30);
        Path blob = file("storage/blobs/3f/3f0b8c55-0f7c-4a39-9d53-5a54e5d0a1b3.edv", 90);
        Path lock = file(".encryptdrive/lock", 90);
        Path userFileOutsideBlobs = file("notes.part", 90);
        Path userTempFile = file("report.1.tmp", 90);
        Path foreignFileInBlobs = file("storage/blobs/readme.txt", 90);

        int deleted = recovery.cleanStalePartials(root, NOW);

        assertEquals(3, deleted);
        assertFalse(Files.exists(oldBlobPart));
        assertFalse(Files.exists(oldMetadataTemp));
        assertFalse(Files.exists(oldManifestTemp));

        for (Path kept : new Path[]{freshBlobPart, blob, lock, userFileOutsideBlobs, userTempFile, foreignFileInBlobs}) {
            assertTrue(Files.exists(kept), kept.toString());
        }
    }

    @Test
    void missingVaultFoldersAreNotAnError() throws Exception {
        assertEquals(0, recovery.cleanStalePartials(root, NOW));
    }

    private Path file(String relative, int ageHours) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1});
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofHours(ageHours))));
        return file;
    }
}
