package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.EncryptedFileDescriptor;
import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.models.WorkspaceStats;
import com.fabianrodas.repositories.BlobRepository;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.CryptoException;
import com.fabianrodas.security.SensitiveBytes;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class FileServiceTest {

    @TempDir
    Path tempDir;

    private final VaultService vaultService = new VaultService();
    private VaultContext vault;
    private Account alice;

    private record Account(UserSessionIdentity identity, byte[] userMasterKey) {
        Supplier<SensitiveBytes> key() {
            return () -> SensitiveBytes.copyOf(userMasterKey);
        }
    }

    @BeforeEach
    void openVault() throws Exception {
        vault = vaultService.createVault(
                tempDir.resolve("vault"), "correct vault password".toCharArray()
        );
        alice = register("alice");
    }

    @AfterEach
    void closeVault() {
        vaultService.closeVault();
    }

    // ------------------------------------------------------------- session

    @Test
    void closedVaultRefusesManifestLoadsWithoutRecovering() throws Exception {
        FileService files = files(alice);
        files.createFolder("One", files.rootFolderId());
        files.createFolder("Two", files.rootFolderId());
        Path manifest = vault.root().resolve(".encryptdrive").resolve("manifests")
                .resolve(alice.identity().manifestId() + ".enc");
        JsonObject envelope = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        byte[] ciphertext = Base64.getDecoder().decode(envelope.get("ciphertext").getAsString());
        ciphertext[ciphertext.length / 2] ^= 0x01;   // a load would restore a backup over it
        envelope.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Files.writeString(manifest, envelope.toString());
        byte[] damaged = Files.readAllBytes(manifest);
        RecoveryService.takeRecoveryNotice();

        vault.close();

        assertReason(FileServiceException.Reason.STORAGE, files::listTrash);
        assertArrayEquals(damaged, Files.readAllBytes(manifest));
        assertFalse(RecoveryService.takeRecoveryNotice());
    }

    // ------------------------------------------------------------- import

    @Test
    void importedFileExportsByteForByte() throws Exception {
        byte[] content = random(200_000);
        Path source = source("budget.xlsx", content);
        FileService files = files(alice);

        ManifestEntry entry = files.importFile(source, files.rootFolderId());
        Path exported = Files.createDirectories(tempDir.resolve("out")).resolve("budget.xlsx");
        files.exportEntry(entry.getEntryId(), exported);

        assertArrayEquals(content, Files.readAllBytes(exported));
        assertEquals(ManifestEntryKind.FILE, entry.getKind());
        assertEquals("budget.xlsx", entry.getName());
        assertEquals(200_000L, entry.getPlainSize());
        assertEquals(List.of(entry.getEntryId()), ids(files.listChildren(files.rootFolderId())));
        assertEquals(200_016L, Files.size(new BlobRepository().blobPath(vault.root(), entry.getBlobId())));
        assertTrue(Files.exists(source));
        assertEquals(List.of("budget.xlsx"), names(exported.getParent()));
    }

    @Test
    void importedFilesRevealNeitherNamesNorContentAtRest() throws Exception {
        String marker = "EncryptDrive plaintext marker for the at-rest scan 0123456789ABCDEF";
        FileService files = files(alice);
        ManifestEntry folder = files.createFolder("Quarterly Taxes", files.rootFolderId());
        files.importFile(source("salary-report.pdf", marker.getBytes(UTF_8)), folder.getEntryId());
        vaultService.closeVault();

        try (Stream<Path> paths = Files.walk(vault.root())) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String raw = new String(Files.readAllBytes(file), ISO_8859_1);

                for (String secret : List.of("salary-report", "Quarterly Taxes", marker)) {
                    assertFalse(raw.contains(secret), file + " reveals " + secret);
                }
            }
        }
    }

    @Test
    void duplicateNameIsRejectedWithoutLeavingABlob() throws Exception {
        FileService files = files(alice);
        files.importFile(source("notes.txt", "one".getBytes(UTF_8)), files.rootFolderId());

        assertReason(
                FileServiceException.Reason.DUPLICATE_NAME,
                () -> files.importFile(source("NOTES.TXT", "two".getBytes(UTF_8)), files.rootFolderId())
        );

        assertEquals(1, blobFiles().size());
    }

    @Test
    void encryptionFailureLeavesNoEntryAndNoBlob() throws Exception {
        StreamingFileCryptoService failing = new StreamingFileCryptoService() {
            @Override
            public EncryptedFileDescriptor encrypt(
                    Path source, Path part, byte[] key, byte[] nonce, byte[] aad, LongConsumer progress
            ) throws IOException {
                Files.write(part, new byte[100]);
                throw new IOException("disk full");
            }
        };
        FileService files = new FileService(
                vault, alice.identity(), alice.key(),
                new ManifestRepository(vault), new BlobRepository(), failing
        );

        assertReason(
                FileServiceException.Reason.STORAGE,
                () -> files.importFile(source("a.txt", "data".getBytes(UTF_8)), files.rootFolderId())
        );

        assertEquals(List.of(), files(alice).listChildren(files.rootFolderId()));
        assertEquals(List.of(), blobFiles());
    }

    @Test
    void manifestSaveFailureDeletesTheNewBlob() throws Exception {
        ManifestRepository failingSave = new ManifestRepository(vault) {
            @Override
            public void save(UserManifest manifest, UUID manifestId, byte[] userMasterKey)
                    throws VaultStorageException {
                throw new VaultStorageException(VaultStorageException.Reason.IO);
            }
        };
        FileService files = new FileService(
                vault, alice.identity(), alice.key(),
                failingSave, new BlobRepository(), new StreamingFileCryptoService()
        );

        assertReason(
                FileServiceException.Reason.STORAGE,
                () -> files.importFile(source("a.txt", "data".getBytes(UTF_8)), files.rootFolderId())
        );

        assertEquals(List.of(), files(alice).listChildren(files.rootFolderId()));
        assertEquals(List.of(), blobFiles());
    }

    @Test
    void sourceThatDisappearsDuringImportLeavesNothing() throws Exception {
        StreamingFileCryptoService deletesSource = new StreamingFileCryptoService() {
            @Override
            public EncryptedFileDescriptor encrypt(
                    Path source, Path part, byte[] key, byte[] nonce, byte[] aad, LongConsumer progress
            ) throws IOException {
                Files.delete(source);
                return super.encrypt(source, part, key, nonce, aad, progress);
            }
        };
        FileService files = new FileService(
                vault, alice.identity(), alice.key(),
                new ManifestRepository(vault), new BlobRepository(), deletesSource
        );

        assertReason(
                FileServiceException.Reason.SOURCE_UNREADABLE,
                () -> files.importFile(source("gone.txt", "data".getBytes(UTF_8)), files.rootFolderId())
        );

        assertEquals(List.of(), files(alice).listChildren(files.rootFolderId()));
        assertEquals(List.of(), blobFiles());
    }

    @Test
    void missingOrNonRegularSourcesAreRejected() throws Exception {
        FileService files = files(alice);

        assertReason(
                FileServiceException.Reason.SOURCE_UNREADABLE,
                () -> files.importFile(tempDir.resolve("missing.txt"), files.rootFolderId())
        );
        assertReason(
                FileServiceException.Reason.SOURCE_UNREADABLE,
                () -> files.importFile(tempDir, files.rootFolderId())
        );
    }

    // ------------------------------------------------------------ folders

    @Test
    void foldersNestAndListTheirChildren() throws Exception {
        FileService files = files(alice);
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        ManifestEntry sub = files.createFolder("Sub", docs.getEntryId());
        ManifestEntry file = files.importFile(source("a.txt", "a".getBytes(UTF_8)), docs.getEntryId());

        assertEquals(List.of(docs.getEntryId()), ids(files.listChildren(files.rootFolderId())));
        assertEquals(List.of(sub.getEntryId(), file.getEntryId()), ids(files.listChildren(docs.getEntryId())));
        assertEquals(
                List.of(files.rootFolderId(), docs.getEntryId(), sub.getEntryId()),
                ids(files.folderView(sub.getEntryId()).path())
        );
    }

    @Test
    void folderExportRecreatesTheTreeWithoutTrashedItems() throws Exception {
        FileService files = files(alice);
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        ManifestEntry sub = files.createFolder("Sub", docs.getEntryId());
        files.importFile(source("a.txt", "alpha".getBytes(UTF_8)), docs.getEntryId());
        files.importFile(source("b.txt", "beta".getBytes(UTF_8)), sub.getEntryId());
        ManifestEntry trashed = files.importFile(source("old.txt", "old".getBytes(UTF_8)), docs.getEntryId());
        files.moveToTrash(trashed.getEntryId());

        Path target = tempDir.resolve("export").resolve("Docs");
        files.exportEntry(docs.getEntryId(), target);

        assertEquals(List.of("Sub", "a.txt"), names(target));
        assertEquals("alpha", Files.readString(target.resolve("a.txt"), UTF_8));
        assertEquals("beta", Files.readString(target.resolve("Sub").resolve("b.txt"), UTF_8));
    }

    @Test
    void exportSanitizesLogicalNamesForWindows() throws Exception {
        FileService files = files(alice);
        ManifestEntry folder = files.createFolder("2025/2026: plans", files.rootFolderId());
        files.createFolder("CON", folder.getEntryId());
        files.createFolder("a:b", folder.getEntryId());
        files.createFolder("a?b", folder.getEntryId());

        Path target = tempDir.resolve("export").resolve(FileService.safeFileName(folder.getName()));
        files.exportEntry(folder.getEntryId(), target);

        assertEquals("2025_2026_ plans", target.getFileName().toString());
        assertEquals(List.of("_CON", "a_b", "a_b (2)"), names(target));
    }

    @Test
    void exportTargetsAreWindowsSafeAndUnique() throws Exception {
        FileService files = files(alice);
        ManifestEntry colon = files.createFolder("a:b", files.rootFolderId());
        ManifestEntry question = files.createFolder("a?b", files.rootFolderId());
        ManifestEntry report = files.importFile(source("report.pdf", new byte[3]), files.rootFolderId());
        Path directory = tempDir.resolve("export");

        assertEquals(
                List.of(directory.resolve("a_b"), directory.resolve("a_b (2)"), directory.resolve("report.pdf")),
                files.exportTargets(
                        List.of(colon.getEntryId(), question.getEntryId(), report.getEntryId()),
                        directory
                )
        );
    }

    @Test
    void importReportsProgressUpToTheFileSize() throws Exception {
        FileService files = files(alice);
        List<Long> reported = new java.util.ArrayList<>();

        files.importFile(source("big.bin", random(300_000)), files.rootFolderId(), reported::add);

        assertEquals(300_000L, reported.get(reported.size() - 1));
    }

    @Test
    void safeFileNameReplacesCharactersWindowsRejects() {
        assertEquals("a_b_c_d_e_f_g_h_i", FileService.safeFileName("a<b>c:d\"e/f\\g|h?i"));
        assertEquals("tab_name", FileService.safeFileName("tab\tname"));
        assertEquals("trailing", FileService.safeFileName("trailing. . "));
        assertEquals("_nul.txt", FileService.safeFileName("nul.txt"));
        assertEquals("_", FileService.safeFileName("..."));
        assertEquals("résumé 2026.pdf", FileService.safeFileName("résumé 2026.pdf"));
    }

    // -------------------------------------------------------------- trash

    @Test
    void trashingAFolderHidesItsSubtreeUntilRestored() throws Exception {
        FileService files = files(alice);
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        ManifestEntry file = files.importFile(source("a.txt", "a".getBytes(UTF_8)), docs.getEntryId());

        files.moveToTrash(docs.getEntryId());

        assertEquals(List.of(), files.listChildren(files.rootFolderId()));
        assertEquals(List.of(docs.getEntryId()), ids(files.listTrash()));
        ManifestEntry trashedDocs = files.listTrash().get(0);
        assertNotNull(trashedDocs.getDeletedAt());

        files.restore(docs.getEntryId());

        assertEquals(List.of(docs.getEntryId()), ids(files.listChildren(files.rootFolderId())));
        assertEquals(List.of(file.getEntryId()), ids(files.listChildren(docs.getEntryId())));
        assertEquals(List.of(), files.listTrash());
    }

    @Test
    void restoreFallsBackToTheRootWhenTheOriginalFolderIsGone() throws Exception {
        FileService files = files(alice);
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        ManifestEntry file = files.importFile(source("a.txt", "a".getBytes(UTF_8)), docs.getEntryId());
        files.moveToTrash(file.getEntryId());
        files.moveToTrash(docs.getEntryId());

        files.restore(file.getEntryId());

        assertEquals(List.of(file.getEntryId()), ids(files.listChildren(files.rootFolderId())));
        assertEquals(List.of(docs.getEntryId()), ids(files.listTrash()));
    }

    @Test
    void restoreRenamesWhenTheNameIsTakenAgain() throws Exception {
        FileService files = files(alice);
        ManifestEntry first = files.importFile(source("a.txt", "one".getBytes(UTF_8)), files.rootFolderId());
        files.moveToTrash(first.getEntryId());
        files.importFile(source("a.txt", "two".getBytes(UTF_8)), files.rootFolderId());

        files.restore(first.getEntryId());

        assertEquals(
                List.of("a (2).txt", "a.txt"),
                files.listChildren(files.rootFolderId()).stream().map(ManifestEntry::getName).sorted().toList()
        );
    }

    @Test
    void permanentDeleteRemovesTheSubtreeAndItsBlobs() throws Exception {
        FileService files = files(alice);
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        files.importFile(source("a.txt", "a".getBytes(UTF_8)), docs.getEntryId());
        ManifestEntry kept = files.importFile(source("keep.txt", "k".getBytes(UTF_8)), files.rootFolderId());
        files.moveToTrash(docs.getEntryId());

        files.permanentlyDelete(List.of(docs.getEntryId()));

        assertEquals(List.of(), files.listTrash());
        assertEquals(List.of(kept.getEntryId()), ids(files.listChildren(files.rootFolderId())));
        assertEquals(1, blobFiles().size());
        assertEquals(new WorkspaceStats(1, 1, 17, 0, 0), files.stats());
    }

    @Test
    void onlyTrashedEntriesCanBeDeletedPermanently() throws Exception {
        FileService files = files(alice);
        ManifestEntry file = files.importFile(source("a.txt", "a".getBytes(UTF_8)), files.rootFolderId());

        assertReason(
                FileServiceException.Reason.NOT_IN_TRASH,
                () -> files.permanentlyDelete(List.of(file.getEntryId()))
        );
        assertEquals(1, blobFiles().size());
    }

    @Test
    void theRootFolderCannotBeTrashed() {
        FileService files = files(alice);

        assertReason(
                FileServiceException.Reason.PROTECTED,
                () -> files.moveToTrash(files.rootFolderId())
        );
    }

    // -------------------------------------------------------------- stats

    @Test
    void statsComeFromTheEncryptedManifestAndBlobSizes() throws Exception {
        FileService files = files(alice);
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        files.importFile(source("ten.bin", new byte[10]), files.rootFolderId());
        files.importFile(source("twenty.bin", new byte[20]), docs.getEntryId());
        files.importFile(source("thirty.bin", new byte[30]), files.rootFolderId());
        files.moveToTrash(docs.getEntryId());

        assertEquals(new WorkspaceStats(2, 40, 10 + 20 + 30 + 3 * 16, 1, 0), files.stats());
    }

    // ---------------------------------------------------------- integrity

    @Test
    void tamperedBlobFailsExportAndLeavesNoPlaintext() throws Exception {
        FileService files = files(alice);
        ManifestEntry file = files.importFile(source("a.txt", random(5_000)), files.rootFolderId());
        Path blob = new BlobRepository().blobPath(vault.root(), file.getBlobId());
        try (FileChannel channel = FileChannel.open(blob, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer one = java.nio.ByteBuffer.allocate(1);
            channel.read(one, 2_500);
            one.put(0, (byte) (one.get(0) ^ 0x01)).rewind();
            channel.write(one, 2_500);
        }
        Path out = Files.createDirectories(tempDir.resolve("out"));

        assertReason(
                FileServiceException.Reason.INTEGRITY,
                () -> files.exportEntry(file.getEntryId(), out.resolve("a.txt"))
        );

        assertEquals(List.of(), names(out));
    }

    @Test
    void filesStillExportAfterAPasswordChange() throws Exception {
        byte[] content = random(50_000);
        FileService files = files(alice);
        ManifestEntry entry = files.importFile(source("keep.bin", content), files.rootFolderId());
        AuthService auth = new AuthService(vault);

        auth.changePassword(alice.identity().userId(), "alice password".toCharArray(), "alice new password".toCharArray());

        UserLoginResult login = auth.login("alice", "alice new password".toCharArray());
        try (SensitiveBytes key = login.userMasterKey()) {
            FileService again = new FileService(vault, login.identity(), () -> SensitiveBytes.copyOf(key.copy()));
            Path out = tempDir.resolve("keep-out.bin");
            again.exportEntry(entry.getEntryId(), out);
            assertArrayEquals(content, Files.readAllBytes(out));
        }
    }

    // ---------------------------------------------------- user isolation

    @Test
    void anotherUserCannotListExportOrDecryptTheFile() throws Exception {
        FileService aliceFiles = files(alice);
        ManifestEntry secret = aliceFiles.importFile(
                source("secret.txt", "alice only".getBytes(UTF_8)), aliceFiles.rootFolderId()
        );
        Account bob = register("bob");
        FileService bobFiles = files(bob);

        assertEquals(List.of(), bobFiles.listChildren(bobFiles.rootFolderId()));
        assertReason(
                FileServiceException.Reason.NOT_FOUND,
                () -> bobFiles.exportEntry(secret.getEntryId(), tempDir.resolve("stolen.txt"))
        );
        assertThrows(CryptoException.class, () -> new AesGcmService().unwrapKey(
                secret.getWrappedFileKey(),
                bob.userMasterKey(),
                Aad.fileKey(vault.vaultId(), bob.identity().userId().toString(), secret.getEntryId().toString())
        ));
        assertThrows(CryptoException.class, () -> new AesGcmService().unwrapKey(
                secret.getWrappedFileKey(),
                bob.userMasterKey(),
                Aad.fileKey(vault.vaultId(), alice.identity().userId().toString(), secret.getEntryId().toString())
        ));
        assertFalse(Files.exists(tempDir.resolve("stolen.txt")));
    }

    // ------------------------------------------------------------ helpers

    private Account register(String username) throws AuthException {
        AuthService auth = new AuthService(vault);
        auth.register(username + " Example", username, (username + " password").toCharArray());
        UserLoginResult login = auth.login(username, (username + " password").toCharArray());

        try (SensitiveBytes key = login.userMasterKey()) {
            return new Account(login.identity(), key.copy());
        }
    }

    private FileService files(Account account) {
        return new FileService(vault, account.identity(), account.key());
    }

    private Path source(String name, byte[] content) throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("sources").resolve(UUID.randomUUID().toString()));
        return Files.write(directory.resolve(name), content);
    }

    private List<Path> blobFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(vault.root().resolve("storage"))) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static List<UUID> ids(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getEntryId).toList();
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        FileServiceException error = assertThrows(FileServiceException.class, action);
        assertEquals(reason, error.getReason());
    }
}
