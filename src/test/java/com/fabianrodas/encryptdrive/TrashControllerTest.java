package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.services.FileServiceException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrashControllerTest {

    private final ManifestEntry deleted = trashed("deleted.txt");
    private final ManifestEntry other = trashed("other.txt");

    @Test
    void aStorageFailureAfterTheEntriesLeftTheTrashLeavesOnlyCleanup() {
        FileServiceException storage = new FileServiceException(FileServiceException.Reason.STORAGE);

        assertTrue(TrashController.onlyCleanupLeft(storage, List.of(deleted.getEntryId()), List.of(other)));
        assertTrue(TrashController.onlyCleanupLeft(storage, List.of(deleted.getEntryId()), List.of()));
    }

    @Test
    void aStorageFailureThatLeftTheItemsListedChangedNothing() {
        FileServiceException storage = new FileServiceException(FileServiceException.Reason.STORAGE);

        assertFalse(TrashController.onlyCleanupLeft(storage, List.of(deleted.getEntryId()), List.of(deleted, other)));
        assertFalse(TrashController.onlyCleanupLeft(
                storage, List.of(deleted.getEntryId(), other.getEntryId()), List.of(other)));
    }

    @Test
    void otherFailuresAreReportedAsTheyAre() {
        assertFalse(TrashController.onlyCleanupLeft(
                new FileServiceException(FileServiceException.Reason.NOT_FOUND), List.of(deleted.getEntryId()), List.of()));
        assertFalse(TrashController.onlyCleanupLeft(
                new IllegalStateException("not a vault failure"), List.of(deleted.getEntryId()), List.of()));
    }

    private static ManifestEntry trashed(String name) {
        return new ManifestEntry(UUID.randomUUID(), ManifestEntryKind.FILE, UUID.randomUUID(), name, "2026-10-01T00:00:00Z");
    }
}
