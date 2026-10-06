package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.ManifestEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class MoveTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account alice;
    private FileService files;
    private UUID root;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.register("alice");
        files = vault.files(alice);
        root = files.rootFolderId();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void movesAFileWithoutTouchingItsBlob() throws Exception {
        ManifestEntry target = files.createFolder("Target", root);
        ManifestEntry file = vault.importText(files, "a.txt", root);
        byte[] blob = Files.readAllBytes(vault.blob(file));

        files.move(List.of(file.getEntryId()), target.getEntryId());

        assertEquals(List.of("Target"), names(files.listChildren(root)));
        assertEquals(List.of("a.txt"), names(files.listChildren(target.getEntryId())));
        assertArrayEquals(blob, Files.readAllBytes(vault.blob(file)));
        Path out = tempDir.resolve("a-out.txt");
        files.exportEntry(file.getEntryId(), out);
        assertEquals("a.txt", Files.readString(out));
    }

    @Test
    void movesAFolderWithItsWholeSubtree() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", root);
        ManifestEntry year = files.createFolder("2025", docs.getEntryId());
        vault.importText(files, "a.txt", year.getEntryId());
        ManifestEntry archive = files.createFolder("Archive", root);

        files.move(List.of(docs.getEntryId()), archive.getEntryId());

        assertEquals(List.of("Docs"), names(files.listChildren(archive.getEntryId())));
        assertEquals(List.of("a.txt"), names(files.listChildren(year.getEntryId())));
        vault.manifest(alice);   // still a valid tree (load validates it)
    }

    @Test
    void movesSeveralEntriesAtOnce() throws Exception {
        ManifestEntry target = files.createFolder("Target", root);
        ManifestEntry a = vault.importText(files, "a.txt", root);
        ManifestEntry b = files.createFolder("B", root);

        files.move(List.of(a.getEntryId(), b.getEntryId()), target.getEntryId());

        assertEquals(List.of("B", "a.txt"), names(files.listChildren(target.getEntryId())));
    }

    @Test
    void aFolderCannotMoveIntoItselfOrBelowItself() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", root);
        ManifestEntry inner = files.createFolder("Inner", docs.getEntryId());

        assertReason(FileServiceException.Reason.INVALID_MOVE, () -> files.move(List.of(docs.getEntryId()), docs.getEntryId()));
        assertReason(FileServiceException.Reason.INVALID_MOVE, () -> files.move(List.of(docs.getEntryId()), inner.getEntryId()));

        assertEquals(List.of("Docs"), names(files.listChildren(root)));
    }

    @Test
    void aCycleLaterInTheSelectionMovesNothing() throws Exception {
        ManifestEntry file = vault.importText(files, "a.txt", root);
        ManifestEntry docs = files.createFolder("Docs", root);
        ManifestEntry inner = files.createFolder("Inner", docs.getEntryId());

        assertReason(FileServiceException.Reason.INVALID_MOVE,
                () -> files.move(List.of(file.getEntryId(), docs.getEntryId()), inner.getEntryId()));

        assertEquals(List.of("Docs", "a.txt"), names(files.listChildren(root)));
        assertEquals(List.of("Inner"), names(files.listChildren(docs.getEntryId())));
        assertEquals(List.of(), files.listChildren(inner.getEntryId()));
    }

    @Test
    void aSelectionHoldingItsOwnDestinationMovesNothing() throws Exception {
        ManifestEntry a = files.createFolder("A", root);
        ManifestEntry b = files.createFolder("B", root);

        assertReason(FileServiceException.Reason.INVALID_MOVE,
                () -> files.move(List.of(a.getEntryId(), b.getEntryId()), a.getEntryId()));

        assertEquals(List.of("A", "B"), names(files.listChildren(root)));
        assertEquals(List.of(), files.listChildren(a.getEntryId()));
    }

    @Test
    void theRootCannotBeMoved() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", root);

        assertReason(FileServiceException.Reason.PROTECTED, () -> files.move(List.of(root), docs.getEntryId()));
    }

    @Test
    void aNameConflictInTheDestinationMovesNothing() throws Exception {
        ManifestEntry target = files.createFolder("Target", root);
        vault.importText(files, "CLASH.TXT", target.getEntryId());
        ManifestEntry ok = vault.importText(files, "ok.txt", root);
        ManifestEntry clash = vault.importText(files, "clash.txt", root);

        assertReason(FileServiceException.Reason.DUPLICATE_NAME,
                () -> files.move(List.of(ok.getEntryId(), clash.getEntryId()), target.getEntryId()));

        assertEquals(List.of("Target", "clash.txt", "ok.txt"), names(files.listChildren(root)));
    }

    @Test
    void twoMovedEntriesWithTheSameNameMoveNothing() throws Exception {
        ManifestEntry a = files.createFolder("A", root);
        ManifestEntry b = files.createFolder("B", root);
        ManifestEntry target = files.createFolder("Target", root);
        ManifestEntry x1 = vault.importText(files, "x.txt", a.getEntryId());
        ManifestEntry x2 = vault.importText(files, "X.txt", b.getEntryId());

        assertReason(FileServiceException.Reason.DUPLICATE_NAME,
                () -> files.move(List.of(x1.getEntryId(), x2.getEntryId()), target.getEntryId()));

        assertEquals(List.of(), files.listChildren(target.getEntryId()));
    }

    @Test
    void theDestinationMustBeAnActiveFolder() throws Exception {
        ManifestEntry file = vault.importText(files, "a.txt", root);
        ManifestEntry other = vault.importText(files, "b.txt", root);
        ManifestEntry trashed = files.createFolder("Old", root);
        files.moveToTrash(trashed.getEntryId());

        assertReason(FileServiceException.Reason.NOT_A_FOLDER, () -> files.move(List.of(file.getEntryId()), other.getEntryId()));
        assertReason(FileServiceException.Reason.NOT_FOUND, () -> files.move(List.of(file.getEntryId()), trashed.getEntryId()));
        assertReason(FileServiceException.Reason.NOT_FOUND, () -> files.move(List.of(file.getEntryId()), UUID.randomUUID()));
    }

    @Test
    void trashedEntriesCannotBeMoved() throws Exception {
        ManifestEntry target = files.createFolder("Target", root);
        ManifestEntry file = vault.importText(files, "a.txt", root);
        files.moveToTrash(file.getEntryId());

        assertReason(FileServiceException.Reason.NOT_FOUND, () -> files.move(List.of(file.getEntryId()), target.getEntryId()));
    }

    @Test
    void movingIntoTheCurrentFolderChangesNothing() throws Exception {
        ManifestEntry file = vault.importText(files, "a.txt", root);

        files.move(List.of(file.getEntryId()), root);

        assertEquals(List.of("a.txt"), names(files.listChildren(root)));
    }

    private static List<String> names(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getName).toList();
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }
}
