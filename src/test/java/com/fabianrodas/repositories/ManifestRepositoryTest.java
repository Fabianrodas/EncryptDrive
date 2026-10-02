package com.fabianrodas.repositories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.PendingDeletion;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class ManifestRepositoryTest {

    private static final String VAULT_ID = "6f1d2c3b-4a59-4687-9a0b-1c2d3e4f5a6b";
    private static final EncryptedPayload WRAPPED
            = new EncryptedPayload(1, "AES/GCM/NoPadding", "bm9uY2Vub25jZW5v", "d3JhcHBlZA==");
    private static final String NONCE = Base64.getEncoder().encodeToString(new byte[12]);

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

    @Test
    void structurallyInvalidManifestsAreRejected() throws Exception {
        List<Consumer<UserManifest>> damages = List.of(
                manifest -> file(manifest).setContent(-1, UUID.randomUUID(), WRAPPED, NONCE),
                manifest -> file(manifest).setContent(1, null, WRAPPED, NONCE),
                manifest -> file(manifest).setContent(1, UUID.randomUUID(), null, NONCE),
                manifest -> file(manifest).setContent(1, UUID.randomUUID(), WRAPPED,
                        Base64.getEncoder().encodeToString(new byte[11])),
                manifest -> manifest.getEntries().add(file(manifest)),
                manifest -> folder(manifest).setParentId(UUID.randomUUID()),
                manifest -> manifest.getEntries().add(new ManifestEntry(
                        UUID.randomUUID(), ManifestEntryKind.FOLDER, null, "/", "2026-09-30T00:00:00Z")),
                manifest -> folder(manifest).setParentId(file(manifest).getEntryId()),
                manifest -> {
                    ManifestEntry a = new ManifestEntry(UUID.randomUUID(), ManifestEntryKind.FOLDER,
                            null, "a", "2026-09-30T00:00:00Z");
                    ManifestEntry b = new ManifestEntry(UUID.randomUUID(), ManifestEntryKind.FOLDER,
                            a.getEntryId(), "b", "2026-09-30T00:00:00Z");
                    a.setParentId(b.getEntryId());
                    manifest.getEntries().addAll(List.of(a, b));
                }
        );

        for (int i = 0; i < damages.size(); i++) {
            UUID freshId = UUID.randomUUID();   // no backups exist for this id, so nothing can be recovered
            UserManifest manifest = manifestWithNames();
            damages.get(i).accept(manifest);
            repository.save(manifest, freshId, userMasterKey);
            int damage = i;

            VaultStorageException error = assertThrows(
                    VaultStorageException.class,
                    () -> repository.load(userId, freshId, userMasterKey),
                    "damage " + damage
            );
            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason(), "damage " + damage);
        }
    }

    @Test
    void pendingDeletionsRoundTripInsideTheEncryptedManifest() throws Exception {
        UserManifest manifest = ManifestService.newManifest(userId);
        UUID blobId = UUID.randomUUID();
        manifest.getPendingDeletions().add(new PendingDeletion(blobId, "2026-10-01T10:00:00Z"));

        repository.save(manifest, manifestId, userMasterKey);

        assertEquals(
                List.of(new PendingDeletion(blobId, "2026-10-01T10:00:00Z")),
                repository.load(userId, manifestId, userMasterKey).getPendingDeletions()
        );
        assertFalse(Files.readString(manifestFile(), UTF_8).contains(blobId.toString()));
    }

    @Test
    void manifestWithoutAJournalLoadsWithAnEmptyOne() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);

        assertEquals(List.of(), repository.load(userId, manifestId, userMasterKey).getPendingDeletions());
    }

    @Test
    void journalEntryWithoutABlobIdIsCorrupted() throws Exception {
        UserManifest manifest = ManifestService.newManifest(userId);
        manifest.getPendingDeletions().add(new PendingDeletion(null, "2026-10-01T10:00:00Z"));
        repository.save(manifest, manifestId, userMasterKey);

        assertCorrupted(() -> repository.load(userId, manifestId, userMasterKey));
    }

    @Test
    void checkpointLeavesNoOlderManifestInTheBackups() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        repository.save(manifestWithNames(), manifestId, userMasterKey);
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);

        repository.saveCheckpoint(manifestWithNames(), manifestId, userMasterKey);

        byte[] current = Files.readAllBytes(manifestFile());
        for (int generation = 1; generation <= 3; generation++) {
            assertArrayEquals(current, Files.readAllBytes(root.resolve(
                    ".encryptdrive/backups/manifests/" + manifestId + ".enc." + generation)));
        }
    }

    private UserManifest manifestWithNames() throws Exception {
        UserManifest manifest = ManifestService.newManifest(userId);
        ManifestService service = new ManifestService(manifest);
        ManifestEntry folder = service.createFolder(manifest.getRootFolderId(), "Tax Returns 2025");
        ManifestEntry file = new ManifestEntry(
                UUID.randomUUID(), ManifestEntryKind.FILE, folder.getEntryId(),
                "budget.xlsx", "2026-09-30T00:00:00Z"
        );
        file.setContent(12_345, UUID.randomUUID(), WRAPPED, NONCE);
        manifest.getEntries().add(file);
        return manifest;
    }

    private static ManifestEntry file(UserManifest manifest) {
        return manifest.getEntries().stream()
                .filter(entry -> entry.getKind() == ManifestEntryKind.FILE).findFirst().orElseThrow();
    }

    private static ManifestEntry folder(UserManifest manifest) {
        return manifest.getEntries().stream()
                .filter(entry -> entry.getKind() == ManifestEntryKind.FOLDER && entry.getParentId() != null)
                .findFirst().orElseThrow();
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
