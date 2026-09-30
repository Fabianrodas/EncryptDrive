package com.fabianrodas.repositories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private VaultContext context(byte[] key) {
        return new VaultContext(
                root, VAULT_ID, 1, "2026-09-30T17:00:00Z", SensitiveBytes.copyOf(key), () -> { }
        );
    }

    private Path usersFile() {
        return root.resolve(".encryptdrive").resolve("users.enc");
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
