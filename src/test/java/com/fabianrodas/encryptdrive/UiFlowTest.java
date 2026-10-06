package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.PendingDeletion;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.BlobRepository;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.security.SensitiveBytes;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.ManifestLockProbe;
import com.fabianrodas.services.RecoveryService;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultService;
import com.fabianrodas.services.VaultSessionService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Logout and Close Vault must destroy different key material: logout keeps
 * the vault unlocked, closing the vault destroys everything.
 */
class UiFlowTest {

    @TempDir
    Path tempDir;

    private VaultContext vault;
    private SensitiveBytes userMasterKey;

    @BeforeAll
    static void startJavaFx() {
        assumeTrue(FxTestSupport.start(), "JavaFX needs a desktop session");
    }

    @BeforeEach
    void openVaultAndSignIn() throws Exception {
        vault = new VaultService().createVault(
                tempDir.resolve("vault"), "correct vault password".toCharArray()
        );
        AuthService auth = new AuthService(vault);
        auth.register("Example User", "ExampleUser", "example password".toCharArray());
        UserLoginResult login = auth.login("ExampleUser", "example password".toCharArray());
        userMasterKey = login.userMasterKey();
        SessionService.start(login.identity(), userMasterKey);
    }

    @AfterEach
    void closeVault() throws Exception {
        // A worker still running would hold files of the temporary directory open.
        FxTestSupport.settleBackground();
        VaultSessionService.closeVault();
    }

    @Test
    void logOutKeepsTheVaultUnlockedAndReturnsToLogin() throws Exception {
        Scene scene = FxTestSupport.showScreen("dashboard");

        click(scene, "#logoutButton");

        assertTrue(userMasterKey.isDestroyed());
        assertFalse(vault.isClosed());
        assertTrue(VaultSessionService.isOpen());
        assertNotNull(FxTestSupport.onFxThread(() -> scene.getRoot().lookup("#usernameField")));
    }

    @Test
    void closeVaultFromDashboardDestroysAllKeysAndReturnsToVaultSelection() throws Exception {
        Scene scene = FxTestSupport.showScreen("dashboard");

        click(scene, "#closeVaultButton");

        assertClosedAndBackAtVaultSelection(scene);
    }

    @Test
    void closeVaultFromLoginReturnsToVaultSelection() throws Exception {
        SessionService.logout();
        Scene scene = FxTestSupport.showScreen("login");

        click(scene, "#closeVaultButton");

        assertClosedAndBackAtVaultSelection(scene);
    }

    @Test
    void closeVaultFromRegisterReturnsToVaultSelection() throws Exception {
        SessionService.logout();
        Scene scene = FxTestSupport.showScreen("register");

        click(scene, "#closeVaultButton");

        assertClosedAndBackAtVaultSelection(scene);
    }

    @Test
    void permanentDeleteHappensOnlyAfterExplicitConfirmation() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry old = files.importFile(
                Files.writeString(tempDir.resolve("old.txt"), "old"), files.rootFolderId()
        );
        files.moveToTrash(old.getEntryId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#trashNavButton");

        waitForRows(scene);
        selectFirstRow(scene);
        FxTestSupport.fireAndAnswerPopup(scene, "#deleteButton", "Cancel");
        // The popup is answered while the button handler is still running: let it finish.
        FxTestSupport.onFxThread(() -> null);
        assertEquals(1, files.listTrash().size());

        waitForRows(scene);
        selectFirstRow(scene);
        FxTestSupport.fireAndAnswerPopup(scene, "#deleteButton", "Delete permanently");
        FxTestSupport.waitUntil(() -> files.listTrash().isEmpty());
    }

