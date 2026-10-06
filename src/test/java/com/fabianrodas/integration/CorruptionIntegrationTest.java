package com.fabianrodas.integration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.BlobRepository;
import com.fabianrodas.security.SensitiveBytes;
import com.fabianrodas.services.AuthException;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.FileServiceException;
import com.fabianrodas.services.RecoveryService;
import com.fabianrodas.services.VaultException;
import com.fabianrodas.services.VaultService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/*
 * Corruption matrix: each kind of damage maps to one outcome, and damaged
 * data never yields unverified plaintext. Metadata is recovered only from
 * backup generations that pass authenticated decryption.
 */
class CorruptionIntegrationTest {

    private static final String VAULT_PASSWORD = "correct vault password";

    @TempDir
    Path tempDir;

    private final VaultService vaultService = new VaultService();
    private Path root;

    @BeforeEach
    void createVault() {
        root = tempDir.resolve("vault");
        RecoveryService.takeRecoveryNotice();
    }

    @AfterEach
    void closeVault() {
        vaultService.closeVault();
    }

    @Test
    void malformedVaultHeaderIsReportedAsCorrupted() throws Exception {
        vaultService.createVault(root, VAULT_PASSWORD.toCharArray());
        vaultService.closeVault();
        Files.writeString(metaFile("vault.json"), "{\"formatVersion\": 1, \"vaultId\"", UTF_8);

        assertVaultReason(VaultException.Reason.CORRUPTED, this::unlock);
    }

    @Test
    void tamperedWrappedRegistryKeyIsAGenericUnlockFailure() throws Exception {
        vaultService.createVault(root, VAULT_PASSWORD.toCharArray());
        vaultService.closeVault();
        flipJsonCiphertext(metaFile("vault.json"), "wrappedRegistryKey");

        assertVaultReason(VaultException.Reason.UNLOCK_FAILED, this::unlock);
    }

    @Test
    void damagedRegistryIsRestoredFromTheNewestAuthenticBackup() throws Exception {
        VaultContext vault = vaultService.createVault(root, VAULT_PASSWORD.toCharArray());
        AuthService auth = new AuthService(vault);
        auth.register("Alice Example", "alice", "alice password".toCharArray());
        auth.register("Bob Example", "bob", "bob password".toCharArray());
        vaultService.closeVault();
        byte[] newestBackup = Files.readAllBytes(metaFile("backups/users.enc.1"));
        flipJsonCiphertext(metaFile("users.enc"), null);

        VaultContext reopened = unlock();

        assertTrue(RecoveryService.takeRecoveryNotice());
        assertArrayEquals(newestBackup, Files.readAllBytes(metaFile("users.enc")));
        AuthService recovered = new AuthService(reopened);
        recovered.login("alice", "alice password".toCharArray()).userMasterKey().close();
        assertAuthReason(
                AuthException.Reason.INVALID_CREDENTIALS,
                () -> recovered.login("bob", "bob password".toCharArray())
        );
    }

    @Test
    void damagedBackupsAreSkippedUntilOneAuthenticates() throws Exception {
        VaultContext vault = vaultService.createVault(root, VAULT_PASSWORD.toCharArray());
        AuthService auth = new AuthService(vault);
        auth.register("Alice Example", "alice", "alice password".toCharArray());
        auth.register("Bob Example", "bob", "bob password".toCharArray());
        auth.register("Carol Example", "carol", "carol password".toCharArray());
        vaultService.closeVault();
        flipJsonCiphertext(metaFile("users.enc"), null);
        flipJsonCiphertext(metaFile("backups/users.enc.1"), null);

        VaultContext reopened = unlock();

        assertTrue(RecoveryService.takeRecoveryNotice());
        new AuthService(reopened).login("alice", "alice password".toCharArray()).userMasterKey().close();
    }

    @Test
    void registryWithoutAnAuthenticBackupCannotBeOpened() throws Exception {
        vaultService.createVault(root, VAULT_PASSWORD.toCharArray());
        vaultService.closeVault();
        flipJsonCiphertext(metaFile("users.enc"), null);

        assertVaultReason(VaultException.Reason.CORRUPTED, this::unlock);
        assertFalse(RecoveryService.takeRecoveryNotice());
    }

