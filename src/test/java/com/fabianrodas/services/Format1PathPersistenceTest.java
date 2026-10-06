package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.UserRegistry;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.UserRegistryRepository;
import com.fabianrodas.repositories.VaultRepository;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format1PathPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void absoluteVaultAndImportPathsNeverEnterDecryptedPersistentMetadata() throws Exception {
        String vaultSentinel = "HOST_PATH_SENTINEL_" + UUID.randomUUID();
        String sourceSentinel = "SOURCE_PARENT_SENTINEL_" + UUID.randomUUID();
        Path vaultParent = Files.createDirectories(tempDir.resolve(vaultSentinel));
        Path sourceParent = Files.createDirectories(tempDir.resolve(sourceSentinel));
        Path sourceRoot = Files.createDirectories(sourceParent.resolve("ImportedDirectory"));
        Path sourceFile = Files.writeString(sourceRoot.resolve("retained.txt"), "public vector content");

        try (TestVault vault = new TestVault(vaultParent)) {
            TestVault.Account alice = vault.register("pathaudit");
            FileService files = vault.files(alice);
            FileService.FolderImport result = files.importFolder(
                    sourceRoot, files.rootFolderId(), (done, total, bytes, totalBytes) -> { }
            );
            assertEquals(1, result.foldersCreated());
            assertEquals(1, result.filesImported());
            assertEquals(List.of(), result.failures());

            Path vaultRoot = vault.vault.root().toAbsolutePath();
            String headerJson = Files.readString(
                    VaultRepository.metaDir(vaultRoot).resolve(VaultRepository.VAULT_HEADER), UTF_8
            );
            byte[] registryKey = vault.vault.copyRegistryKey();
            byte[] userMasterKey = alice.userMasterKey().clone();
            byte[] registryPlaintext = null;
            byte[] manifestPlaintext = null;

            try {
                Gson gson = new Gson();
                EncryptedPayload usersEnvelope = gson.fromJson(
                        Files.readString(VaultRepository.usersFile(vaultRoot), UTF_8),
                        EncryptedPayload.class
                );
                registryPlaintext = new AesGcmService().decrypt(
                        usersEnvelope, registryKey, Aad.users(vault.vault.vaultId())
                );
                String registryJson = new String(registryPlaintext, UTF_8);
                UserRegistry registry = new UserRegistryRepository().load(vault.vault);
                assertEquals(1, registry.getUsers().size());
                assertTrue(registryJson.contains("pathaudit"));

                UUID userId = alice.identity().userId();
                UUID manifestId = alice.identity().manifestId();
                EncryptedPayload manifestEnvelope = gson.fromJson(
                        Files.readString(
                                VaultRepository.manifestsDir(vaultRoot).resolve(manifestId + ".enc"), UTF_8
                        ),
                        EncryptedPayload.class
                );
                manifestPlaintext = new AesGcmService().decrypt(
                        manifestEnvelope,
                        userMasterKey,
                        Aad.manifest(vault.vault.vaultId(), userId.toString())
                );
                String manifestJson = new String(manifestPlaintext, UTF_8);
                UserManifest manifest = new ManifestRepository(vault.vault).load(
                        userId, manifestId, userMasterKey
                );
                String registryModelJson = gson.toJson(registry);
                String manifestModelJson = gson.toJson(manifest);

                assertTrue(manifestJson.contains("ImportedDirectory"));
                assertTrue(manifestJson.contains("retained.txt"));
                assertEquals(3, manifest.getEntries().size());

                List<String> forbidden = List.of(
                        vaultSentinel,
                        sourceSentinel,
                        vaultRoot.toString(),
                        vaultParent.toAbsolutePath().toString(),
                        sourceParent.toAbsolutePath().toString(),
                        sourceRoot.toAbsolutePath().toString(),
                        sourceFile.toAbsolutePath().toString()
                );
                for (String secret : forbidden) {
                    assertAbsent(headerJson, secret, "vault.json");
                    assertAbsent(registryJson, secret, "decrypted registry bytes");
                    assertAbsent(manifestJson, secret, "decrypted manifest bytes");
                    assertAbsent(registryModelJson, secret, "registry model JSON");
                    assertAbsent(manifestModelJson, secret, "manifest model JSON");
                }
            } finally {
                Arrays.fill(registryKey, (byte) 0);
                Arrays.fill(userMasterKey, (byte) 0);
                if (registryPlaintext != null) {
                    Arrays.fill(registryPlaintext, (byte) 0);
                }
                if (manifestPlaintext != null) {
                    Arrays.fill(manifestPlaintext, (byte) 0);
                }
            }
        }
    }

    private static void assertAbsent(String text, String value, String subject) {
        assertFalse(text.contains(value), subject + " contains host path data: " + value);
    }
}
