package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.UserManifest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ManifestServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UserManifest manifest = ManifestService.newManifest(userId);
    private final ManifestService service = new ManifestService(manifest);

    @Test
    void newManifestHasASingleProtectedRootFolder() {
        ManifestEntry root = service.find(service.rootFolderId());

        assertEquals(1, manifest.getFormatVersion());
        assertEquals(userId, manifest.getUserId());
        assertEquals(1, manifest.getEntries().size());
        assertEquals(ManifestEntryKind.FOLDER, root.getKind());
        assertNull(root.getParentId());
        assertEquals("/", root.getName());
    }

    @Test
    void createFolderAddsAChildOfTheParent() throws Exception {
        ManifestEntry photos = service.createFolder(service.rootFolderId(), "Photos");
        ManifestEntry trip = service.createFolder(photos.getEntryId(), "Trip");

        assertEquals(List.of(photos), service.listChildren(service.rootFolderId(), false));
        assertEquals(List.of(trip), service.listChildren(photos.getEntryId(), false));
        assertEquals(ManifestEntryKind.FOLDER, trip.getKind());
        assertNotNull(trip.getCreatedAt());
        assertEquals(trip.getCreatedAt(), trip.getModifiedAt());
        assertNull(trip.getBlobId());
        assertNull(trip.getPlainSize());
    }

    @Test
    void invalidFolderNamesAreRejected() {
        String longest = "a".repeat(254) + "\uD83D\uDE00";
        String tooLong = "a".repeat(255) + "\uD83D\uDE00";

        for (String name : new String[]{"", "   ", ".", "..", "a\u0000b", tooLong, null}) {
            assertReason(
                    FileServiceException.Reason.INVALID_NAME,
                    () -> service.createFolder(service.rootFolderId(), name)
            );
        }

        assertEquals(255, longest.codePointCount(0, longest.length()));
        assertNotNull(assertCreated(longest));
    }

    @Test
    void namesMayContainPathSeparatorsBecauseTheyAreMetadata() throws Exception {
        assertNotNull(assertCreated("2025/2026 budget"));
        assertNotNull(assertCreated("C:\\legacy"));
        assertEquals("a:b", assertCreated("a:b").getName());
    }

    @Test
    void logicalNamesKeepTheReferenceWhitespaceAndUnicodeRules() throws Exception {
        assertEquals("trimmed name", assertCreated("  trimmed name  ").getName());
        assertEquals("trailing.", assertCreated("trailing.").getName());
        assertEquals("CON", assertCreated("CON").getName());

        String composed = "caf\u00e9";
        String decomposed = "cafe\u0301";
        assertEquals(composed, assertCreated(composed).getName());
        assertEquals(decomposed, assertCreated(decomposed).getName());
        assertEquals("ß", assertCreated("ß").getName());
        assertEquals("SS", assertCreated("SS").getName());

        String noBreakSpaces = "\u00a0name\u00a0";
        assertEquals(noBreakSpaces, assertCreated(noBreakSpaces).getName());
    }

    @Test
    void nulIsRejectedButOtherEmbeddedControlCharactersAreKept() throws Exception {
        String embeddedControl = "left\u0001right";
        assertEquals(embeddedControl, assertCreated(embeddedControl).getName());
        assertEquals("tab\tinside", assertCreated("tab\tinside").getName());
        assertEquals("trimmed", assertCreated("\ttrimmed\t").getName());

        assertReason(
                FileServiceException.Reason.INVALID_NAME,
                () -> service.createFolder(service.rootFolderId(), "left\u0000right")
        );
    }

    @Test
    void siblingNamesAreUniqueCaseInsensitively() throws Exception {
        ManifestEntry photos = service.createFolder(service.rootFolderId(), "Photos");

        assertReason(
                FileServiceException.Reason.DUPLICATE_NAME,
                () -> service.createFolder(service.rootFolderId(), " PHOTOS ")
        );
        assertNotNull(service.createFolder(photos.getEntryId(), "photos"));
    }

    @Test
    void deletedSiblingNamesCanBeReused() throws Exception {
        ManifestEntry deleted = service.createFolder(service.rootFolderId(), "Archived");
        deleted.setDeletedAt("2026-10-05T12:00:00Z");

        assertNotNull(service.createFolder(service.rootFolderId(), "archived"));
    }

    @Test
    void foldersNeedAnExistingFolderParent() throws Exception {
        assertReason(
                FileServiceException.Reason.NOT_FOUND,
                () -> service.createFolder(UUID.randomUUID(), "Orphan")
        );

        ManifestEntry file = new ManifestEntry(
                UUID.randomUUID(), ManifestEntryKind.FILE, service.rootFolderId(),
                "notes.txt", "2026-09-30T00:00:00Z"
        );
        manifest.getEntries().add(file);

        assertReason(
                FileServiceException.Reason.NOT_A_FOLDER,
                () -> service.createFolder(file.getEntryId(), "Inside a file")
        );
    }

    @Test
    void deletedEntriesAreListedOnlyWhenRequested() throws Exception {
        ManifestEntry kept = service.createFolder(service.rootFolderId(), "Kept");
        ManifestEntry trashed = service.createFolder(service.rootFolderId(), "Trashed");
        trashed.setDeletedAt("2026-09-30T12:00:00Z");

        assertEquals(List.of(kept), service.listChildren(service.rootFolderId(), false));
        assertTrue(service.listChildren(service.rootFolderId(), true).contains(trashed));
    }

    private ManifestEntry assertCreated(String name) {
        try {
            return service.createFolder(service.rootFolderId(), name);
        } catch (FileServiceException e) {
            throw new AssertionError(name + " was rejected: " + e.getReason());
        }
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        FileServiceException error = assertThrows(FileServiceException.class, action);
        assertEquals(reason, error.getReason());
    }
}
