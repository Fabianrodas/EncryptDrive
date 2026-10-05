package com.fabianrodas.repositories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.PendingDeletion;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.UserRecord;
import com.fabianrodas.models.UserRegistry;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.models.VaultHeader;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.Argon2KeyDeriver;
import com.fabianrodas.security.CryptoException;
import com.fabianrodas.security.SensitiveBytes;
import com.fabianrodas.services.FileServiceException;
import com.fabianrodas.services.ManifestService;
import com.fabianrodas.services.StreamingFileCryptoService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public fixed-input vectors for the language-independent Vault Format 1 contract. */
class FormatV1VectorTest {

    private static final Path VECTORS = Path.of("test-vectors", "format-v1");
    private static final String ENVELOPE_ALGORITHM = "AES/GCM/NoPadding";

    @TempDir
    Path tempDir;

    @Test
    void argon2idVectorsMatchIndependentImplementationsAndEncryptDriveParameters() throws IOException {
        JsonObject vectors = vectors();
        JsonObject argon2 = vectors.getAsJsonObject("argon2id");

        assertArgonVector(argon2.getAsJsonObject("reference"));
        assertArgonVector(argon2.getAsJsonObject("vaultPassword"));
        assertArgonVector(argon2.getAsJsonObject("accountPassword"));
    }

    @Test
    void aadVectorsPinEveryContextAsExactUtf8Bytes() throws IOException {
        JsonObject vectors = vectors();
        JsonObject ids = vectors.getAsJsonObject("ids");
        JsonObject aad = vectors.getAsJsonObject("aad");
        String vaultId = ids.get("vaultId").getAsString();
        String userId = ids.get("userId").getAsString();
        String fileId = ids.get("fileId").getAsString();

        assertAad(aad, "vaultKey", Aad.vaultKey(vaultId));
        assertAad(aad, "users", Aad.users(vaultId));
        assertAad(aad, "userKey", Aad.userKey(vaultId, userId));
        assertAad(aad, "manifest", Aad.manifest(vaultId, userId));
        assertAad(aad, "fileKey", Aad.fileKey(vaultId, userId, fileId));
        assertAad(aad, "fileContent", Aad.fileContent(vaultId, userId, fileId));
    }

    @Test
    void logicalNameVectorsPinAcceptedNamesAndSiblingCollisionRules() throws Exception {
        JsonObject vectors = vectors();
        JsonObject ids = vectors.getAsJsonObject("ids");
        UUID userId = UUID.fromString(ids.get("userId").getAsString());
        UUID rootId = UUID.fromString(ids.get("rootFolderId").getAsString());
        UserManifest manifest = new UserManifest(
                1,
                userId,
                rootId,
                new ArrayList<>(List.of(new ManifestEntry(
                        rootId, ManifestEntryKind.FOLDER, null, "/", "2026-10-05T12:00:00Z"
                )))
        );

        JsonObject names = vectors.getAsJsonObject("logicalNames");
        for (var element : names.getAsJsonArray("accepted")) {
            JsonObject example = element.getAsJsonObject();
            assertEquals(example.get("stored").getAsString(), new ManifestService(manifest)
                    .requireAvailableName(rootId, example.get("input").getAsString()));
        }

        for (var element : names.getAsJsonArray("rejected")) {
            JsonObject example = element.getAsJsonObject();
            FileServiceException error = assertThrows(FileServiceException.class,
                    () -> new ManifestService(manifest).requireAvailableName(rootId,
                            example.get("input").getAsString()));
            assertEquals(FileServiceException.Reason.valueOf(example.get("reason").getAsString()),
                    error.getReason());
        }

        JsonObject clash = names.getAsJsonObject("caseCollision");
        manifest.getEntries().add(new ManifestEntry(
                UUID.fromString("00000000-0000-4000-8000-000000000004"),
                ManifestEntryKind.FOLDER,
                rootId,
                clash.get("existing").getAsString(),
                "2026-10-05T12:00:00Z"
        ));
        FileServiceException collision = assertThrows(FileServiceException.class,
                () -> new ManifestService(manifest).requireAvailableName(rootId,
                        clash.get("input").getAsString()));
        assertEquals(FileServiceException.Reason.valueOf(clash.get("reason").getAsString()),
                collision.getReason());

        JsonObject forms = names.getAsJsonObject("distinctUnicodeForms");
        manifest.getEntries().add(new ManifestEntry(
                UUID.fromString("00000000-0000-4000-8000-000000000005"),
                ManifestEntryKind.FOLDER,
                rootId,
                forms.get("composed").getAsString(),
                "2026-10-05T12:00:00Z"
        ));
        assertEquals(forms.get("decomposed").getAsString(),
                new ManifestService(manifest).requireAvailableName(rootId,
                        forms.get("decomposed").getAsString()));
    }

