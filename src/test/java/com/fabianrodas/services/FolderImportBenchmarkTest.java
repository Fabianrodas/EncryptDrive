package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.repositories.ManifestRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/*
 * Opt-in measurement (spec 14.2/14.3), never a CI threshold:
 *   mvn -Dtest=FolderImportBenchmarkTest -Dencryptdrive.perfCheck=true test
 * Optional inputs (the defaults are the measured case):
 *   -Dencryptdrive.perfFiles=1000     number of 4 KiB files in the imported folder
 *   -Dencryptdrive.perfExisting=0     entries already in the manifest before the timed import
 */
@EnabledIfSystemProperty(named = "encryptdrive.perfCheck", matches = "true")
class FolderImportBenchmarkTest {

    private static final int REPORT_EVERY = 500;

    @Test
    void smallFiles(@TempDir Path dir) throws Exception {
        int fileCount = Integer.getInteger("encryptdrive.perfFiles", 1000);
        int existing = Integer.getInteger("encryptdrive.perfExisting", 0);
        Path source = Files.createDirectories(dir.resolve("in").resolve("Bench"));

        for (int i = 0; i < fileCount; i++) {
            Files.write(source.resolve("file-" + i + ".bin"), new byte[4096]);
        }

        try (TestVault vault = new TestVault(dir)) {
            TestVault.Account alice = vault.register("alice");
            FileService files = vault.files(alice);
            addExistingEntries(vault, alice, existing);
            int[] reported = {0};
            long start = System.nanoTime();

            FileService.FolderImport result = files.importFolder(source, files.rootFolderId(), (done, total, bytes, totalBytes) -> {
                if (done > reported[0] && done % REPORT_EVERY == 0) {
                    reported[0] = done;
                    System.out.println("Folder import benchmark: " + done + "/" + total + " files after "
                            + (System.nanoTime() - start) / 1_000_000 + " ms");
                }
            });

            System.out.println("Folder import benchmark: " + fileCount + " x 4 KiB in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms, with " + existing
                    + " entries already in the manifest");
            assertEquals(fileCount, result.filesImported());
        }
    }

    /**
     * Writes {@code count} entries straight into the manifest in one save: a folder for every hundred
     * entries, files in the newest folder. Their blob ids point at nothing, which is enough to cost
     * what loading and saving a manifest of that size costs.
     */
    private static void addExistingEntries(TestVault vault, TestVault.Account alice, int count) throws Exception {
        if (count <= 0) {
            return;
        }

        UserManifest manifest = vault.manifest(alice);
        String now = Instant.now().toString();
        String nonce = Base64.getEncoder().encodeToString(new byte[12]);
        UUID folder = manifest.getRootFolderId();

        for (int i = 0; i < count; i++) {
            boolean newFolder = i % 100 == 0;
            ManifestEntry entry = new ManifestEntry(
                    UUID.randomUUID(),
                    newFolder ? ManifestEntryKind.FOLDER : ManifestEntryKind.FILE,
                    newFolder ? manifest.getRootFolderId() : folder,
                    (newFolder ? "existing folder " : "existing file ") + i,
                    now
            );

            if (newFolder) {
                folder = entry.getEntryId();
            } else {
                entry.setContent(1, UUID.randomUUID(), new EncryptedPayload(1, "AES-256-GCM", nonce, "AA=="), nonce);
            }

            manifest.getEntries().add(entry);
        }

        new ManifestRepository(vault.vault).save(manifest, alice.identity().manifestId(), alice.userMasterKey());
    }
}
