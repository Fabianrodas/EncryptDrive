package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.ManifestEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Moving a whole selection to the trash, as the Files view does after a search. */
class TrashTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account alice;
    private CountingManifests manifests;
    private FileService files;
    private UUID root;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.register("alice");
        manifests = new CountingManifests(vault.vault);
        files = vault.files(alice, manifests);
        root = files.rootFolderId();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    /** A folder, a file inside it and an unrelated file, selected in every possible order. */
    @ParameterizedTest
    @CsvSource({"0,1,2", "0,2,1", "1,0,2", "1,2,0", "2,0,1", "2,1,0"})
    void aFolderAndTheFileInsideItAreTrashedTogetherInAnyOrder(int first, int second, int third) throws Exception {
        ManifestEntry docs = files.createFolder("Docs", root);
        ManifestEntry inner = vault.importText(files, "inner.txt", docs.getEntryId());
        ManifestEntry loose = vault.importText(files, "loose.txt", root);
        List<UUID> selection = List.of(docs.getEntryId(), inner.getEntryId(), loose.getEntryId());

        files.moveToTrash(List.of(selection.get(first), selection.get(second), selection.get(third)));

        assertEquals(List.of(), files.listChildren(root));
        assertEquals(List.of(), files.search("txt"));
        // Only the top-most selected entries are trash roots; the inner file went with its folder.
        assertEquals(Set.of("Docs", "loose.txt"), names(files.listTrash()));

        files.restore(docs.getEntryId());

        assertEquals(Set.of("Docs"), names(files.listChildren(root)));
        assertEquals(Set.of("inner.txt"), names(files.listChildren(docs.getEntryId())));
        assertEquals(Set.of("loose.txt"), names(files.listTrash()));
    }

    @Test
    void oneInvalidEntryChangesNothing() throws Exception {
        ManifestEntry keep = vault.importText(files, "keep.txt", root);
        ManifestEntry gone = vault.importText(files, "gone.txt", root);
        files.moveToTrash(gone.getEntryId());
        byte[] before = Files.readAllBytes(vault.manifestFile(alice));

        assertFails(FileServiceException.Reason.PROTECTED, () -> files.moveToTrash(List.of(keep.getEntryId(), root)));
        assertFails(FileServiceException.Reason.NOT_FOUND,
                () -> files.moveToTrash(List.of(keep.getEntryId(), UUID.randomUUID())));
        assertFails(FileServiceException.Reason.NOT_FOUND,
                () -> files.moveToTrash(List.of(gone.getEntryId(), keep.getEntryId())));

        assertArrayEquals(before, Files.readAllBytes(vault.manifestFile(alice)), "the manifest was saved");
        assertEquals(Set.of("keep.txt"), names(files.listChildren(root)));
    }

    @Test
    void theWholeSelectionIsOneManifestSave() throws Exception {
        ManifestEntry a = vault.importText(files, "a.txt", root);
        ManifestEntry b = files.createFolder("B", root);
        ManifestEntry c = vault.importText(files, "c.txt", b.getEntryId());
        manifests.saves.set(0);

        files.moveToTrash(List.of(a.getEntryId(), b.getEntryId(), c.getEntryId()));

        assertEquals(1, manifests.saves.get());
    }

    private static void assertFails(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }

    private static Set<String> names(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getName).collect(Collectors.toSet());
    }
}
