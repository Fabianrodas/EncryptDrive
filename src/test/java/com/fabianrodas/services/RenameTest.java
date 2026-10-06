package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.ManifestEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class RenameTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private FileService files;
    private UUID root;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        files = vault.files(vault.register("alice"));
        root = files.rootFolderId();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void renamingAFileLeavesItsBlobUntouched() throws Exception {
        byte[] content = new byte[100_000];
        new SecureRandom().nextBytes(content);
        ManifestEntry file = files.importFile(vault.source("report.pdf", content), root);
        Path blob = vault.blob(file);
        byte[] blobBefore = Files.readAllBytes(blob);
        FileTime modifiedBefore = Files.getLastModifiedTime(blob);

        files.rename(file.getEntryId(), "summary");

        assertEquals(List.of("summary"), names(files.listChildren(root)));
        assertArrayEquals(blobBefore, Files.readAllBytes(blob));
        assertEquals(modifiedBefore, Files.getLastModifiedTime(blob));
        Path out = tempDir.resolve("out.bin");
        files.exportEntry(file.getEntryId(), out);
        assertArrayEquals(content, Files.readAllBytes(out));
    }

    @Test
    void renamingAFolderKeepsItsChildren() throws Exception {
        ManifestEntry folder = files.createFolder("Docs", root);
        vault.importText(files, "a.txt", folder.getEntryId());

        files.rename(folder.getEntryId(), "Papers");

        assertEquals(List.of("Papers"), names(files.listChildren(root)));
        assertEquals(List.of("a.txt"), names(files.listChildren(folder.getEntryId())));
    }

    @Test
    void namesAreStrippedAndValidated() throws Exception {
        ManifestEntry file = vault.importText(files, "notes.txt", root);

        files.rename(file.getEntryId(), "  todo.txt  ");
        assertEquals(List.of("todo.txt"), names(files.listChildren(root)));

        for (String invalid : new String[]{"", "   ", ".", "..", "a\0b", "x".repeat(256)}) {
            assertReason(FileServiceException.Reason.INVALID_NAME, () -> files.rename(file.getEntryId(), invalid));
        }
    }

    @Test
    void renamingOntoASiblingsExactNameIsRefused() throws Exception {
        ManifestEntry a = vault.importText(files, "a.txt", root);
        vault.importText(files, "b.txt", root);

        assertReason(FileServiceException.Reason.DUPLICATE_NAME, () -> files.rename(a.getEntryId(), "b.txt"));

        assertEquals(List.of("a.txt", "b.txt"), names(files.listChildren(root)));
    }

    @Test
    void siblingNamesStayUniqueIgnoringCase() throws Exception {
        ManifestEntry a = vault.importText(files, "a.txt", root);
        ManifestEntry b = vault.importText(files, "b.txt", root);

        assertReason(FileServiceException.Reason.DUPLICATE_NAME, () -> files.rename(b.getEntryId(), "A.TXT"));
        files.rename(a.getEntryId(), "A.txt");

        assertEquals(List.of("A.txt", "b.txt"), names(files.listChildren(root)));
    }

    @Test
    void theSameNameInAnotherFolderIsAllowed() throws Exception {
        ManifestEntry folder = files.createFolder("Docs", root);
        vault.importText(files, "a.txt", root);
        ManifestEntry inner = vault.importText(files, "b.txt", folder.getEntryId());

        files.rename(inner.getEntryId(), "a.txt");

        assertEquals(List.of("a.txt"), names(files.listChildren(folder.getEntryId())));
    }

    @Test
    void theRootAndTrashedEntriesCannotBeRenamed() throws Exception {
        ManifestEntry file = vault.importText(files, "a.txt", root);
        files.moveToTrash(file.getEntryId());

        assertReason(FileServiceException.Reason.PROTECTED, () -> files.rename(root, "Mine"));
        assertReason(FileServiceException.Reason.NOT_FOUND, () -> files.rename(file.getEntryId(), "b.txt"));
    }

    private static List<String> names(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getName).toList();
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }
}