    @Test
    void wrappedKeyVectorsMatchIndependentAesGcmAndUnwrapExactly() throws Exception {
        JsonObject vectors = vectors();
        AesGcmService aes = new AesGcmService();

        for (String name : new String[]{"wrappedRegistryKey", "wrappedUserMasterKey", "wrappedFileKey"}) {
            JsonObject vector = vectors.getAsJsonObject("keyWraps").getAsJsonObject(name);
            byte[] key = hex(vector, "keyHex");
            byte[] nonce = hex(vector, "nonceHex");
            byte[] aad = utf8(vector, "aadUtf8");
            byte[] plaintext = hex(vector, "plaintextHex");
            byte[] ciphertextAndTag = hex(vector, "ciphertextAndTagHex");

            assertEquals(48, ciphertextAndTag.length, name);
            assertArrayEquals(ciphertextAndTag, jceEncrypt(key, nonce, aad, plaintext), name);
            assertArrayEquals(plaintext, aes.unwrapKey(payload(vector), key, aad), name);
        }
    }

    @Test
    void fixedHeaderAndRegistryFilesLoadAndUnwrapTheirKeys() throws Exception {
        JsonObject vectors = vectors();
        JsonObject ids = vectors.getAsJsonObject("ids");
        String vaultId = ids.get("vaultId").getAsString();
        Path meta = Files.createDirectories(tempDir.resolve(VaultRepository.META_DIR));
        Files.copy(VECTORS.resolve("vault.json"), meta.resolve(VaultRepository.VAULT_HEADER));
        Files.copy(VECTORS.resolve("users.enc"), meta.resolve(VaultRepository.USERS_FILE));

        VaultHeader header = new VaultRepository().readHeader(tempDir);
        assertEquals(1, header.getFormatVersion());
        assertEquals(vaultId, header.getVaultId());

        JsonObject vaultKdf = vectors.getAsJsonObject("argon2id").getAsJsonObject("vaultPassword");
        byte[] vkek = new Argon2KeyDeriver().derive(
                vaultKdf.get("passwordUtf8").getAsString().toCharArray(), header.getKdf()
        );
        assertArrayEquals(hex(vaultKdf, "outputHex"), vkek);
        AesGcmService aes = new AesGcmService();
        byte[] registryKey = aes.unwrapKey(
                header.getWrappedRegistryKey(), vkek, Aad.vaultKey(vaultId)
        );
        assertArrayEquals(
                hex(vectors.getAsJsonObject("keyWraps").getAsJsonObject("wrappedRegistryKey"), "plaintextHex"),
                registryKey
        );

        Files.createDirectories(meta.resolve(VaultRepository.MANIFESTS_DIR));
        JsonObject userVector = vectors.getAsJsonObject("userRegistry");
        assertArrayEquals(
                hex(userVector, "ciphertextAndTagHex"),
                jceEncrypt(registryKey, hex(userVector, "nonceHex"), utf8(userVector, "aadUtf8"),
                        utf8(userVector, "plaintextUtf8"))
        );

        try (VaultContext context = new VaultContext(
                tempDir,
                vaultId,
                1,
                header.getCreatedAt(),
                SensitiveBytes.copyOf(registryKey),
                () -> { }
        )) {
            UserRegistry registry = new UserRegistryRepository().load(context);
            UserRecord user = registry.getUsers().get(0);
            assertEquals(ids.get("userId").getAsString(), user.getUserId());
            assertEquals("VectorUser", user.getUsername());

            JsonObject accountKdf = vectors.getAsJsonObject("argon2id").getAsJsonObject("accountPassword");
            byte[] accountKey = new Argon2KeyDeriver().derive(
                    accountKdf.get("passwordUtf8").getAsString().toCharArray(), user.getUserKdf()
            );
            assertArrayEquals(hex(accountKdf, "outputHex"), accountKey);
            byte[] userMasterKey = aes.unwrapKey(
                    user.getWrappedUserMasterKey(), accountKey,
                    Aad.userKey(vaultId, ids.get("userId").getAsString())
            );
            assertArrayEquals(hex(vectors.getAsJsonObject("keyWraps").getAsJsonObject("wrappedUserMasterKey"),
                    "plaintextHex"), userMasterKey);
        }
    }

