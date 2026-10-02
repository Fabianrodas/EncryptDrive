package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.ManifestLockProbe;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultService;
import com.fabianrodas.services.VaultSessionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/*
 * Spec 14.1: no manifest decrypt, read or write on the JavaFX thread. Each
 * test blocks all manifest access, drives one UI flow, and requires the
 * JavaFX thread to stay responsive; the flow then completes once unblocked.
 */
class FxThreadBoundaryTest {

    @TempDir
    Path tempDir;

    private FileService files;

    @BeforeAll
    static void startJavaFx() {
        assumeTrue(FxTestSupport.start(), "JavaFX needs a desktop session");
    }

    @BeforeEach
    void openVaultAndSignIn() throws Exception {
        VaultContext vault = new VaultService().createVault(
                tempDir.resolve("vault"), "correct vault password".toCharArray());
        AuthService auth = new AuthService(vault);
        auth.register("Example User", "ExampleUser", "example password".toCharArray());
        UserLoginResult login = auth.login("ExampleUser", "example password".toCharArray());
        SessionService.start(login.identity(), login.userMasterKey());
        files = FileService.forCurrentSession();
        files.importFile(Files.writeString(tempDir.resolve("a.txt"), "a"), files.rootFolderId());
    }

    @AfterEach
    void closeVault() throws Exception {
        FxTestSupport.waitUntil(() -> !Background.isBusy());
        VaultSessionService.closeVault();
    }

    @ParameterizedTest
    @ValueSource(strings = {"#overviewNavButton", "#filesNavButton", "#trashNavButton"})
    void openingAViewNeverLoadsTheManifestOnTheFxThread(String navigation) throws Exception {
        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            Scene scene = FxTestSupport.showScreen("dashboard");   // Overview loads its statistics
            fire(scene, navigation);
            assertFxThreadResponsive();
        }
    }

    @Test
    void movingAFolderToTheTrashWritesTheManifestOffTheFxThread() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        Scene scene = showFiles();

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            FxTestSupport.onFxThread(() -> {
                TableView<ManifestEntry> table = table(scene);
                table.getSelectionModel().select(table.getItems().stream()
                        .filter(entry -> entry.getEntryId().equals(docs.getEntryId())).findFirst().orElseThrow());
                return null;
            });
            fire(scene, "#trashButton");
            assertFxThreadResponsive();
        }

        FxTestSupport.waitUntil(() -> files.listTrash().size() == 1);
    }

    @Test
    void creatingAFolderWritesTheManifestOffTheFxThread() throws Exception {
        Scene scene = showFiles();

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            FxTestSupport.fireAndAnswer(scene, "#newFolderButton", popup -> {
                ((TextField) popup.getScene().getRoot().lookup(".popup-input")).setText("Made in background");
                FxTestSupport.clickButton(popup, "Create");
            });
            assertFxThreadResponsive();
        }

        FxTestSupport.waitUntil(() -> files.listChildren(files.rootFolderId()).stream()
                .anyMatch(entry -> entry.getName().equals("Made in background")));
    }

    @Test
    void restoringFromTheTrashWritesTheManifestOffTheFxThread() throws Exception {
        files.moveToTrash(files.listChildren(files.rootFolderId()).get(0).getEntryId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        fire(scene, "#trashNavButton");
        FxTestSupport.waitUntil(() -> !table(scene).getItems().isEmpty());
        FxTestSupport.onFxThread(() -> {
            table(scene).getSelectionModel().select(0);
            return null;
        });

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            fire(scene, "#restoreButton");
            assertFxThreadResponsive();
        }

        FxTestSupport.waitUntil(() -> files.listTrash().isEmpty());
    }

    private Scene showFiles() throws Exception {
        Scene scene = FxTestSupport.showScreen("dashboard");
        fire(scene, "#filesNavButton");
        FxTestSupport.waitUntil(() -> !table(scene).getItems().isEmpty());
        return scene;
    }

    @SuppressWarnings("unchecked")
    private static TableView<ManifestEntry> table(Scene scene) {
        return (TableView<ManifestEntry>) scene.getRoot().lookup("#table");
    }

    /** Fires without waiting: a handler that blocks the FX thread must not block the test thread too. */
    private static void fire(Scene scene, String selector) {
        Platform.runLater(() -> ((ButtonBase) scene.getRoot().lookup(selector)).fire());
    }

    /** The FX thread must answer within two seconds although every manifest access is blocked. */
    private static void assertFxThreadResponsive() throws Exception {
        CompletableFuture<Boolean> answered = new CompletableFuture<>();
        Platform.runLater(() -> answered.complete(true));

        try {
            assertTrue(answered.get(2, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            throw new AssertionError("the JavaFX thread is blocked on manifest work", e);
        }
    }
}
