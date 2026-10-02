package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.PendingDeletion;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.repositories.BlobRepository;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/*
 * Spec 9: entries leave the manifest and every backup before a blob is
 * deleted. Each test stops the sequence at one boundary.
 */
class PermanentDeleteTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account alice;
    private FileService files;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.register("alice");
        files = vault.files(alice);
        RecoveryService.takeRecoveryNotice();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void entriesLeaveManifestAndBackupsBeforeAnyBlobIsRemoved() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        Path blob = vault.blob(doomed);
        List<Boolean> blobPresentAtCheckpoint = new ArrayList<>();
        ManifestRepository watching = recordingBlobPresence(blob, blobPresentAtCheckpoint);

        assertEquals(0, vault.files(alice, watching).permanentlyDelete(List.of(doomed.getEntryId())));

        assertEquals(List.of(true, false), blobPresentAtCheckpoint);
        assertFalse(Files.exists(blob));
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
        assertBackupsForget(doomed, true);
    }

    @Test
    void failedManifestCommitDeletesNothing() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        byte[] before = Files.readAllBytes(vault.manifestFile(alice));
        ManifestRepository failing = new ManifestRepository(vault.vault) {
            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                throw new VaultStorageException(VaultStorageException.Reason.IO);
            }
        };

        assertReason(FileServiceException.Reason.STORAGE,
                () -> vault.files(alice, failing).permanentlyDelete(List.of(doomed.getEntryId())));

        assertTrue(Files.exists(vault.blob(doomed)));
        assertArrayEquals(before, Files.readAllBytes(vault.manifestFile(alice)));
        assertEquals(List.of(doomed.getEntryId()), ids(files.listTrash()));
    }

    @Test
    void recoveryBeforeAnyBackupIsReseededFindsTheBlobIntact() throws Exception {
        ManifestEntry doomed = vault.importText(files, "doomed.txt", files.rootFolderId());
        files.createFolder("Later", files.rootFolderId());
        files.moveToTrash(doomed.getEntryId());
        // Backup 1 cannot be replaced, so the delete stops right after the manifest commit.
        blockReplacing(vault.manifestBackup(alice, 1));

        assertReason(FileServiceException.Reason.STORAGE,
                () -> files.permanentlyDelete(List.of(doomed.getEntryId())));

        assertTrue(Files.exists(vault.blob(doomed)), "older backups still list it");
        UserManifest committed = vault.manifest(alice);
        assertNull(new ManifestService(committed).find(doomed.getEntryId()));
        assertEquals(List.of(doomed.getBlobId()), blobIds(committed.getPendingDeletions()));

        // Backup 2 predates the delete: recovery brings the entry back, with its content.
        flipCiphertext(vault.manifestFile(alice));
        assertTrue(ids(files.listChildren(files.rootFolderId())).contains(doomed.getEntryId()));
        assertTrue(RecoveryService.takeRecoveryNotice());
        Path out = tempDir.resolve("doomed-out.txt");
        files.exportEntry(doomed.getEntryId(), out);
        assertEquals("doomed.txt", Files.readString(out));
    }

    @Test
    void blobsSurviveUntilEveryBackupIsReseeded() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        Path obstacle = blockReplacing(vault.manifestBackup(alice, 2));

        assertReason(FileServiceException.Reason.STORAGE,
                () -> files.permanentlyDelete(List.of(doomed.getEntryId())));

        assertTrue(Files.exists(vault.blob(doomed)), "a backup may still list it");
        UserManifest current = vault.manifest(alice);
        assertNull(new ManifestService(current).find(doomed.getEntryId()));
        assertEquals(List.of(doomed.getBlobId()), blobIds(current.getPendingDeletions()));

        // The retry finishes reseeding the backups before it removes the blob.
        deleteRecursively(obstacle);
        List<Boolean> blobPresentAtCheckpoint = new ArrayList<>();
        ManifestRepository watching = recordingBlobPresence(vault.blob(doomed), blobPresentAtCheckpoint);
        assertEquals(0, vault.files(alice, watching).resumePendingDeletions());

        assertEquals(List.of(true, false), blobPresentAtCheckpoint);
        assertFalse(Files.exists(vault.blob(doomed)));
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
        assertBackupsForget(doomed, true);
    }

    @Test
    void crashAfterBackupsAreReseededResumesOnTheNextRetry() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        ManifestRepository crashing = new ManifestRepository(vault.vault) {
            private boolean crashed;

            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                super.saveCheckpoint(manifest, manifestId, key);

                if (!crashed) {
                    crashed = true;
                    throw new IllegalStateException("simulated crash after step 5");
                }
            }
        };

        assertThrows(IllegalStateException.class,
                () -> vault.files(alice, crashing).permanentlyDelete(List.of(doomed.getEntryId())));

        assertTrue(Files.exists(vault.blob(doomed)));
        assertBackupsForget(doomed, false);

        assertEquals(0, vault.files(alice).resumePendingDeletions());
        assertFalse(Files.exists(vault.blob(doomed)));
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void crashBetweenTwoBlobRemovalsResumesWithTheRest() throws Exception {
        ManifestEntry first = trashed("first.txt");
        ManifestEntry second = trashed("second.txt");
        BlobRepository crashing = new BlobRepository() {
            private int deletes;

            @Override
            public void delete(Path vaultRoot, UUID blobId) throws IOException {
                if (++deletes == 2) {
                    throw new IllegalStateException("simulated crash between two blob removals");
                }

                super.delete(vaultRoot, blobId);
            }
        };

        assertThrows(IllegalStateException.class,
                () -> vault.files(alice, new ManifestRepository(vault.vault), crashing)
                        .permanentlyDelete(List.of(first.getEntryId(), second.getEntryId())));

        assertEquals(1, vault.blobFiles().size(), "one blob was removed before the crash");
        assertEquals(Set.of(first.getBlobId(), second.getBlobId()),
                Set.copyOf(blobIds(vault.manifest(alice).getPendingDeletions())));
        assertEquals(List.of(), files.listTrash());
        assertBackupsForget(first, false);
        assertBackupsForget(second, false);

        assertEquals(0, files.resumePendingDeletions());
        assertEquals(List.of(), vault.blobFiles());
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
        assertBackupsForget(first, true);
    }

    @Test
    void blobThatCannotBeDeletedStaysQueuedForLater() throws Exception {
        ManifestEntry first = trashed("first.txt");
        ManifestEntry second = trashed("second.txt");
        Path obstacle = blockReplacing(vault.blob(second));

        assertEquals(1, files.permanentlyDelete(List.of(first.getEntryId(), second.getEntryId())));

        assertFalse(Files.exists(vault.blob(first)));
        assertEquals(List.of(second.getBlobId()), blobIds(vault.manifest(alice).getPendingDeletions()));
        assertEquals(List.of(), files.listTrash());
        assertBackupsForget(first, false);
        assertBackupsForget(second, false);

        deleteRecursively(obstacle);
        assertEquals(0, files.resumePendingDeletions());
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void laterPermanentDeleteRetriesWhatIsStillQueued() throws Exception {
        ManifestEntry first = trashed("first.txt");
        ManifestEntry second = trashed("second.txt");
        Path obstacle = blockReplacing(vault.blob(first));
        assertEquals(1, files.permanentlyDelete(List.of(first.getEntryId())));
        deleteRecursively(obstacle);
        Files.writeString(vault.blob(first), "orphaned ciphertext");

        assertEquals(0, files.permanentlyDelete(List.of(second.getEntryId())));

        assertEquals(List.of(), vault.blobFiles());
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void alreadyMissingBlobCountsAsDeleted() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        Files.delete(vault.blob(doomed));

        assertEquals(0, files.permanentlyDelete(List.of(doomed.getEntryId())));

        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void failedCleanupSaveLeavesARetryThatTreatsGoneBlobsAsDone() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        ManifestRepository cleanupFails = new ManifestRepository(vault.vault) {
            private int checkpoints;

            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                if (++checkpoints == 2) {
                    throw new VaultStorageException(VaultStorageException.Reason.IO);
                }

                super.saveCheckpoint(manifest, manifestId, key);
            }
        };

        assertEquals(1, vault.files(alice, cleanupFails).permanentlyDelete(List.of(doomed.getEntryId())));
        assertEquals(1, files.stats().pendingDeletions());

        assertFalse(Files.exists(vault.blob(doomed)));
        assertEquals(List.of(doomed.getBlobId()), blobIds(vault.manifest(alice).getPendingDeletions()));
        assertEquals(0, files.resumePendingDeletions());
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
        assertEquals(0, files.stats().pendingDeletions());
    }

    @Test
    void recoveredBackupNeverBringsBackDeletedEntries() throws Exception {
        ManifestEntry kept = vault.importText(files, "kept.txt", files.rootFolderId());
        ManifestEntry doomed = trashed("doomed.txt");
        files.permanentlyDelete(List.of(doomed.getEntryId()));
        flipCiphertext(vault.manifestFile(alice));

        List<ManifestEntry> root = files.listChildren(files.rootFolderId());

        assertTrue(RecoveryService.takeRecoveryNotice());
        assertEquals(List.of(kept.getEntryId()), ids(root));
        assertEquals(List.of(), files.listTrash());
        Path out = tempDir.resolve("kept-out.txt");
        files.exportEntry(kept.getEntryId(), out);
        assertEquals("kept.txt", Files.readString(out));
    }

    @Test
    void folderSubtreeQueuesEachBlobExactlyOnce() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        ManifestEntry year = files.createFolder("2025", docs.getEntryId());
        Set<UUID> blobs = Set.of(
                vault.importText(files, "a.txt", docs.getEntryId()).getBlobId(),
                vault.importText(files, "b.txt", year.getEntryId()).getBlobId(),
                vault.importText(files, "c.txt", year.getEntryId()).getBlobId()
        );
        files.moveToTrash(docs.getEntryId());
        List<List<UUID>> queued = new ArrayList<>();
        ManifestRepository watching = new ManifestRepository(vault.vault) {
            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                queued.add(blobIds(manifest.getPendingDeletions()));
                super.saveCheckpoint(manifest, manifestId, key);
            }
        };

        vault.files(alice, watching).permanentlyDelete(List.of(docs.getEntryId(), docs.getEntryId()));

        assertEquals(3, queued.get(0).size());
        assertEquals(blobs, Set.copyOf(queued.get(0)));
        assertEquals(List.of(), vault.blobFiles());
    }

    @Test
    void queuedBlobStillInUseIsNeverDeleted() throws Exception {
        ManifestEntry live = vault.importText(files, "live.txt", files.rootFolderId());
        UserManifest manifest = vault.manifest(alice);
        manifest.getPendingDeletions().add(new PendingDeletion(live.getBlobId(), Instant.now().toString()));
        new ManifestRepository(vault.vault).save(manifest, alice.identity().manifestId(), alice.userMasterKey());

        assertEquals(0, files.resumePendingDeletions());

        assertTrue(Files.exists(vault.blob(live)));
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void oneInvalidIdDeletesNothing() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        ManifestEntry active = vault.importText(files, "active.txt", files.rootFolderId());

        assertReason(FileServiceException.Reason.NOT_IN_TRASH,
                () -> files.permanentlyDelete(List.of(doomed.getEntryId(), active.getEntryId())));
        assertReason(FileServiceException.Reason.NOT_FOUND,
                () -> files.permanentlyDelete(List.of(doomed.getEntryId(), UUID.randomUUID())));

        assertTrue(Files.exists(vault.blob(doomed)));
        assertEquals(List.of(doomed.getEntryId()), ids(files.listTrash()));
    }

    // ------------------------------------------------------------ helpers

    /** A repository that notes, at every checkpoint, whether the blob still exists. */
    private ManifestRepository recordingBlobPresence(Path blob, List<Boolean> presentAtCheckpoint) {
        return new ManifestRepository(vault.vault) {
            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                presentAtCheckpoint.add(Files.exists(blob));
                super.saveCheckpoint(manifest, manifestId, key);
            }
        };
    }

    private ManifestEntry trashed(String name) throws Exception {
        ManifestEntry entry = vault.importText(files, name, files.rootFolderId());
        files.moveToTrash(entry.getEntryId());
        return entry;
    }

    /** Every backup generation lacks the entry; with {@code queueEmpty} its journal is empty too. */
    private void assertBackupsForget(ManifestEntry entry, boolean queueEmpty) throws Exception {
        for (int generation = 1; generation <= 3; generation++) {
            UserManifest backup = vault.manifestBackupContent(alice, generation);
            assertNull(new ManifestService(backup).find(entry.getEntryId()), "backup " + generation);

            if (queueEmpty) {
                assertTrue(backup.getPendingDeletions().isEmpty(), "backup " + generation);
            }
        }
    }

    /** Replaces a file with a non-empty directory, so replacing or deleting it fails. */
    private static Path blockReplacing(Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.createDirectories(file.resolve("blocker"));
        return file;
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void flipCiphertext(Path envelopeFile) throws IOException {
        JsonObject payload = JsonParser.parseString(Files.readString(envelopeFile)).getAsJsonObject();
        byte[] ciphertext = Base64.getDecoder().decode(payload.get("ciphertext").getAsString());
        ciphertext[ciphertext.length / 2] ^= 0x01;
        payload.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Files.writeString(envelopeFile, payload.toString());
    }

    private static List<UUID> ids(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getEntryId).toList();
    }

    private static List<UUID> blobIds(List<PendingDeletion> deletions) {
        return deletions.stream().map(PendingDeletion::blobId).toList();
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }
}
