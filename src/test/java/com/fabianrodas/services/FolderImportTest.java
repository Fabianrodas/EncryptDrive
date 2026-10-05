package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.BlobRepository;
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
    void folderImportSavesOncePer64LogicalMutations() throws Exception {
        CountingManifests counting = new CountingManifests(vault.vault);
        FileService counted = vault.files(alice, counting);

        for (int mutations : List.of(64, 65, 130)) {
            Path input = inputTree("Batch" + mutations, mutations - 1);
            counting.loads.set(0);
            counting.saves.set(0);

            FileService.FolderImport result = counted.importFolder(input, root, (a, b, c, d) -> { });

            assertEquals((mutations + 63) / 64, counting.saves.get(), mutations + " logical mutations");
            assertEquals(1, counting.loads.get(), mutations + " logical mutations");
            assertEquals(mutations, result.foldersCreated() + result.filesImported());
        }
    }

    @Test
    void manyEmptyFoldersAreAlsoCommittedInBoundedBatches() throws Exception {
        Path input = Files.createDirectories(tempDir.resolve("inputs").resolve("EmptyTree"));
        for (int i = 0; i < 129; i++) {
            Files.createDirectories(input.resolve(String.format(Locale.ROOT, "empty-%03d", i)));
        }
        CountingManifests counting = new CountingManifests(vault.vault);
        FileService counted = vault.files(alice, counting);

        FileService.FolderImport result = counted.importFolder(input, root, (a, b, c, d) -> { });

        assertEquals(130, result.foldersCreated());
        assertEquals(0, result.filesImported());
        assertEquals(3, counting.saves.get());
    }

    @Test
    void aFailedFirstBatchDoesNotExposeItsFoldersOrFiles() throws Exception {
        Path input = inputTree("FailedFirst", 63);
        ManifestRepository failsWhenFileEntriesAreSaved = new ManifestRepository(vault.vault) {
            @Override
            public void save(UserManifest manifest, UUID manifestId, byte[] key) throws VaultStorageException {
                if (manifest.getEntries().stream().anyMatch(entry -> entry.getBlobId() != null)) {
                    throw new VaultStorageException(VaultStorageException.Reason.IO);
                }
                super.save(manifest, manifestId, key);
            }
        };

        FileService.FolderImport result = vault.files(alice, failsWhenFileEntriesAreSaved)
                .importFolder(input, root, (a, b, c, d) -> { });

        assertTrue(result.stopped());
        assertEquals(0, result.foldersCreated());
        assertEquals(0, result.filesImported());
        assertEquals(FileServiceException.Reason.STORAGE, result.failures().get(0).reason());
        assertEquals(List.of(), files.listChildren(root));
        assertEquals(List.of(), vault.blobFiles());
    }

    @Test
    void aFailedLaterBatchKeepsOnlyEarlierCommittedEntriesVisible() throws Exception {
        Path input = inputTree("FailedLater", 65);
        ManifestRepository failsSecondSave = failOnSave(vault.vault, 2);

        FileService.FolderImport result = vault.files(alice, failsSecondSave)
                .importFolder(input, root, (a, b, c, d) -> { });
        FileService reopened = vault.files(alice);
        List<ManifestEntry> visible = reopened.listChildren(root);

        assertTrue(result.stopped());
        assertEquals(1, result.foldersCreated());
        assertEquals(63, result.filesImported());
        assertEquals(FileServiceException.Reason.STORAGE, result.failures().get(0).reason());
        List<String> firstFolderChildren = visible.isEmpty() ? List.of()
                : reopened.listChildren(visible.get(0).getEntryId()).stream()
                        .map(ManifestEntry::getName).toList();
        assertEquals(64, countEntries(reopened, visible), "top-level="
                + visible.stream().map(ManifestEntry::getName).toList()
                + ", first-folder-children=" + firstFolderChildren
                + ", blob-files=" + vault.blobFiles().size());
        assertEquals(63, vault.blobFiles().size());
        assertTrue(visible.stream().filter(entry -> entry.getBlobId() != null)
                .allMatch(entry -> Files.exists(vault.blob(entry))));
    }

    @Test
    void aCleanupFailureMayLeaveAnOrphanButNeverACommittedReference() throws Exception {
        Path input = inputTree("Orphan", 1);
        ManifestRepository failsWhenFileEntriesAreSaved = new ManifestRepository(vault.vault) {
            @Override
            public void save(UserManifest manifest, UUID manifestId, byte[] key) throws VaultStorageException {
                if (manifest.getEntries().stream().anyMatch(entry -> entry.getBlobId() != null)) {
                    throw new VaultStorageException(VaultStorageException.Reason.IO);
                }
                super.save(manifest, manifestId, key);
            }
        };
        BlobRepository cannotDelete = new BlobRepository() {
            @Override
            public void delete(Path vaultRoot, UUID blobId) throws IOException {
                throw new IOException("simulated cleanup failure");
            }
        };

        FileService.FolderImport result = vault.files(alice, failsWhenFileEntriesAreSaved, cannotDelete)
                .importFolder(input, root, (a, b, c, d) -> { });
        UserManifest persisted = vault.manifest(alice);

        assertTrue(result.stopped());
        assertEquals(1, vault.blobFiles().size());
        UUID orphan = UUID.fromString(vault.blobFiles().get(0).getFileName().toString().replace(".edv", ""));
        assertTrue(persisted.getEntries().stream().noneMatch(entry -> orphan.equals(entry.getBlobId())));
        assertEquals(List.of(), files.listChildren(root));
    }

    @Test
    void nestedAndEmptyFoldersRemainCorrectAcrossABatchBoundary() throws Exception {
        for (int i = 0; i < 63; i++) {
            Files.createDirectories(source.resolve(String.format(Locale.ROOT, "empty-%03d", i)));
        }
        write("z-nested/inner/kept.txt", "content");

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(66, result.foldersCreated());
        assertEquals(1, result.filesImported());
        ManifestEntry photos = child(root, "Photos");
        ManifestEntry nested = child(photos.getEntryId(), "z-nested");
        ManifestEntry inner = child(nested.getEntryId(), "inner");
        assertEquals("FILE", child(inner.getEntryId(), "kept.txt").getKind().name());
        assertEquals("FOLDER", child(photos.getEntryId(), "empty-062").getKind().name());
    }

    @Test
    void singleFileImportStillSavesImmediately() throws Exception {
        CountingManifests counting = new CountingManifests(vault.vault);
        FileService counted = vault.files(alice, counting);
        Path oneFile = Files.writeString(tempDir.resolve("one.txt"), "one");
        counting.saves.set(0);

        ManifestEntry imported = counted.importFile(oneFile, root);

        assertEquals(1, counting.saves.get());
        assertEquals(imported.getEntryId(), counted.listChildren(root).get(0).getEntryId());
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
    void theVaultReachedThroughAShareAliasIsStillRefused() throws Exception {
        // \\localhost\C$\... names the same folder as C:\..., and its real path keeps the share form.
        Path vaultRoot = vault.vault.root().toRealPath();
        String drive = vaultRoot.getRoot().toString();
        assumeTrue(drive.matches("[A-Za-z]:\\\\"), "the vault is not on a lettered drive");
        Path alias = Path.of("\\\\localhost\\" + drive.charAt(0) + "$")
                .resolve(vaultRoot.getRoot().relativize(vaultRoot));
        assumeTrue(Files.isDirectory(alias), "the administrative share of the drive is not reachable");

        for (Path overlapping : List.of(
                alias,
                alias.resolve("storage"),
                alias.resolve("storage").resolve("not-written-yet.txt"),
                alias.getParent())) {
            assertReason(FileServiceException.Reason.INSIDE_VAULT,
                    () -> files.importFolder(overlapping, root, (a, b, c, d) -> { }));
        }

        assertEquals(List.of(), files.listChildren(root));
        assertEquals(List.of(), vault.blobFiles());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aSourceWhoseRealLocationCannotBeResolvedIsRefused() throws Exception {
        // A junction whose target is gone exists, but has no real path to check against the vault.
        Path target = Files.createDirectories(tempDir.resolve("gone"));
        Path dangling = tempDir.resolve("dangling");
        junction(dangling, target);
        Files.delete(target);

        for (Path unresolvable : List.of(dangling, dangling.resolve("below"))) {
            assertReason(FileServiceException.Reason.STORAGE,
                    () -> files.importFolder(unresolvable, root, (a, b, c, d) -> { }));
        }

        assertEquals(List.of(), files.listChildren(root));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFolderSwappedForAJunctionAfterTheScanIsNotFollowed() throws Exception {
        write("a.txt", "a");
        Path sub = write("sub/b.txt", "inside the tree").getParent();
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("b.txt"), "outside the tree");
        boolean[] swapped = new boolean[1];

        FileService.FolderImport result = files.importFolder(source, root, (done, total, bytes, totalBytes) -> {
            // Once a.txt is in, and before anything of "sub" is read.
            if (done == 1 && !swapped[0]) {
                swapped[0] = true;

                try {
                    Files.delete(sub.resolve("b.txt"));
                    Files.delete(sub);
                    junction(sub, outside);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        assertTrue(swapped[0]);
        assertEquals(1, result.filesImported());
        assertFalse(result.stopped());
        assertEquals(List.of(new FileService.ImportFailure(
                Path.of("Photos", "sub", "b.txt").toString(), FileServiceException.Reason.SOURCE_UNREADABLE)),
                result.failures());
        assertEquals(Map.of("Photos", "FOLDER", "Photos/a.txt", "FILE", "Photos/sub", "FOLDER"), tree());
        assertEquals(1, vault.blobFiles().size());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void namesThatClashInsideTheTreeSkipOnlyTheClashingItem() throws Exception {
        // NTFS keeps "key" and "\u212Aey" (Kelvin sign) apart; EncryptDrive compares names ignoring case and treats them as equal.
        write("key.txt", "plain k");
        write("\u212Aey.txt", "kelvin k");

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
        write("\u212Aey/second.txt", "22");
        write("\u212Aey/deeper/third.txt", "333");
        write("z.txt", "4444");
        List<long[]> updates = new ArrayList<>();

        FileService.FolderImport result = files.importFolder(source, root, (done, total, bytes, totalBytes) ->
                updates.add(new long[]{done, total, bytes, totalBytes}));

        assertEquals(2, result.foldersCreated());
        assertEquals(2, result.filesImported());
        assertFalse(result.stopped());
        assertEquals(List.of(new FileService.ImportFailure(
                Path.of("Photos", "\u212Aey").toString(), FileServiceException.Reason.DUPLICATE_NAME)),
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

    // ------------------------------------------------------------ helpers

    private Path write(String relative, String content) throws IOException {
        Path file = source.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, UTF_8);
    }

    private Path inputTree(String name, int fileCount) throws IOException {
        Path input = Files.createDirectories(tempDir.resolve("inputs").resolve(name));
        for (int i = 0; i < fileCount; i++) {
            Files.write(input.resolve(String.format(Locale.ROOT, "file-%03d.bin", i)), new byte[]{(byte) i});
        }
        return input;
    }

    private static ManifestRepository failOnSave(VaultContext vault, int failureNumber) {
        return new ManifestRepository(vault) {
            private int saves;

            @Override
            public void save(UserManifest manifest, UUID manifestId, byte[] key) throws VaultStorageException {
                if (++saves == failureNumber) {
                    throw new VaultStorageException(VaultStorageException.Reason.IO);
                }
                super.save(manifest, manifestId, key);
            }
        };
    }

    private static int countEntries(FileService service, List<ManifestEntry> entries) throws Exception {
        int count = entries.size();
        for (ManifestEntry entry : entries) {
            if (entry.getKind() == com.fabianrodas.models.ManifestEntryKind.FOLDER) {
                count += countEntries(service, service.listChildren(entry.getEntryId()));
            }
        }
        return count;
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