    @Test
    void emptyTrashStatesTheCountAndNeedsConfirmation() throws Exception {
        FileService files = FileService.forCurrentSession();
        for (String name : List.of("a.txt", "b.txt")) {
            files.moveToTrash(files.importFile(Files.writeString(tempDir.resolve(name), name), files.rootFolderId()).getEntryId());
        }
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#trashNavButton");
        waitForRows(scene);
        List<String> messages = new ArrayList<>();

        FxTestSupport.fireAndAnswer(scene, "#emptyTrashButton", popup -> {
            messages.add(((Labeled) popup.getScene().getRoot().lookup(".popup-message")).getText());
            FxTestSupport.clickButton(popup, "Cancel");
        });
        // The popup is answered while the button handler is still running: let it finish.
        FxTestSupport.onFxThread(() -> null);
        assertTrue(messages.get(0).contains("all 2 items"), messages.get(0));
        assertEquals(2, files.listTrash().size());

        FxTestSupport.fireAndAnswer(scene, "#emptyTrashButton", popup -> FxTestSupport.clickButton(popup, "Empty Trash"));
        FxTestSupport.waitUntil(() -> files.listTrash().isEmpty());
    }

    @Test
    void emptyTrashCountsTheItemsTheViewShowsAndRemovesWhatIsInsideThemToo() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry folder = files.createFolder("Old", files.rootFolderId());
        ManifestEntry inside = files.importFile(Files.writeString(tempDir.resolve("inside.txt"), "x"), folder.getEntryId());
        ManifestEntry loose = files.importFile(Files.writeString(tempDir.resolve("loose.txt"), "x"), files.rootFolderId());
        files.moveToTrash(List.of(folder.getEntryId(), inside.getEntryId()));
        files.moveToTrash(loose.getEntryId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#trashNavButton");
        waitForRows(scene);
        List<String> messages = new ArrayList<>();

        FxTestSupport.fireAndAnswer(scene, "#emptyTrashButton", popup -> {
            messages.add(((Labeled) popup.getScene().getRoot().lookup(".popup-message")).getText());
            FxTestSupport.clickButton(popup, "Empty Trash");
        });
        FxTestSupport.waitUntil(() -> files.listTrash().isEmpty());
        FxTestSupport.waitUntil(() -> !Background.isBusy());

        // Two rows in the view (the folder and the loose file), though three entries are trashed.
        assertTrue(messages.get(0).contains("all 2 items"), messages.get(0));
        assertFalse(Files.exists(new BlobRepository().blobPath(vault.root(), inside.getBlobId())));
        assertFalse(Files.exists(new BlobRepository().blobPath(vault.root(), loose.getBlobId())));
        assertTrue(pendingBlobIds().isEmpty());
    }

    @Test
    void emptyTrashStaysUsableWhenABlobCannotBeRemovedYetAndRetriesItLater() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry stuck = files.importFile(Files.writeString(tempDir.resolve("stuck.txt"), "x"), files.rootFolderId());
        files.moveToTrash(stuck.getEntryId());
        // A non-empty directory where the blob was: it cannot be deleted.
        Path blob = new BlobRepository().blobPath(vault.root(), stuck.getBlobId());
        Files.delete(blob);
        Files.createDirectories(blob);
        Files.writeString(blob.resolve("in-use"), "x");
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#trashNavButton");
        waitForRows(scene);

        FxTestSupport.fireAndAnswerPopup(scene, "#emptyTrashButton", "Empty Trash");

        FxTestSupport.waitUntil(() -> text(scene, "#feedbackLabel").contains(Formats.CLEANUP_PENDING));
        FxTestSupport.waitUntil(() -> rows(scene).isEmpty() && !Background.isBusy());
        assertEquals(List.of(stuck.getBlobId()), pendingBlobIds());
        assertFalse(FxTestSupport.onFxThread(() -> ((Node) scene.getRoot().lookup("#table")).isDisabled()));

        // Once the blob can go, the next Empty Trash removes it with the new items.
        Files.delete(blob.resolve("in-use"));
        files.moveToTrash(files.importFile(Files.writeString(tempDir.resolve("next.txt"), "x"), files.rootFolderId()).getEntryId());
        click(scene, "#trashNavButton");
        waitForRows(scene);

        FxTestSupport.fireAndAnswerPopup(scene, "#emptyTrashButton", "Empty Trash");

