package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.repositories.BlobRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/* Spec 14.1: metadata is not re-read more than required within one operation. */
class MetadataLoadCountTest {

    @TempDir
    Path tempDir;

    private final AtomicBoolean blobDeletesFail = new AtomicBoolean();
    private TestVault vault;
    private CountingManifests counting;
    private FileService files;
    private UUID root;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        counting = new CountingManifests(vault.vault);
        BlobRepository blobs = new BlobRepository() {
            @Override
            public void delete(Path vaultRoot, UUID blobId) throws IOException {
                if (blobDeletesFail.get()) {
                    throw new IOException("blob delete refused");
                }

                super.delete(vaultRoot, blobId);
            }
        };
        files = vault.files(vault.register("alice"), counting, blobs);
        root = files.rootFolderId();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void everyOperationDecryptsTheManifestOnce() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", root);
        ManifestEntry file = vault.importText(files, "a.txt", root);
        ManifestEntry toRestore = trashed("t.txt");
        ManifestEntry toDelete = trashed("d.txt");
        trashed("e.txt"); // left for emptyTrash
        ManifestEntry toTrash = vault.importText(files, "x.txt", root);

        Map<String, Executable> operations = new LinkedHashMap<>();
        operations.put("folderView", () -> files.folderView(docs.getEntryId()));
        operations.put("listChildren", () -> files.listChildren(root));
        operations.put("search", () -> files.search("a"));
        operations.put("stats", () -> files.stats());
        operations.put("listTrash", () -> files.listTrash());
        operations.put("activeFolders", () -> files.activeFolders());
        operations.put("createFolder", () -> files.createFolder("New", root));
        operations.put("rename", () -> files.rename(file.getEntryId(), "b.txt"));
        operations.put("move", () -> files.move(List.of(file.getEntryId()), docs.getEntryId()));
        operations.put("moveToTrash", () -> files.moveToTrash(toTrash.getEntryId()));
        operations.put("restore", () -> files.restore(toRestore.getEntryId()));
        // The blob stays queued, so the next operation has a non-empty queue to work through.
        operations.put("permanentlyDelete", () -> {
            blobDeletesFail.set(true);
            assertEquals(1, files.permanentlyDelete(List.of(toDelete.getEntryId())));
        });
        operations.put("resumePendingDeletions", () -> {
            blobDeletesFail.set(false);
            assertEquals(0, files.resumePendingDeletions());
        });
        operations.put("emptyTrash", () -> files.emptyTrash());
        operations.put("exportEntry", () -> files.exportEntry(file.getEntryId(), tempDir.resolve("out.txt")));
        operations.put("exportTargets", () -> files.exportTargets(List.of(file.getEntryId()), tempDir));

        // Collected, not thrown at the first miss, so one failure shows every operation's count.
        Map<String, Integer> notOnce = new LinkedHashMap<>();

        for (Map.Entry<String, Executable> operation : operations.entrySet()) {
            counting.loads.set(0);

            try {
                operation.getValue().execute();
            } catch (Throwable e) {
                throw new AssertionError(operation.getKey(), e);
            }

            if (counting.loads.get() != 1) {
                notOnce.put(operation.getKey(), counting.loads.get());
            }
        }

        assertEquals(Map.of(), notOnce, "operations that did not decrypt the manifest exactly once");
    }

    @Test
    void folderImportReadsAFixedNumberOfTimesPerItem() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("in").resolve("Tree"));
        Files.createDirectories(source.resolve("sub"));
        Files.writeString(source.resolve("a.txt"), "a");
        Files.writeString(source.resolve("b.txt"), "b");
        Files.writeString(source.resolve("sub").resolve("c.txt"), "c");
        counting.loads.set(0);

        files.importFolder(source, root, (a, b, c, d) -> { });

        // 1 preflight + 1 per folder (2) + 2 per file (3): the per-file transaction model of spec 14.2.
        assertEquals(1 + 2 + 2 * 3, counting.loads.get());
    }

    private ManifestEntry trashed(String name) throws Exception {
        ManifestEntry entry = vault.importText(files, name, root);
        files.moveToTrash(entry.getEntryId());
        return entry;
    }
}