    @Test
    void fixedManifestAndBlobFilesLoadAndMatchTheDocumentedLayout() throws Exception {
        JsonObject vectors = vectors();
        JsonObject ids = vectors.getAsJsonObject("ids");
        String vaultId = ids.get("vaultId").getAsString();
        UUID userId = UUID.fromString(ids.get("userId").getAsString());
        UUID manifestId = UUID.fromString(ids.get("manifestId").getAsString());
        UUID blobId = UUID.fromString(ids.get("blobId").getAsString());
        Path meta = Files.createDirectories(tempDir.resolve(VaultRepository.META_DIR));
        Path manifests = Files.createDirectories(meta.resolve(VaultRepository.MANIFESTS_DIR));
        Files.copy(VECTORS.resolve("manifests").resolve(manifestId + ".enc"),
                manifests.resolve(manifestId + ".enc"));

        byte[] registryKey = hex(vectors.getAsJsonObject("keyWraps").getAsJsonObject("wrappedRegistryKey"),
                "plaintextHex");
        JsonObject manifestVector = vectors.getAsJsonObject("manifest");
        byte[] userMasterKey = hex(manifestVector, "keyHex");
        assertArrayEquals(
                hex(manifestVector, "ciphertextAndTagHex"),
                jceEncrypt(userMasterKey, hex(manifestVector, "nonceHex"), utf8(manifestVector, "aadUtf8"),
                        utf8(manifestVector, "plaintextUtf8"))
        );

        try (VaultContext context = new VaultContext(
                tempDir, vaultId, 1, "2026-10-05T12:00:00Z", SensitiveBytes.copyOf(registryKey), () -> { }
        )) {
            var manifest = new ManifestRepository(context).load(userId, manifestId, userMasterKey);
            assertEquals(1, manifest.getFormatVersion());
            assertEquals(UUID.fromString(ids.get("rootFolderId").getAsString()), manifest.getRootFolderId());
            assertEquals(1, manifest.getPendingDeletions().size());
            PendingDeletion pending = manifest.getPendingDeletions().get(0);
            assertEquals(UUID.fromString(ids.get("pendingBlobId").getAsString()), pending.blobId());

            ManifestEntry file = manifest.getEntries().stream()
                    .filter(entry -> entry.getEntryId().toString().equals(ids.get("fileId").getAsString()))
                    .findFirst().orElseThrow();
            assertEquals(blobId, file.getBlobId());

            JsonObject blobVector = vectors.getAsJsonObject("fileBlob");
            byte[] blobBytes = hex(blobVector, "ciphertextAndTagHex");
            Path blobFile = new BlobRepository().blobPath(tempDir, blobId);
            Path expectedBlobRelative = Path.of(
                    "storage", "blobs", blobId.toString().replace("-", "").substring(0, 2), blobId + ".edv"
            );
            assertTrue(blobFile.endsWith(expectedBlobRelative));
            Files.createDirectories(blobFile.getParent());
            Files.copy(VECTORS.resolve(expectedBlobRelative),
                    blobFile,
                    StandardCopyOption.REPLACE_EXISTING);
            assertArrayEquals(blobBytes, Files.readAllBytes(blobFile));

            byte[] fileKey = new AesGcmService().unwrapKey(
                    file.getWrappedFileKey(), userMasterKey,
                    Aad.fileKey(vaultId, userId.toString(), file.getEntryId().toString())
            );
            byte[] nonce = Base64.getDecoder().decode(file.getContentNonce());
            byte[] aad = Aad.fileContent(vaultId, userId.toString(), file.getEntryId().toString());
            Path plaintext = tempDir.resolve("vector-plaintext.bin");
            new StreamingFileCryptoService().decrypt(blobFile, plaintext, fileKey, nonce, aad);
            assertArrayEquals(hex(blobVector, "plaintextHex"), Files.readAllBytes(plaintext));

            Path encryptedAgain = tempDir.resolve("roundtrip.edv.part");
            new StreamingFileCryptoService().encrypt(plaintext, encryptedAgain, fileKey, nonce, aad);
            assertArrayEquals(blobBytes, Files.readAllBytes(encryptedAgain));
        }
    }

