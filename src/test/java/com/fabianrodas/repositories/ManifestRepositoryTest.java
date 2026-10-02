package com.fabianrodas.repositories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.security.SensitiveBytes;
import com.fabianrodas.services.ManifestService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class ManifestRepositoryTest {

    private static final String VAULT_ID = "6f1d2c3b-4a59-4687-9a0b-1c2d3e4f5a6b";

    @TempDir
    Path root;

    private final UUID userId = UUID.randomUUID();
    private final UUID manifestId = UUID.randomUUID();
    private final byte[] userMasterKey = randomKey();
    private VaultContext vault;
    private ManifestRepository repository;

    @BeforeEach
    void openVault() throws Exception {
        Files.createDirectories(root.resolve(".encryptdrive").resolve("manifests"));
        vault = new VaultContext(
                root, VAULT_ID, 1, "2026-09-30T17:00:00Z", SensitiveBytes.copyOf(randomKey()), () -> { }
        );
        repository = new ManifestRepository(vault);
    }

    @AfterEach
    void closeVault() {
        vault.close();
    }

    @Test
    void savedManifestRevealsNoNamesOrUserIdAtRest() throws Exception {
        repository.save(manifestWithNames(), manifestId, userMasterKey);

        String raw = Files.readString(manifestFile(), UTF_8);

        for (String secret : List.of("Tax Returns 2025", "budget.xlsx", userId.toString())) {
            assertFalse(raw.contains(secret), secret);
        }
    }

    @Test
    void saveThenLoadRoundTrips() throws Exception {
        UserManifest saved = manifestWithNames();
        repository.save(saved, manifestId, userMasterKey);

        UserManifest loaded = repository.load(userId, manifestId, userMasterKey);

        assertEquals(userId, loaded.getUserId());
        assertEquals(saved.getRootFolderId(), loaded.getRootFolderId());
        assertEquals(
                List.of("/", "Tax Returns 2025", "budget.xlsx"),
                loaded.getEntries().stream().map(ManifestEntry::getName).toList()
        );
    }

    @Test
    void wrongUserMasterKeyFailsAuthentication() throws Exception {
        repository.save(manifestWithNames(), manifestId, userMasterKey);

        assertCorrupted(() -> repository.load(userId, manifestId, randomKey()));
    }

    @Test
    void manifestIsBoundToItsUser() throws Exception {
        repository.save(manifestWithNames(), manifestId, userMasterKey);

        assertCorrupted(() -> repository.load(UUID.randomUUID(), manifestId, userMasterKey));
    }

    @Test
    void oneByteCorruptionFailsAuthentication() throws Exception {
        repository.save(manifestWithNames(), manifestId, userMasterKey);
        JsonObject payload = JsonParser.parseString(
                Files.readString(manifestFile(), UTF_8)
        ).getAsJsonObject();
        byte[] ciphertext = Base64.getDecoder().decode(payload.get("ciphertext").getAsString());
        ciphertext[ciphertext.length - 1] ^= 0x01;
        payload.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Files.writeString(manifestFile(), payload.toString(), UTF_8);

        assertCorrupted(() -> repository.load(userId, manifestId, userMasterKey));
    }

    @Test
    void saveKeepsEncryptedBackupsOfThePreviousManifest() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        byte[] previous = Files.readAllBytes(manifestFile());

        repository.save(manifestWithNames(), manifestId, userMasterKey);

        assertArrayEquals(previous, Files.readAllBytes(root.resolve(
                ".encryptdrive/backups/manifests/" + manifestId + ".enc.1"
        )));
    }

    @Test
    void oversizedManifestFallsBackToAnAuthenticBackup() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        repository.save(manifestWithNames(), manifestId, userMasterKey);
        Files.delete(manifestFile());
        BoundedFilesTest.sparseFile(manifestFile(), 3L << 30);

        assertEquals(1, repository.load(userId, manifestId, userMasterKey).getEntries().size());
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void oversizedManifestBackupIsSkipped() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        Path backup1 = root.resolve(".encryptdrive/backups/manifests/" + manifestId + ".enc.1");
        Files.delete(backup1);
        BoundedFilesTest.sparseFile(backup1, 3L << 30);
        Files.writeString(manifestFile(), "{}", UTF_8);

        assertEquals(1, repository.load(userId, manifestId, userMasterKey).getEntries().size());
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void saveRefusesManifestOverTheLimit() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        byte[] before = Files.readAllBytes(manifestFile());
        UserManifest huge = ManifestService.newManifest(userId);
        huge.getEntries().get(0).setName("x".repeat(65 * 1024 * 1024));

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.save(huge, manifestId, userMasterKey)
        );

        assertEquals(VaultStorageException.Reason.TOO_LARGE, error.getReason());
        assertArrayEquals(before, Files.readAllBytes(manifestFile()));
    }

    private UserManifest manifestWithNames() throws Exception {
        UserManifest manifest = ManifestService.newManifest(userId);
        ManifestService service = new ManifestService(manifest);
        ManifestEntry folder = service.createFolder(manifest.getRootFolderId(), "Tax Returns 2025");
        manifest.getEntries().add(new ManifestEntry(
                UUID.randomUUID(), ManifestEntryKind.FILE, folder.getEntryId(),
                "budget.xlsx", "2026-09-30T00:00:00Z"
        ));
        return manifest;
    }

    private Path manifestFile() {
        return root.resolve(".encryptdrive").resolve("manifests").resolve(manifestId + ".enc");
    }

    private static void assertCorrupted(Executable action) {
        VaultStorageException error = assertThrows(VaultStorageException.class, action);
        assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
