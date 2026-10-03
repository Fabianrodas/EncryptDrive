package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;

class FilesControllerTest {

    @Test
    void moveDestinationsLeaveOutTheMovedFoldersAndEverythingInThem() {
        ManifestEntry root = folder(null, "/");
        ManifestEntry a = folder(root, "A");
        ManifestEntry b = folder(a, "B");
        ManifestEntry c = folder(root, "C");

        TreeItem<ManifestEntry> tree = FilesController.folderTree(List.of(root, a, b, c), Set.of(a.getEntryId()));

        assertSame(root, tree.getValue());
        assertEquals(List.of(c), tree.getChildren().stream().map(TreeItem::getValue).toList());
    }

    @Test
    void aDriveRootIsShownByItsPathBecauseItHasNoName() {
        Path driveRoot = Path.of("x").toAbsolutePath().getRoot();

        assertEquals("Photos", FilesController.displayName(driveRoot.resolve("input").resolve("Photos")));
        assertEquals(driveRoot.toString(), FilesController.displayName(driveRoot));
    }

    private static ManifestEntry folder(ManifestEntry parent, String name) {
        return new ManifestEntry(UUID.randomUUID(), ManifestEntryKind.FOLDER,
                parent == null ? null : parent.getEntryId(), name, "2026-10-01T00:00:00Z");
    }
}