    @Test
    void damagedManifestIsRestoredFromBackup() throws Exception {
        Signed alice = signedIn();
        FileService files = alice.files();
        ManifestEntry kept = files.createFolder("Kept", files.rootFolderId());
        files.createFolder("Newest", files.rootFolderId());
        flipJsonCiphertext(manifestFile(alice.identity()), null);

        List<ManifestEntry> children = files.listChildren(files.rootFolderId());

        assertEquals(List.of(kept.getEntryId()), children.stream().map(ManifestEntry::getEntryId).toList());
        assertTrue(RecoveryService.takeRecoveryNotice());
    }

    @Test
    void damagedBackupManifestDoesNotAffectTheCurrentOne() throws Exception {
        Signed alice = signedIn();
        FileService files = alice.files();
        files.createFolder("One", files.rootFolderId());
        files.createFolder("Two", files.rootFolderId());
        flipJsonCiphertext(
                metaFile("backups/manifests/" + alice.identity().manifestId() + ".enc.1"), null
        );

        assertEquals(2, files.listChildren(files.rootFolderId()).size());
        assertFalse(RecoveryService.takeRecoveryNotice());
    }

    @Test
    void manifestWithoutAnAuthenticBackupIsReportedAsCorrupted() throws Exception {
        Signed alice = signedIn();
        flipJsonCiphertext(manifestFile(alice.identity()), null);

        assertFileReason(
                FileServiceException.Reason.CORRUPTED,
                () -> alice.files().rootFolderId()
        );
    }

    @Test
    void damagedBlobIsReportedAndNeverExported() throws Exception {
        Signed alice = signedIn();
        FileService files = alice.files();
        Path source = Files.write(tempDir.resolve("photo.jpg"), new byte[70_000]);
        ManifestEntry photo = files.importFile(source, files.rootFolderId());
        Path blob = new BlobRepository().blobPath(root, photo.getBlobId());

        for (long position : new long[]{0, Files.size(blob) / 2, Files.size(blob) - 1}) {
            flipByte(blob, position);
            Path out = Files.createDirectories(tempDir.resolve("out-" + position));

            assertFileReason(
                    FileServiceException.Reason.INTEGRITY,
                    () -> files.exportEntry(photo.getEntryId(), out.resolve("photo.jpg"))
            );

            try (var listing = Files.list(out)) {
                assertEquals(0, listing.count());
            }
            flipByte(blob, position);
        }
    }

    // ------------------------------------------------------------ helpers

    private record Signed(VaultContext vault, UserSessionIdentity identity, byte[] userMasterKey) {
        FileService files() {
            Supplier<SensitiveBytes> key = () -> SensitiveBytes.copyOf(userMasterKey);
            return new FileService(vault, identity, key);
        }
    }

    private Signed signedIn() throws Exception {
        VaultContext vault = vaultService.createVault(root, VAULT_PASSWORD.toCharArray());
        AuthService auth = new AuthService(vault);
        auth.register("Alice Example", "alice", "alice password".toCharArray());
        UserLoginResult login = auth.login("alice", "alice password".toCharArray());

        try (SensitiveBytes key = login.userMasterKey()) {
            return new Signed(vault, login.identity(), key.copy());
        }
    }

    private VaultContext unlock() throws VaultException {
        return vaultService.unlockVault(root, VAULT_PASSWORD.toCharArray());
    }

    private Path metaFile(String relative) {
        return root.resolve(".encryptdrive").resolve(relative);
    }

    private Path manifestFile(UserSessionIdentity identity) {
        return metaFile("manifests/" + identity.manifestId() + ".enc");
    }

    /** Flips one ciphertext byte of an envelope (top level, or inside the named member). */
    private static void flipJsonCiphertext(Path file, String member) throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(file, UTF_8)).getAsJsonObject();
        JsonObject envelope = member == null ? json : json.getAsJsonObject(member);
        byte[] ciphertext = Base64.getDecoder().decode(envelope.get("ciphertext").getAsString());
        ciphertext[ciphertext.length / 3] ^= 0x01;
        envelope.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Files.writeString(file, json.toString(), UTF_8);
    }

    private static void flipByte(Path file, long position) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer one = java.nio.ByteBuffer.allocate(1);
            channel.read(one, position);
            one.put(0, (byte) (one.get(0) ^ 0x01)).rewind();
            channel.write(one, position);
        }
    }

    private static void assertVaultReason(VaultException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(VaultException.class, action).getReason());
    }

    private static void assertAuthReason(AuthException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(AuthException.class, action).getReason());
    }

    private static void assertFileReason(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }
}
