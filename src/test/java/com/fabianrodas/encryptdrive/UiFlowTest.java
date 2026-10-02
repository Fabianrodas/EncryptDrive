package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.security.SensitiveBytes;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultService;
import com.fabianrodas.services.VaultSessionService;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableView;
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
    void closeVault() {
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

        selectFirstRow(scene);
        FxTestSupport.fireAndAnswerPopup(scene, "#deleteButton", "Cancel");
        // The popup is answered while the button handler is still running: let it finish.
        FxTestSupport.onFxThread(() -> null);
        assertEquals(1, files.listTrash().size());

        selectFirstRow(scene);
        FxTestSupport.fireAndAnswerPopup(scene, "#deleteButton", "Delete permanently");
        FxTestSupport.waitUntil(() -> files.listTrash().isEmpty());
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