        FxTestSupport.waitUntil(() -> files.listTrash().isEmpty());
        FxTestSupport.waitUntil(() -> !Background.isBusy());
        assertTrue(pendingBlobIds().isEmpty());
        assertFalse(Files.exists(blob));
    }

    @Test
    void renameThroughThePrompt() throws Exception {
        FileService files = FileService.forCurrentSession();
        files.importFile(Files.writeString(tempDir.resolve("old.txt"), "x"), files.rootFolderId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        selectFirstRow(scene);

        FxTestSupport.fireAndAnswer(scene, "#renameButton", popup -> {
            ((TextField) popup.getScene().getRoot().lookup(".popup-input")).setText("new.txt");
            FxTestSupport.clickButton(popup, "Rename");
        });

        FxTestSupport.waitUntil(() -> files.listChildren(files.rootFolderId()).get(0).getName().equals("new.txt"));
    }

    @Test
    void moveThroughTheFolderPicker() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry target = files.createFolder("Target", files.rootFolderId());
        files.importFile(Files.writeString(tempDir.resolve("moved.txt"), "x"), files.rootFolderId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        FxTestSupport.onFxThread(() -> {
            ((TableView<?>) scene.getRoot().lookup("#table")).getSelectionModel().select(1);
            return null;
        });

        FxTestSupport.fireAndAnswer(scene, "#moveButton", popup -> {
            TreeView<?> tree = (TreeView<?>) popup.getScene().getRoot().lookup("#folderTree");
            tree.getSelectionModel().select(1);
            FxTestSupport.clickButton(popup, "Move here");
        });

        FxTestSupport.waitUntil(() -> files.listChildren(target.getEntryId()).size() == 1);
    }

    @Test
    void movingIntoTheFolderTheItemsAreInDoesNothing() throws Exception {
        FileService files = FileService.forCurrentSession();
        files.importFile(Files.writeString(tempDir.resolve("stay.txt"), "x"), files.rootFolderId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        selectFirstRow(scene);
        Path manifest = vault.root().resolve(".encryptdrive").resolve("manifests")
                .resolve(SessionService.identity().manifestId() + ".enc");
        byte[] before = Files.readAllBytes(manifest);

        FxTestSupport.fireAndAnswer(scene, "#moveButton", popup -> {
            ((TreeView<?>) popup.getScene().getRoot().lookup("#folderTree")).getSelectionModel().select(0);
            FxTestSupport.clickButton(popup, "Move here");
        });
        // The popup is answered while the button handler is still running: let it finish.
        FxTestSupport.onFxThread(() -> null);
        FxTestSupport.waitUntil(() -> !Background.isBusy());

        assertArrayEquals(before, Files.readAllBytes(manifest), "the manifest was saved again");
        assertEquals("", FxTestSupport.onFxThread(() -> text(scene, "#feedbackLabel")));
    }

    @Test
    void restoreSaysWhatItIsDoingUntilItIsDone() throws Exception {
        Scene scene = showTrashWithOneItem("a.txt");

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            click(scene, "#restoreButton");

            assertEquals(List.of("Restoring…", true), whileInFlight(scene));
        }

        FxTestSupport.waitUntil(() -> "\"a.txt\" was restored.".equals(text(scene, "#feedbackLabel")));
    }

    @Test
    void permanentDeleteSaysWhatItIsDoingUntilItIsDone() throws Exception {
        Scene scene = showTrashWithOneItem("a.txt");

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            FxTestSupport.fireAndAnswerPopup(scene, "#deleteButton", "Delete permanently");
            // The popup is answered while the button handler is still running: let it finish.
            FxTestSupport.onFxThread(() -> null);

            assertEquals(List.of("Deleting permanently…", true), whileInFlight(scene));
        }

        FxTestSupport.waitUntil(() -> "\"a.txt\" was deleted permanently.".equals(text(scene, "#feedbackLabel")));
    }

    @Test
    void emptyTrashSaysWhatItIsDoingUntilItIsDone() throws Exception {
        Scene scene = showTrashWithOneItem("a.txt");

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            FxTestSupport.fireAndAnswerPopup(scene, "#emptyTrashButton", "Empty Trash");
            // The popup is answered while the button handler is still running: let it finish.
            FxTestSupport.onFxThread(() -> null);

            assertEquals(List.of("Emptying the trash…", true), whileInFlight(scene));
        }

        FxTestSupport.waitUntil(() -> "The trash was emptied.".equals(text(scene, "#feedbackLabel")));
    }

    @Test
    void theLoginTimeCleanupSaysWhyTheSidebarIsLocked() throws Exception {
        queueAsInterruptedDelete("orphan.txt");
        AtomicReference<ManifestLockProbe> held = new AtomicReference<>();
        Scene scene = FxTestSupport.showScreen("dashboard", loaded -> {
            loaded.getRoot().applyCss();
            ((Labeled) loaded.getRoot().lookup("#feedbackLabel")).textProperty().addListener((observable, before, shown) -> {
                // The cleanup starts right after this text is set: hold the manifest so it stays in flight.
                if (shown.equals("Finishing an earlier deletion…") && held.get() == null) {
                    held.set(holdManifest());
                }
            });
        });

        try {
            FxTestSupport.waitUntil(() -> held.get() != null && Background.isBusy());

            assertEquals(List.of("Finishing an earlier deletion…", true), whileInFlight(scene));
        } finally {
            if (held.get() != null) {
                held.get().close();
            }
        }

        FxTestSupport.settleBackground();
        assertFalse(FxTestSupport.onFxThread(() -> ((Node) scene.getRoot().lookup("#feedbackLabel")).isVisible()));
        assertTrue(pendingBlobIds().isEmpty());
    }

    @Test
    void aPermanentDeleteThatFailsAfterTheEntriesAreGoneSaysTheCleanupWillRetry() throws Exception {
        Scene scene = showTrashWithOneItem("old.txt");
        blockBackup(1);   // the manifest is written, then the backups cannot be replaced

        FxTestSupport.fireAndAnswerPopup(scene, "#deleteButton", "Delete permanently");

        assertEquals("\"old.txt\" was deleted permanently. " + Formats.CLEANUP_PENDING, outcome(scene));
        assertTrue(FileService.forCurrentSession().listTrash().isEmpty());
    }

    @Test
    void anEmptyTrashThatFailsAfterTheEntriesAreGoneSaysTheCleanupWillRetry() throws Exception {
        Scene scene = showTrashWithOneItem("old.txt");
        blockBackup(1);

        FxTestSupport.fireAndAnswerPopup(scene, "#emptyTrashButton", "Empty Trash");

        assertEquals("The trash was emptied. " + Formats.CLEANUP_PENDING, outcome(scene));
        assertTrue(FileService.forCurrentSession().listTrash().isEmpty());
    }

    @Test
    void renamingToTheSameNamePaddedWithSpacesDoesNothing() throws Exception {
        FileService files = FileService.forCurrentSession();
        files.importFile(Files.writeString(tempDir.resolve("old.txt"), "x"), files.rootFolderId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        selectFirstRow(scene);
        byte[] before = Files.readAllBytes(liveManifest());

        FxTestSupport.fireAndAnswer(scene, "#renameButton", popup -> {
            ((TextField) popup.getScene().getRoot().lookup(".popup-input")).setText("  old.txt ");
            FxTestSupport.clickButton(popup, "Rename");
        });
        // The popup is answered while the button handler is still running: let it finish.
        FxTestSupport.onFxThread(() -> null);
        FxTestSupport.settleBackground();

        assertArrayEquals(before, Files.readAllBytes(liveManifest()), "the manifest was saved again");
        assertEquals("", FxTestSupport.onFxThread(() -> text(scene, "#feedbackLabel")));
    }

    @Test
    void aMoveThatFinishesLoadingAfterTheUserLeftTheViewOpensNoPicker() throws Exception {
        Scene scene = showFilesWithAFolderAndAFile();

        try {
            try (ManifestLockProbe blocked = new ManifestLockProbe()) {
                click(scene, "#moveButton");         // its folder read waits for the lock
                click(scene, "#profileNavButton");   // the Files view is replaced meanwhile
            }

            FxTestSupport.settleBackground();

            assertEquals(0, FxTestSupport.onFxThread(() -> FxTestSupport.modalPopups().size()));
        } finally {
            closePopups();
        }
    }

    @Test
    void aSecondMoveWhileTheFoldersLoadOpensNoSecondPicker() throws Exception {
        Scene scene = showFilesWithAFolderAndAFile();

        try {
            try (ManifestLockProbe blocked = new ManifestLockProbe()) {
                FxTestSupport.onFxThread(() -> {
                    ButtonBase move = (ButtonBase) scene.getRoot().lookup("#moveButton");
                    move.fire();
                    move.fire();
                    return null;
                });
            }

            FxTestSupport.waitUntil(() -> !FxTestSupport.modalPopups().isEmpty());
            FxTestSupport.settleBackground();

            assertEquals(1, FxTestSupport.onFxThread(() -> FxTestSupport.modalPopups().size()));

            FxTestSupport.onFxThread(() -> {
                FxTestSupport.clickButton(FxTestSupport.modalPopups().get(0), "Cancel");
                return null;
            });
            // Closing the picker unlocks the view again.
            FxTestSupport.waitUntil(() -> !((Node) scene.getRoot().lookup("#moveButton")).isDisable());
        } finally {
            closePopups();
        }
    }

    @Test
    void searchShowsMatchesFromEveryFolderAndClearReturns() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        files.importFile(Files.writeString(tempDir.resolve("invoice.pdf"), "x"), docs.getEntryId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);

        search(scene, "INVOICE");

        FxTestSupport.waitUntil(() -> rows(scene).size() == 1 && rows(scene).get(0).getName().equals("invoice.pdf"));
        assertEquals("My files › Docs", FxTestSupport.onFxThread(() -> locationOfFirstRow(scene)));
        // Search results have no current folder to create or import into.
        assertTrue(FxTestSupport.onFxThread(() -> ((Node) scene.getRoot().lookup("#newFolderButton")).isDisable()));
        assertTrue(FxTestSupport.onFxThread(() -> ((Node) scene.getRoot().lookup("#importMenu")).isDisable()));

        click(scene, "#clearSearchButton");

        FxTestSupport.waitUntil(() -> rows(scene).size() == 1 && rows(scene).get(0).getName().equals("Docs"));
        assertEquals("", FxTestSupport.onFxThread(() -> ((TextField) scene.getRoot().lookup("#searchField")).getText()));
        assertFalse(FxTestSupport.onFxThread(() -> ((Node) scene.getRoot().lookup("#newFolderButton")).isDisable()));
        assertFalse(FxTestSupport.onFxThread(() -> ((Node) scene.getRoot().lookup("#importMenu")).isDisable()));
    }

    @Test
    void openingAResultGoesToItsFolderAndSelectsIt() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        files.importFile(Files.writeString(tempDir.resolve("invoice.pdf"), "x"), docs.getEntryId());
        files.importFile(Files.writeString(tempDir.resolve("other.txt"), "x"), docs.getEntryId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        search(scene, "invoice");
        FxTestSupport.waitUntil(() -> rows(scene).size() == 1);

        FxTestSupport.waitUntil(() -> doubleClickRow(scene, "invoice.pdf"));

        FxTestSupport.waitUntil(() -> rows(scene).size() == 2);
        assertEquals(List.of("invoice.pdf"), FxTestSupport.onFxThread(() -> ((TableView<?>) scene.getRoot()
                .lookup("#table")).getSelectionModel().getSelectedItems().stream()
                .map(item -> ((ManifestEntry) item).getName()).toList()));
        assertEquals("", FxTestSupport.onFxThread(() -> ((TextField) scene.getRoot().lookup("#searchField")).getText()));
        assertFalse(FxTestSupport.onFxThread(() -> ((Node) scene.getRoot().lookup("#clearSearchButton")).isVisible()));
        assertEquals(List.of("My files", "›", "Docs"), FxTestSupport.onFxThread(() -> ((HBox) scene.getRoot()
                .lookup("#breadcrumbBar")).getChildren().stream().map(node -> ((Labeled) node).getText()).toList()));
    }

    @Test
    void aChangeMadeInTheResultsRerunsTheSearch() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        files.importFile(Files.writeString(tempDir.resolve("invoice-a.pdf"), "x"), docs.getEntryId());
        files.importFile(Files.writeString(tempDir.resolve("invoice-b.pdf"), "x"), files.rootFolderId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        search(scene, "invoice");
        FxTestSupport.waitUntil(() -> rows(scene).size() == 2);
        selectFirstRow(scene);

        click(scene, "#trashButton");

        FxTestSupport.waitUntil(() -> rows(scene).size() == 1);
        assertEquals(1, files.listTrash().size());
        assertTrue(FxTestSupport.onFxThread(() -> ((Node) scene.getRoot().lookup("#clearSearchButton")).isVisible()));
    }

    @Test
    void aFolderAndAFileInsideItCanBeTrashedTogetherFromTheResults() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry year = files.createFolder("2025 report folder", files.rootFolderId());
        files.importFile(Files.writeString(tempDir.resolve("report.pdf"), "x"), year.getEntryId());
        files.importFile(Files.writeString(tempDir.resolve("report.txt"), "x"), files.rootFolderId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        search(scene, "report");
        FxTestSupport.waitUntil(() -> rows(scene).size() == 3);
        FxTestSupport.onFxThread(() -> {
            ((TableView<?>) scene.getRoot().lookup("#table")).getSelectionModel().selectAll();
            return null;
        });

        click(scene, "#trashButton");

        FxTestSupport.waitUntil(() -> rows(scene).isEmpty());
        assertEquals("3 items moved to the trash.", FxTestSupport.onFxThread(() -> text(scene, "#feedbackLabel")));
        assertEquals(List.of("2025 report folder", "report.txt"),
                files.listTrash().stream().map(ManifestEntry::getName).sorted().toList());
    }

    @Test
    void aSearchStillRunningWhenTheViewRefreshesIsNotDropped() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        files.importFile(Files.writeString(tempDir.resolve("invoice.pdf"), "x"), docs.getEntryId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        FxTestSupport.waitUntil(() -> doubleClickRow(scene, "Docs"));
        FxTestSupport.waitUntil(() -> ((HBox) scene.getRoot().lookup("#breadcrumbBar")).getChildren().size() == 3);

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            search(scene, "invoice");               // waits for the manifest lock
            click(scene, ".breadcrumb-button");     // reloads the view meanwhile
        }

        // The Docs folder shows the same file, so only the Clear button tells results from that folder.
        FxTestSupport.waitUntil(() -> ((Node) scene.getRoot().lookup("#clearSearchButton")).isVisible());
        assertEquals(List.of("invoice.pdf"), FxTestSupport.onFxThread(() -> rows(scene).stream().map(ManifestEntry::getName).toList()));
    }

    @Test
    void overviewRetriesDeletionsInterruptedEarlier() throws Exception {
        ManifestEntry orphan = queueAsInterruptedDelete("orphan.txt");
        Path blob = new BlobRepository().blobPath(vault.root(), orphan.getBlobId());

        FxTestSupport.showScreen("dashboard");

        FxTestSupport.waitUntil(() -> !Files.exists(blob));
        // The blob goes first; the shorter journal is saved just after.
        FxTestSupport.waitUntil(() -> !Background.isBusy());
        assertTrue(pendingBlobIds().isEmpty());
    }

    @Test
    void overviewRetriesAnUndeletableBlobOnceAndLeavesItQueued() throws Exception {
        ManifestEntry stuck = queueAsInterruptedDelete("stuck.txt");
        // A non-empty directory where the blob was: it cannot be deleted.
        Path blob = new BlobRepository().blobPath(vault.root(), stuck.getBlobId());
        Files.delete(blob);
        Files.createDirectories(blob);
        Files.writeString(blob.resolve("in-use"), "x");

        Scene scene = FxTestSupport.showScreen("dashboard");

        FxTestSupport.waitUntil(() -> Formats.CLEANUP_PENDING.equals(text(scene, "#feedbackLabel")));
        FxTestSupport.waitUntil(() -> !Background.isBusy());

        for (int sample = 0; sample < 40; sample++) {
            Thread.sleep(25);
            assertFalse(FxTestSupport.onFxThread(Background::isBusy), "the deletion was retried again");
        }

        assertEquals(List.of(stuck.getBlobId()), pendingBlobIds());
    }

    @Test
    void recoveryNoticeIsLeftForTheViewThatIsShowing() throws Exception {
        FileService files = FileService.forCurrentSession();
        files.importFile(Files.writeString(tempDir.resolve("kept.txt"), "k"), files.rootFolderId());
        files.createFolder("Newest", files.rootFolderId());
        RecoveryService.takeRecoveryNotice();
        damageManifest();
        Scene scene;
        Labeled staleOverviewCount;

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            scene = FxTestSupport.showScreen("dashboard");   // its statistics read waits for the lock
            staleOverviewCount = FxTestSupport.onFxThread(() -> {
                scene.getRoot().applyCss();
                return (Labeled) scene.getRoot().lookup("#fileCountLabel");
            });
            click(scene, "#profileNavButton");                // leave the Overview before that read runs
        }

        // The Overview's read recovers the manifest from a backup and reaches a detached view.
        FxTestSupport.waitUntil(() -> "1".equals(staleOverviewCount.getText()));
        click(scene, "#filesNavButton");
        waitForRows(scene);

        assertEquals(Formats.RECOVERY_NOTICE, FxTestSupport.onFxThread(() -> text(scene, "#feedbackLabel")));
    }

    @Test
    void vaultSettingsShowTheApplicationVersion() throws Exception {
        Scene scene = FxTestSupport.showScreen("dashboard");

        click(scene, "#settingsNavButton");

        assertEquals(App.version(), FxTestSupport.onFxThread(() -> {
            // The settings ScrollPane exposes its content only once its skin exists.
            scene.getRoot().applyCss();
            return ((Labeled) scene.getRoot().lookup("#appVersionLabel")).getText();
        }));
    }

    /** The Trash view listing one trashed file, with that row selected. */
    private Scene showTrashWithOneItem(String name) throws Exception {
        FileService files = FileService.forCurrentSession();
        files.moveToTrash(files.importFile(Files.writeString(tempDir.resolve(name), "x"), files.rootFolderId()).getEntryId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#trashNavButton");
        waitForRows(scene);
        selectFirstRow(scene);
        return scene;
    }

    /** The Files view with a folder and a file in the root, the folder's row selected. */
    private Scene showFilesWithAFolderAndAFile() throws Exception {
        FileService files = FileService.forCurrentSession();
        files.createFolder("Target", files.rootFolderId());
        files.importFile(Files.writeString(tempDir.resolve("moved.txt"), "x"), files.rootFolderId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        selectFirstRow(scene);
        return scene;
    }

    /** The view's feedback text and whether a counted task is running, read together. */
    private static List<Object> whileInFlight(Scene scene) throws Exception {
        return FxTestSupport.onFxThread(() -> List.of(text(scene, "#feedbackLabel"), Background.isBusy()));
    }

    /** The first feedback text that is not a progress text (those end with an ellipsis). */
    private static String outcome(Scene scene) throws Exception {
        FxTestSupport.waitUntil(() -> {
            String shown = text(scene, "#feedbackLabel");
            return !shown.isEmpty() && !shown.endsWith("…");
        });

        return FxTestSupport.onFxThread(() -> text(scene, "#feedbackLabel"));
    }

    private static void closePopups() throws Exception {
        FxTestSupport.onFxThread(() -> {
            FxTestSupport.modalPopups().forEach(Stage::hide);
            return null;
        });
    }

    private static ManifestLockProbe holdManifest() {
        try {
            return new ManifestLockProbe();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path liveManifest() {
        return vault.root().resolve(".encryptdrive").resolve("manifests")
                .resolve(SessionService.identity().manifestId() + ".enc");
    }

    /** Puts a non-empty directory where a backup generation goes, so the backup cannot be replaced. */
    private void blockBackup(int generation) throws Exception {
        Path backup = vault.root().resolve(".encryptdrive").resolve("backups").resolve("manifests")
                .resolve(SessionService.identity().manifestId() + ".enc." + generation);
        Files.deleteIfExists(backup);
        Files.createDirectories(backup.resolve("blocker"));
    }

    /** As if a permanent delete stopped after its manifest commit: entry gone, blob queued. */
    private ManifestEntry queueAsInterruptedDelete(String name) throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry orphan = files.importFile(Files.writeString(tempDir.resolve(name), "x"), files.rootFolderId());
        UserSessionIdentity identity = SessionService.identity();
        byte[] key = userMasterKey.copy();
        ManifestRepository manifests = new ManifestRepository(vault);
        UserManifest manifest = manifests.load(identity.userId(), identity.manifestId(), key);
        manifest.getEntries().removeIf(entry -> entry.getEntryId().equals(orphan.getEntryId()));
        manifest.getPendingDeletions().add(new PendingDeletion(orphan.getBlobId(), Instant.now().toString()));
        manifests.save(manifest, identity.manifestId(), key);
        return orphan;
    }

    private List<UUID> pendingBlobIds() throws Exception {
        UserSessionIdentity identity = SessionService.identity();

        return new ManifestRepository(vault)
                .load(identity.userId(), identity.manifestId(), userMasterKey.copy())
                .getPendingDeletions().stream().map(PendingDeletion::blobId).toList();
    }

    /** Flips one ciphertext bit of the live manifest, so the next load restores a backup. */
    private void damageManifest() throws Exception {
        Path manifest = vault.root().resolve(".encryptdrive").resolve("manifests")
                .resolve(SessionService.identity().manifestId() + ".enc");
        JsonObject envelope = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        byte[] ciphertext = Base64.getDecoder().decode(envelope.get("ciphertext").getAsString());
        ciphertext[ciphertext.length / 2] ^= 0x01;
        envelope.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Files.writeString(manifest, envelope.toString());
    }

    /** Text of a node inside the workspace; call on the JavaFX thread. */
    private static String text(Scene scene, String selector) {
        scene.getRoot().applyCss();
        return ((Labeled) scene.getRoot().lookup(selector)).getText();
    }

    private static void waitForRows(Scene scene) throws Exception {
        FxTestSupport.waitUntil(() -> !((TableView<?>) scene.getRoot().lookup("#table")).getItems().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static List<ManifestEntry> rows(Scene scene) {
        return ((TableView<ManifestEntry>) scene.getRoot().lookup("#table")).getItems();
    }

    /** Types the query and presses Enter, the way a user searches. */
    private static void search(Scene scene, String query) throws Exception {
        FxTestSupport.onFxThread(() -> {
            TextField search = (TextField) scene.getRoot().lookup("#searchField");
            search.setText(query);
            search.fireEvent(new ActionEvent());
            return null;
        });
    }

    /** The LOCATION cell of the first row; call on the JavaFX thread. */
    @SuppressWarnings("unchecked")
    private static String locationOfFirstRow(Scene scene) {
        TableView<ManifestEntry> table = (TableView<ManifestEntry>) scene.getRoot().lookup("#table");
        return (String) table.getColumns().get(table.getColumns().size() - 1).getCellData(0);
    }

    /**
     * Double-clicks the row showing the named entry. A table that is not in a
     * showing window only updates its rows when laid out, so this is false
     * until a layout has brought that row up; call on the JavaFX thread.
     */
    private static boolean doubleClickRow(Scene scene, String name) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();

        for (Node node : scene.getRoot().lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row
                    && row.getItem() instanceof ManifestEntry entry
                    && entry.getName().equals(name)) {
                Event.fireEvent(row, new MouseEvent(
                        MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 2,
                        false, false, false, false, true, false, false, true, false, true, null
                ));
                return true;
            }
        }

        return false;
    }

    private static void selectFirstRow(Scene scene) throws Exception {
        FxTestSupport.onFxThread(() -> {
            ((TableView<?>) scene.getRoot().lookup("#table")).getSelectionModel().select(0);
            return null;
        });
    }

    private void assertClosedAndBackAtVaultSelection(Scene scene) throws Exception {
        assertTrue(userMasterKey.isDestroyed());
        assertTrue(vault.isClosed());
        assertFalse(VaultSessionService.isOpen());
        assertNotNull(FxTestSupport.onFxThread(() -> scene.getRoot().lookup("#openModeButton")));
    }

    private static void click(Scene scene, String selector) throws Exception {
        FxTestSupport.onFxThread(() -> {
            ((ButtonBase) scene.getRoot().lookup(selector)).fire();
            return null;
        });
    }
}