    @Test
    void vectorCiphertextAndBlobTagsRejectTampering() throws Exception {
        JsonObject vectors = vectors();
        JsonObject userVector = vectors.getAsJsonObject("userRegistry");
        byte[] ciphertext = hex(userVector, "ciphertextAndTagHex");
        ciphertext[0] ^= 1;
        EncryptedPayload tampered = new EncryptedPayload(
                1,
                ENVELOPE_ALGORITHM,
                Base64.getEncoder().encodeToString(hex(userVector, "nonceHex")),
                Base64.getEncoder().encodeToString(ciphertext)
        );
        assertThrows(CryptoException.class, () -> new AesGcmService().decrypt(
                tampered,
                hex(userVector, "keyHex"),
                utf8(userVector, "aadUtf8")
        ));

        JsonObject blobVector = vectors.getAsJsonObject("fileBlob");
        byte[] badBlob = hex(blobVector, "ciphertextAndTagHex");
        badBlob[badBlob.length - 1] ^= 1;
        Path damaged = tempDir.resolve("damaged.edv");
        Path partial = tempDir.resolve("must-be-deleted.part");
        Files.write(damaged, badBlob);
        assertThrows(CryptoException.class, () -> new StreamingFileCryptoService().decrypt(
                damaged,
                partial,
                hex(blobVector, "keyHex"),
                hex(blobVector, "nonceHex"),
                utf8(blobVector, "aadUtf8")
        ));
        assertFalse(Files.exists(partial));
    }

    private static void assertArgonVector(JsonObject vector) {
        byte[] passwordBytes = hex(vector, "passwordUtf8Hex");
        char[] password = new String(passwordBytes, UTF_8).toCharArray();
        byte[] actual = new Argon2KeyDeriver().derive(
                password,
                hex(vector, "saltHex"),
                vector.get("memoryKiB").getAsInt(),
                vector.get("iterations").getAsInt(),
                vector.get("parallelism").getAsInt()
        );
        assertArrayEquals(hex(vector, "outputHex"), actual, vector.get("name").getAsString());
    }

    private static void assertAad(JsonObject all, String member, byte[] actual) {
        JsonObject vector = all.getAsJsonObject(member);
        assertArrayEquals(hex(vector, "utf8Hex"), actual, member);
        assertEquals(vector.get("text").getAsString(), new String(actual, UTF_8), member);
    }

    private static EncryptedPayload payload(JsonObject vector) {
        return new EncryptedPayload(
                1,
                ENVELOPE_ALGORITHM,
                Base64.getEncoder().encodeToString(hex(vector, "nonceHex")),
                Base64.getEncoder().encodeToString(hex(vector, "ciphertextAndTagHex"))
        );
    }

    private static byte[] jceEncrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(ENVELOPE_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    private static JsonObject vectors() throws IOException {
        return JsonParser.parseString(Files.readString(VECTORS.resolve("vectors.json"), UTF_8)).getAsJsonObject();
    }

    private static byte[] utf8(JsonObject object, String member) {
        return object.get(member).getAsString().getBytes(UTF_8);
    }

    private static byte[] hex(JsonObject object, String member) {
        return HexFormat.of().parseHex(object.get(member).getAsString());
    }
}
