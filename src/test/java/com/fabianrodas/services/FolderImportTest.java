package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class FolderImportTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account alice;
    private FileService files;
    private UUID root;
    private Path source;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.register("alice");
        files = vault.files(alice);
        root = files.rootFolderId();
        source = Files.createDirectories(tempDir.resolve("input").resolve("Photos"));
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void importsTheHierarchyIncludingEmptyFolders() throws Exception {
        write("a.txt", "a");
        write("2024/b.txt", "b");
        write("2024/summer/c.txt", "c");
        Files.createDirectories(source.resolve("empty"));

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(4, result.foldersCreated());
        assertEquals(3, result.filesImported());
        assertEquals(List.of(), result.failures());
        assertEquals(Map.of(
                "Photos", "FOLDER", "Photos/a.txt", "FILE", "Photos/2024", "FOLDER",
                "Photos/2024/b.txt", "FILE", "Photos/2024/summer", "FOLDER",
                "Photos/2024/summer/c.txt", "FILE", "Photos/empty", "FOLDER"
        ), tree());
    }

    @Test
    void importedFilesExportByteForByte() throws Exception {
        write("2024/summer/c.txt", "summer content");

        files.importFolder(source, root, (a, b, c, d) -> { });

        ManifestEntry photos = child(root, "Photos");
        ManifestEntry summer = child(child(photos.getEntryId(), "2024").getEntryId(), "summer");
        Path out = tempDir.resolve("c-out.txt");
        files.exportEntry(child(summer.getEntryId(), "c.txt").getEntryId(), out);
        assertEquals("summer content", Files.readString(out));
    }

    @Test
    void anEmptyFileInsideAFolderImportsAndTheManifestStillLoads() throws Exception {
        write("sub/empty.bin", "");

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(1, result.filesImported());
        assertEquals(List.of(), result.failures());
        // A fresh service reads the manifest back from disk through the validator.
        assertEquals(
                Map.of("Photos", "FOLDER", "Photos/sub", "FOLDER", "Photos/sub/empty.bin", "FILE"),
                tree(vault.files(alice))
        );
        ManifestEntry sub = child(child(root, "Photos").getEntryId(), "sub");
        assertEquals(0L, child(sub.getEntryId(), "empty.bin").getPlainSize());
    }

    @Test
    void theSourceTreeIsNeverModified() throws Exception {
        write("a.txt", "a");
        write("2024/b.txt", "b");
        Map<String, String> before = snapshot(source);

        files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(before, snapshot(source));
    }

    @Test
    void aTakenFolderNameAbortsBeforeAnythingIsImported() throws Exception {
        files.createFolder("photos", root);
        write("a.txt", "a");

        assertReason(FileServiceException.Reason.DUPLICATE_NAME,
                () -> files.importFolder(source, root, (a, b, c, d) -> { }));

        assertEquals(List.of("photos"), names(files.listChildren(root)));
        assertEquals(List.of(), vault.blobFiles());
    }

    @Test
    void aMissingSourceOrAFileIsRefused() throws Exception {
        assertReason(FileServiceException.Reason.SOURCE_UNREADABLE,
                () -> files.importFolder(source.resolve("missing"), root, (a, b, c, d) -> { }));
        assertReason(FileServiceException.Reason.SOURCE_UNREADABLE,
                () -> files.importFolder(write("a.txt", "a"), root, (a, b, c, d) -> { }));

        assertEquals(List.of(), files.listChildren(root));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void junctionsInsideTheSourceAreNotFollowed() throws Exception {
        write("a.txt", "a");
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "outside the tree");
        junction(source.resolve("link"), outside);
        // A junction back to the tree itself would otherwise recurse forever.
        junction(source.resolve("loop"), source);

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(2, result.linksSkipped());
        assertEquals(List.of(), result.failures());
        assertEquals(Map.of("Photos", "FOLDER", "Photos/a.txt", "FILE"), tree());
        assertEquals(1, vault.blobFiles().size());
        assertTrue(Files.exists(outside.resolve("secret.txt")));
    }

    @Test
    void symbolicLinksInsideTheSourceAreNotFollowed() throws Exception {
        write("a.txt", "a");
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Path outside = Files.writeString(outsideDir.resolve("outside.txt"), "outside the tree");

        try {
            Files.createSymbolicLink(source.resolve("link.txt"), outside);
            Files.createSymbolicLink(source.resolve("linked-folder"), outsideDir);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "creating symbolic links needs Developer Mode or administrator rights");
        }

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(2, result.linksSkipped());
        assertEquals(List.of(), result.failures());
        assertEquals(Map.of("Photos", "FOLDER", "Photos/a.txt", "FILE"), tree());
        assertEquals(1, vault.blobFiles().size());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLockedFileIsReportedAndTheRestIsImported() throws Exception {
        write("a.txt", "a");
        Path locked = write("b.txt", "b");

        try (FileChannel channel = FileChannel.open(locked, StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

            assertEquals(1, result.filesImported());
            assertFalse(result.stopped());
            assertEquals(List.of(new FileService.ImportFailure(
                    Path.of("Photos", "b.txt").toString(), FileServiceException.Reason.SOURCE_UNREADABLE)),
                    result.failures());
        }

        assertEquals(1, vault.blobFiles().size());
    }

    @Test
    void aSourceThatContainsTheVaultIsRefused() throws Exception {
        assertReason(FileServiceException.Reason.INSIDE_VAULT,
                () -> files.importFolder(vault.vault.root().getParent(), root, (a, b, c, d) -> { }));
    }

    @Test
    void aSourceInsideTheVaultIsRefused() throws Exception {
        assertReason(FileServiceException.Reason.INSIDE_VAULT,
                () -> files.importFolder(vault.vault.root().resolve("storage"), root, (a, b, c, d) -> { }));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aDifferentlyCasedPathToTheVaultIsStillRefused() throws Exception {
        Path vaultRoot = vault.vault.root();

        for (Path overlapping : List.of(vaultRoot, vaultRoot.resolve("storage"), vaultRoot.getParent())) {
            for (String spelling : List.of(
                    overlapping.toString().toUpperCase(Locale.ROOT),
                    overlapping.toString().toLowerCase(Locale.ROOT))) {
                assertReason(FileServiceException.Reason.INSIDE_VAULT,
                        () -> files.importFolder(Path.of(spelling), root, (a, b, c, d) -> { }));
            }
        }

        assertEquals(List.of(), files.listChildren(root));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void namesThatClashInsideTheTreeSkipOnlyTheClashingItem() throws Exception {
        // NTFS keeps "key" and "Key" (Kelvin sign) apart; EncryptDrive compares names ignoring case and treats them as equal.
        write("key.txt", "plain k");
        write("Key.txt", "kelvin k");

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(1, result.filesImported());
        assertEquals(1, result.failures().size());
        assertEquals(FileServiceException.Reason.DUPLICATE_NAME, result.failures().get(0).reason());
        assertFalse(result.stopped());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFolderThatClashesInsideTheTreeIsSkippedWithEverythingBelowIt() throws Exception {
        write("key/first.txt", "1");
        write("Key/second.txt", "22");
        write("Key/deeper/third.txt", "333");
        write("z.txt", "4444");
        List<long[]> updates = new ArrayList<>();

        FileService.FolderImport result = files.importFolder(source, root, (done, total, bytes, totalBytes) ->
                updates.add(new long[]{done, total, bytes, totalBytes}));

        assertEquals(2, result.foldersCreated());
        assertEquals(2, result.filesImported());
        assertFalse(result.stopped());
        assertEquals(List.of(new FileService.ImportFailure(
                Path.of("Photos", "Key").toString(), FileServiceException.Reason.DUPLICATE_NAME)),
                result.failures());
        assertEquals(Map.of("Photos", "FOLDER", "Photos/key", "FOLDER",
                "Photos/key/first.txt", "FILE", "Photos/z.txt", "FILE"), tree());
        assertEquals(2, vault.blobFiles().size());
        long[] last = updates.get(updates.size() - 1);
        assertEquals(List.of(4L, 4L, 10L, 10L), List.of(last[0], last[1], last[2], last[3]));
    }

    @Test
    void progressReachesEveryFileAndByte() throws Exception {
        write("a.txt", "12345");
        write("sub/b.txt", "123");
        List<long[]> updates = new ArrayList<>();

        files.importFolder(source, root, (done, total, bytes, totalBytes) ->
                updates.add(new long[]{done, total, bytes, totalBytes}));

        long[] last = updates.get(updates.size() - 1);
        assertEquals(List.of(2L, 2L, 8L, 8L), List.of(last[0], last[1], last[2], last[3]));
    }

    @Test
    void aVaultWriteFailureStopsTheImportAndKeepsWhatWasCommitted() throws Exception {
        write("a.txt", "a");
        write("b.txt", "b");
        write("c.txt", "c");
        ManifestRepository failsFourthSave = new ManifestRepository(vault.vault) {
            private int saves;

            @Override
            public void save(UserManifest manifest, UUID manifestId, byte[] key) throws VaultStorageException {
                if (++saves == 4) {
                    throw new VaultStorageException(VaultStorageException.Reason.IO);
                }

                super.save(manifest, manifestId, key);
            }
        };

        FileService.FolderImport result = vault.files(alice, failsFourthSave)
                .importFolder(source, root, (a, b, c, d) -> { });

        assertTrue(result.stopped());
        assertEquals(2, result.filesImported());
        assertEquals(FileServiceException.Reason.STORAGE, result.failures().get(0).reason());
        assertEquals(Map.of("Photos", "FOLDER", "Photos/a.txt", "FILE", "Photos/b.txt", "FILE"), tree());
        assertEquals(2, vault.blobFiles().size());
    }

    // ------------------------------------------------------------ helpers

    private Path write(String relative, String content) throws IOException {
        Path file = source.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, UTF_8);
    }

    private static void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
    }

    /** Logical paths below the root mapped to their kind. */
    private Map<String, String> tree() throws Exception {
        return tree(files);
    }

    private Map<String, String> tree(FileService service) throws Exception {
        Map<String, String> result = new TreeMap<>();
        collect(service, root, "", result);
        return result;
    }

    private static void collect(FileService service, UUID folderId, String prefix, Map<String, String> result)
            throws Exception {
        for (ManifestEntry entry : service.listChildren(folderId)) {
            String path = prefix + entry.getName();
            result.put(path, entry.getKind().name());

            if (entry.getKind().name().equals("FOLDER")) {
                collect(service, entry.getEntryId(), path + "/", result);
            }
        }
    }

    private ManifestEntry child(UUID folderId, String name) throws Exception {
        return files.listChildren(folderId).stream()
                .filter(entry -> entry.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> result = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.toList()) {
                result.put(dir.relativize(path).toString(),
                        Files.size(path) + "@" + Files.getLastModifiedTime(path));
            }
        }

        return result;
    }

    private static List<String> names(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getName).toList();
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }
}
