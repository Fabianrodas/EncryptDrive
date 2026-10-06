package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.repositories.ManifestRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account aliceAccount;
    private FileService alice;
    private UUID root;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        aliceAccount = vault.register("alice");
        alice = vault.files(aliceAccount);
        root = alice.rootFolderId();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void searchIgnoresCase() throws Exception {
        vault.importText(alice, "Budget-2025.xlsx", root);

        assertEquals(List.of("Budget-2025.xlsx"), names(alice.search("budget")));
        assertEquals(List.of("Budget-2025.xlsx"), names(alice.search("GET-20")));
    }

    @Test
    void searchFindsEntriesInEveryFolderWithTheirLocation() throws Exception {
        ManifestEntry docs = alice.createFolder("Docs", root);
        ManifestEntry year = alice.createFolder("2025 report folder", docs.getEntryId());
        vault.importText(alice, "report.pdf", year.getEntryId());
        vault.importText(alice, "report.txt", root);

        List<FileService.SearchResult> results = alice.search("report");

        assertEquals(Map.of(
                "2025 report folder", List.of("Docs"),
                "report.pdf", List.of("Docs", "2025 report folder"),
                "report.txt", List.of()
        ), byName(results));
    }

    @Test
    void sameNameInDifferentFoldersIsToldApartByLocation() throws Exception {
        ManifestEntry work = alice.createFolder("Work", root);
        ManifestEntry home = alice.createFolder("Home", root);
        vault.importText(alice, "plan.txt", work.getEntryId());
        vault.importText(alice, "plan.txt", home.getEntryId());

        List<FileService.SearchResult> results = alice.search("plan");

        assertEquals(List.of("plan.txt", "plan.txt"), names(results));
        assertEquals(
                Set.of(List.of("Home"), List.of("Work")),
                results.stream().map(FileService.SearchResult::folders).collect(Collectors.toSet())
        );
    }

    @Test
    void searchLeavesOutTheTrashAndTrashedFolders() throws Exception {
        ManifestEntry old = alice.createFolder("Old", root);
        vault.importText(alice, "note-inside.txt", old.getEntryId());
        ManifestEntry loose = vault.importText(alice, "note-loose.txt", root);
        alice.moveToTrash(old.getEntryId());
        alice.moveToTrash(loose.getEntryId());
        vault.importText(alice, "note-kept.txt", root);

        assertEquals(List.of("note-kept.txt"), names(alice.search("note")));
        assertEquals(List.of(), alice.search("old"));
    }

    /** No writer produces this today; the search must not depend on that. */
    @Test
    void searchLeavesOutEntriesBelowATrashedFolderEvenIfTheyLookActive() throws Exception {
        ManifestEntry old = alice.createFolder("Old", root);
        ManifestEntry deeper = alice.createFolder("Deeper", old.getEntryId());
        vault.importText(alice, "note-inside.txt", deeper.getEntryId());
        vault.importText(alice, "note-kept.txt", root);

        UserManifest manifest = vault.manifest(aliceAccount);
        manifest.getEntries().stream()
                .filter(entry -> entry.getEntryId().equals(old.getEntryId()))
                .forEach(entry -> entry.setDeletedAt(Instant.now().toString()));
        new ManifestRepository(vault.vault).save(manifest, aliceAccount.identity().manifestId(),
                aliceAccount.userMasterKey());

        assertEquals(List.of("note-kept.txt"), names(alice.search("note")));
        assertEquals(List.of(), alice.search("deeper"));
    }

    @Test
    void searchNeverSeesAnotherAccount() throws Exception {
        vault.importText(alice, "alice-secret.txt", root);
        FileService bob = vault.files(vault.register("bob"));

        assertEquals(List.of(), bob.search("secret"));
        assertEquals(List.of(), bob.search("alice"));

        vault.importText(bob, "bob-secret.txt", bob.rootFolderId());

        assertEquals(List.of("bob-secret.txt"), names(bob.search("secret")));
        assertEquals(List.of("alice-secret.txt"), names(alice.search("secret")));
    }

    @Test
    void blankQueriesAndTheRootFindNothing() throws Exception {
        vault.importText(alice, "a.txt", root);

        assertEquals(List.of(), alice.search(""));
        assertEquals(List.of(), alice.search("   "));
        assertEquals(List.of(), alice.search("/"));
    }

    @Test
    void searchWritesNothing() throws Exception {
        vault.importText(alice, "a.txt", root);
        Map<String, String> before = snapshot(vault.vault.root());

        alice.search("a");

        assertEquals(before, snapshot(vault.vault.root()));
    }

    private static List<String> names(List<FileService.SearchResult> results) {
        return results.stream().map(result -> result.entry().getName()).toList();
    }

    private static Map<String, List<String>> byName(List<FileService.SearchResult> results) {
        Map<String, List<String>> map = new TreeMap<>();
        results.forEach(result -> map.put(result.entry().getName(), result.folders()));
        return map;
    }

    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> result = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(dir.relativize(path).toString(), Files.size(path) + "@" + Files.getLastModifiedTime(path));
            }
        }

        return result;
    }
}
