package com.fabianrodas.repositories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.KdfConfig;
import com.fabianrodas.models.UserRecord;
import com.fabianrodas.models.UserRegistry;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.SensitiveBytes;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserRegistryRepositoryTest {

    private static final String VAULT_ID = "0d9f3c1e-8a4b-4c5d-9e6f-7a8b9c0d1e2f";
    private static final String USER_ID = "5b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9";

    @TempDir
    Path root;

    private final UserRegistryRepository repository = new UserRegistryRepository();
    private final byte[] registryKey = new byte[32];
    private VaultContext vault;

    @BeforeEach
    void openVault() throws Exception {
        new SecureRandom().nextBytes(registryKey);
        Files.createDirectories(root.resolve(".encryptdrive"));
        vault = context(registryKey);
    }

    @AfterEach
    void closeVault() {
        vault.close();
    }

    @Test
    void savedRegistryRevealsNoUserIdentityAtRest() throws Exception {
        repository.save(vault, registryWith(record()));

        String raw = Files.readString(usersFile(), UTF_8);

        for (String secret : List.of("Example Person", "ExampleUser", "exampleuser", USER_ID)) {
            assertFalse(raw.contains(secret), secret);
        }

        UserRecord loaded = repository.load(vault).getUsers().get(0);
        assertEquals(USER_ID, loaded.getUserId());
        assertEquals("Example Person", loaded.getFullName());
    }

    @Test
    void decryptedRegistryUsesTheSpecifiedFieldsAndNoPasswordHash() throws Exception {
        repository.save(vault, registryWith(record()));

        EncryptedPayload payload = new Gson().fromJson(
                Files.readString(usersFile(), UTF_8),
                EncryptedPayload.class
        );
        JsonObject registry = JsonParser.parseString(new String(
                new AesGcmService().decrypt(payload, registryKey, Aad.users(VAULT_ID)),
                UTF_8
        )).getAsJsonObject();

        assertEquals(1, registry.get("formatVersion").getAsInt());
        assertEquals(
                Set.of("userId", "fullName", "username", "normalizedUsername", "createdAt",
                        "manifestId", "userKdf", "wrappedUserMasterKey"),
                registry.getAsJsonArray("users").get(0).getAsJsonObject().keySet()
        );
    }

    @Test
    void saveRotatesEncryptedBackupsBeforeReplacing() throws Exception {
        repository.save(vault, new UserRegistry(1, new ArrayList<>()));
        byte[] previous = Files.readAllBytes(usersFile());

        repository.save(vault, registryWith(record()));

        assertArrayEquals(
                previous,
                Files.readAllBytes(root.resolve(".encryptdrive/backups/users.enc.1"))
        );
    }

    @Test
    void checkpointLeavesNoOlderRegistryInTheBackups() throws Exception {
        repository.save(vault, new UserRegistry(1, new ArrayList<>()));
        repository.save(vault, registryWith(record()));
        repository.save(vault, new UserRegistry(1, new ArrayList<>()));

        repository.saveCheckpoint(vault, registryWith(record()));

        byte[] current = Files.readAllBytes(usersFile());
        for (int generation = 1; generation <= 3; generation++) {
            assertArrayEquals(current, Files.readAllBytes(backup(generation)));
        }
    }

    /** Pins the order the checkpoint's safety rests on: backup 1, backup 2, backup 3, then users.enc. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void aFailedCheckpointHasReseededOnlyTheBackupsWrittenBeforeTheFailure(int failingWrite) throws Exception {
        FailingWriter writer = new FailingWriter();
        UserRegistryRepository failing = new UserRegistryRepository(writer);
        failing.save(vault, new UserRegistry(1, new ArrayList<>()));
        failing.save(vault, registryWith(record()));
        failing.save(vault, new UserRegistry(1, new ArrayList<>()));
        failing.save(vault, registryWith(record()));
        byte[] live = Files.readAllBytes(usersFile());
        List<byte[]> before = List.of(
                Files.readAllBytes(backup(1)), Files.readAllBytes(backup(2)), Files.readAllBytes(backup(3))
        );
        writer.failBeforeWrite(failingWrite);

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> failing.saveCheckpoint(vault, registryWith(record()))
        );

        assertEquals(VaultStorageException.Reason.IO, error.getReason());
        assertArrayEquals(live, Files.readAllBytes(usersFile()));
        byte[] reseeded = Files.readAllBytes(backup(1));

        for (int generation = 1; generation <= 3; generation++) {
            byte[] after = Files.readAllBytes(backup(generation));

            if (generation < failingWrite) {
                assertArrayEquals(reseeded, after, "backup " + generation + " was reseeded");
                assertFalse(Arrays.equals(before.get(generation - 1), after), "backup " + generation);
                assertFalse(Arrays.equals(live, after), "backup " + generation);
            } else {
                assertArrayEquals(before.get(generation - 1), after, "backup " + generation + " is untouched");
            }
        }
    }

    @Test
    void aCheckpointPerformsExactlyFourWrites() throws Exception {
        FailingWriter writer = new FailingWriter();
        UserRegistryRepository counting = new UserRegistryRepository(writer);
        counting.save(vault, new UserRegistry(1, new ArrayList<>()));
        writer.failBeforeWrite(5);              // a fifth write would fail; the fourth is users.enc

        counting.saveCheckpoint(vault, registryWith(record()));

        byte[] current = Files.readAllBytes(usersFile());
        for (int generation = 1; generation <= 3; generation++) {
            assertArrayEquals(current, Files.readAllBytes(backup(generation)));
        }
        assertEquals(USER_ID, repository.load(vault).getUsers().get(0).getUserId());
    }

    @Test
    void recoveringARegistryReseedsEveryBackupWithTheRestoredState() throws Exception {
        for (int save = 0; save < 4; save++) {
            repository.save(vault, registryWith(record()));
        }
        Files.writeString(usersFile(), "{}", UTF_8);

        assertEquals(USER_ID, repository.load(vault).getUsers().get(0).getUserId());

        byte[] restored = Files.readAllBytes(usersFile());
        for (int generation = 1; generation <= 3; generation++) {
            assertArrayEquals(restored, Files.readAllBytes(backup(generation)), "backup " + generation);
        }
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void anIntactRegistryLoadsWithoutWritingAnything() throws Exception {
        for (int save = 0; save < 4; save++) {
            repository.save(vault, registryWith(record()));
        }
        FileTime longAgo = FileTime.fromMillis(1_000_000_000_000L);
        List<Path> files = List.of(usersFile(), backup(1), backup(2), backup(3));
        List<byte[]> before = new ArrayList<>();
        for (Path file : files) {
            Files.setLastModifiedTime(file, longAgo);
            before.add(Files.readAllBytes(file));
        }

        repository.load(vault);

        for (int i = 0; i < files.size(); i++) {
            assertArrayEquals(before.get(i), Files.readAllBytes(files.get(i)), files.get(i).toString());
            assertEquals(longAgo, Files.getLastModifiedTime(files.get(i)), files.get(i).toString());
        }
        assertEquals(List.of("backups", "users.enc"), names(usersFile().getParent()));
        assertEquals(List.of("users.enc.1", "users.enc.2", "users.enc.3"), names(backup(1).getParent()));
    }

    @Test
    void aFailedReseedAfterRecoveryStillReturnsTheRecoveredRegistry() throws Exception {
        FailingWriter writer = new FailingWriter();
        UserRegistryRepository failing = new UserRegistryRepository(writer);
        for (int save = 0; save < 4; save++) {
            failing.save(vault, registryWith(record()));
        }
        Files.writeString(usersFile(), "{}", UTF_8);
        BackupRotator.takeRecoveryNotice();
        writer.failBeforeWrite(1);              // the reseed's first write; recovery's own copy is not a write()

        UserRegistry recovered = failing.load(vault);

        assertEquals(USER_ID, recovered.getUsers().get(0).getUserId());
        assertTrue(BackupRotator.takeRecoveryNotice());
        assertArrayEquals(Files.readAllBytes(backup(1)), Files.readAllBytes(usersFile()));
        // The armed failure was used up by the reseed attempt, so this write goes through.
        writer.write(root.resolve("probe"), new byte[]{1});
    }

    @Test
    void registryEncryptedUnderAnotherKeyIsRejected() throws Exception {
        repository.save(vault, registryWith(record()));
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);

        try (VaultContext other = context(otherKey)) {
            VaultStorageException error = assertThrows(
                    VaultStorageException.class,
                    () -> repository.load(other)
            );

            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
        }
    }

    @Test
    void tamperedRegistryIsRejected() throws Exception {
        repository.save(vault, registryWith(record()));
        JsonObject payload = JsonParser.parseString(
                Files.readString(usersFile(), UTF_8)
        ).getAsJsonObject();
        byte[] ciphertext = Base64.getDecoder().decode(payload.get("ciphertext").getAsString());
        ciphertext[ciphertext.length / 2] ^= 0x01;
        payload.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Files.writeString(usersFile(), payload.toString(), UTF_8);

        VaultStorageException error = assertThrows(
                VaultStorageException.class,
                () -> repository.load(vault)
        );

        assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
    }

    @Test
    void registryWithATypeConfusedEnvelopeFallsBackToAnAuthenticBackup() throws Exception {
        repository.save(vault, registryWith(record()));
        repository.save(vault, registryWith(record()));
        JsonObject envelope = JsonParser.parseString(
                Files.readString(usersFile(), UTF_8)
        ).getAsJsonObject();
        envelope.addProperty("nonce", 5);
        Files.writeString(usersFile(), envelope.toString(), UTF_8);

        assertEquals(USER_ID, repository.load(vault).getUsers().get(0).getUserId());
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void oversizedRegistryFallsBackToAnAuthenticBackup() throws Exception {
        repository.save(vault, registryWith(record()));
        repository.save(vault, new UserRegistry(1, new ArrayList<>(List.of(record()))));
        Files.delete(usersFile());
        BoundedFilesTest.sparseFile(usersFile(), 3L << 30);

        assertEquals(USER_ID, repository.load(vault).getUsers().get(0).getUserId());
        assertTrue(Files.size(usersFile()) < UserRegistryRepository.MAX_REGISTRY_BYTES);
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void oversizedRegistryBackupIsSkipped() throws Exception {
        repository.save(vault, registryWith(record()));
        repository.save(vault, registryWith(record()));
        repository.save(vault, registryWith(record()));
        Path backups = root.resolve(".encryptdrive").resolve("backups");
        Files.delete(backups.resolve("users.enc.1"));
        BoundedFilesTest.sparseFile(backups.resolve("users.enc.1"), 3L << 30);
        Files.writeString(usersFile(), "{}", UTF_8);

        assertEquals(USER_ID, repository.load(vault).getUsers().get(0).getUserId());
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void saveRefusesRegistryOverTheLimit() throws Exception {
        repository.save(vault, registryWith(record()));
        byte[] before = Files.readAllBytes(usersFile());
        UserRecord huge = new UserRecord(
                USER_ID, "x".repeat(17 * 1024 * 1024), "ExampleUser", "exampleuser",
                "2026-09-30T17:05:00Z", "7c8d9e0f-1a2b-4c3d-8e4f-5a6b7c8d9e0f",
                record().getUserKdf(), record().getWrappedUserMasterKey()
        );

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.save(vault, registryWith(huge))
        );

        assertEquals(VaultStorageException.Reason.TOO_LARGE, error.getReason());
        assertArrayEquals(before, Files.readAllBytes(usersFile()));
    }

    @Test
    void structurallyInvalidRegistriesAreRejected() throws Exception {
        UserRecord valid = record();
        List<List<UserRecord>> registries = List.of(
                List.of(withIds("not-a-uuid", valid.getManifestId(), "exampleuser")),
                List.of(withIds(USER_ID, "not-a-uuid", "exampleuser")),
                List.of(withIds(USER_ID, valid.getManifestId(), null)),
                List.of(new UserRecord(USER_ID, "Example Person", "ExampleUser", "exampleuser",
                        "2026-09-30T17:05:00Z", valid.getManifestId(), valid.getUserKdf(), null)),
                List.of(valid, withIds("9a1b2c3d-4e5f-4061-8273-a4b5c6d7e8f9",
                        "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f0", "exampleuser"))
        );

        for (List<UserRecord> users : registries) {
            writeWithoutValidation(new UserRegistry(1, new ArrayList<>(users)));   // no backups exist to recover from

            VaultStorageException error = assertThrows(
                    VaultStorageException.class, () -> repository.load(vault), users.toString()
            );
            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
        }
    }

    private static UserRecord withIds(String userId, String manifestId, String normalizedUsername) {
        UserRecord valid = record();
        return new UserRecord(userId, valid.getFullName(), valid.getUsername(), normalizedUsername,
                valid.getCreatedAt(), manifestId, valid.getUserKdf(), valid.getWrappedUserMasterKey());
    }

    @Test
    void saveRefusesADuplicateUsernameAndTouchesNothing() throws Exception {
        saveFourDistinctRegistries();
        Map<String, byte[]> before = diskState();

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.save(vault, withDuplicateUsername())
        );

        assertEquals(VaultStorageException.Reason.INVALID, error.getReason());
        assertSameDiskState(before);
    }

    @Test
    void saveCheckpointRefusesADuplicateUsernameAndTouchesNothing() throws Exception {
        saveFourDistinctRegistries();
        Map<String, byte[]> before = diskState();

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.saveCheckpoint(vault, withDuplicateUsername())
        );

        assertEquals(VaultStorageException.Reason.INVALID, error.getReason());
        assertSameDiskState(before);
    }

    private void saveFourDistinctRegistries() throws Exception {
        for (int save = 0; save < 4; save++) {
            repository.save(vault, registryWith(record()));
        }
    }

    private static UserRegistry withDuplicateUsername() {
        return new UserRegistry(1, new ArrayList<>(List.of(
                record(),
                withIds("9a1b2c3d-4e5f-4061-8273-a4b5c6d7e8f9", "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f0", "exampleuser")
        )));
    }

    /** Every file under the vault with its bytes: users.enc and all backup generations. */
    private Map<String, byte[]> diskState() throws IOException {
        Map<String, byte[]> state = new TreeMap<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                state.put(root.relativize(file).toString(), Files.readAllBytes(file));
            }
        }

        return state;
    }

    private void assertSameDiskState(Map<String, byte[]> before) throws IOException {
        Map<String, byte[]> after = diskState();

        assertEquals(before.keySet(), after.keySet());
        assertEquals(4, before.size());   // users.enc and three backups, so nothing was skipped
        before.forEach((file, bytes) -> assertArrayEquals(bytes, after.get(file), file));
    }

    /**
     * Puts authentic but damaged content in users.enc the way an older or buggy writer could have, so the
     * read-side validation can still be tested now that save() refuses to write it.
     */
    private void writeWithoutValidation(UserRegistry registry) throws IOException {
        Gson gson = new Gson();
        EncryptedPayload sealed = new AesGcmService().encrypt(
                gson.toJson(registry).getBytes(UTF_8), registryKey, Aad.users(VAULT_ID)
        );
        Files.writeString(usersFile(), gson.toJson(sealed), UTF_8);
    }

    private VaultContext context(byte[] key) {
        return new VaultContext(
                root, VAULT_ID, 1, "2026-09-30T17:00:00Z", SensitiveBytes.copyOf(key), () -> { }
        );
    }

    private Path usersFile() {
        return root.resolve(".encryptdrive").resolve("users.enc");
    }

    private Path backup(int generation) {
        return root.resolve(".encryptdrive/backups/users.enc." + generation);
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static UserRegistry registryWith(UserRecord record) {
        List<UserRecord> users = new ArrayList<>();
        users.add(record);
        return new UserRegistry(1, users);
    }

    private static UserRecord record() {
        return new UserRecord(
                USER_ID,
                "Example Person",
                "ExampleUser",
                "exampleuser",
                "2026-09-30T17:05:00Z",
                "7c8d9e0f-1a2b-4c3d-8e4f-5a6b7c8d9e0f",
                new KdfConfig("Argon2id", 65536, 3, 1, "MDEyMzQ1Njc4OWFiY2RlZg=="),
                new EncryptedPayload(1, "AES/GCM/NoPadding", "bm9uY2Vub25jZW5v", "d3JhcHBlZA==")
        );
    }
}
