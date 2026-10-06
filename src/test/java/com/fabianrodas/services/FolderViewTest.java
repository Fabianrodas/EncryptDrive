package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fabianrodas.models.ManifestEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderViewTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account alice;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.register("alice");
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void pathAndSortedChildrenComeFromOneManifestRead() throws Exception {
        CountingManifests counting = new CountingManifests(vault.vault);
        FileService files = vault.files(alice, counting);
        UUID root = files.rootFolderId();
        ManifestEntry docs = files.createFolder("Docs", root);
        vault.importText(files, "b.txt", docs.getEntryId());
        files.createFolder("a-folder", docs.getEntryId());
        files.createFolder("Z-folder", docs.getEntryId());
        counting.loads.set(0);

        FileService.FolderView view = files.folderView(docs.getEntryId());

        assertEquals(1, counting.loads.get());
        assertEquals(docs.getEntryId(), view.folderId());
        assertEquals(List.of(root, docs.getEntryId()), ids(view.path()));
        assertEquals(List.of("a-folder", "Z-folder", "b.txt"), names(view.children()));
    }

    @Test
    void missingOrTrashedFoldersShowTheRoot() throws Exception {
        FileService files = vault.files(alice);
        UUID root = files.rootFolderId();
        ManifestEntry docs = files.createFolder("Docs", root);
        files.moveToTrash(docs.getEntryId());

        assertEquals(root, files.folderView(docs.getEntryId()).folderId());
        assertEquals(root, files.folderView(UUID.randomUUID()).folderId());
        assertEquals(root, files.folderView(null).folderId());
        assertEquals(List.of(root), ids(files.folderView(null).path()));
    }

    private static List<UUID> ids(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getEntryId).toList();
    }

    private static List<String> names(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getName).toList();
    }
}
