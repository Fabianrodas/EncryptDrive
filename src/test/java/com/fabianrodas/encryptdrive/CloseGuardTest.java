package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.security.SensitiveBytes;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultService;
import com.fabianrodas.services.VaultSessionService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/*
 * Spec 11: while background work runs, no exit path may close the window,
 * log out, or close the vault; when idle, closing wipes every key.
 */
class CloseGuardTest {

    @TempDir
    Path tempDir;

    private VaultContext vault;
    private SensitiveBytes userMasterKey;
    private CountDownLatch busy;

    @BeforeAll
    static void startJavaFx() {
        assumeTrue(FxTestSupport.start(), "JavaFX needs a desktop session");
    }

    @BeforeEach
    void openVaultAndSignIn() throws Exception {
        vault = new VaultService().createVault(tempDir.resolve("vault"), "correct vault password".toCharArray());
        AuthService auth = new AuthService(vault);
        auth.register("Example User", "ExampleUser", "example password".toCharArray());
        UserLoginResult login = auth.login("ExampleUser", "example password".toCharArray());
        userMasterKey = login.userMasterKey();
        SessionService.start(login.identity(), userMasterKey);
    }

    /** Leaves no held busy latch, no showing window and no open vault, even after a failed assertion. */
    @AfterEach
    void cleanUp() throws Exception {
        try {
            if (busy != null) {
                busy.countDown();
            }

            FxTestSupport.waitUntil(() -> !Background.isBusy());
        } finally {
            try {
                FxTestSupport.onFxThread(() -> {
                    for (Window window : List.copyOf(Window.getWindows())) {
                        if (window instanceof Stage stage) {
                            stage.close();
                        }
                    }
                    return null;
                });
            } finally {
                VaultSessionService.closeVault();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"vault-selection", "login", "register", "dashboard"})
    void titleBarCloseButtonIsRefusedWhileBusyAndExplained(String screen) throws Exception {
        Stage stage = FxTestSupport.showInStage(screen);
        busy = FxTestSupport.holdBusy();

        click(stage, "#close");

        assertCloseRefused(stage);
    }

    @Test
    void windowCloseRequestIsRefusedWhileBusyAndExplained() throws Exception {
        Stage stage = FxTestSupport.showInStage("dashboard");
        busy = FxTestSupport.holdBusy();

        FxTestSupport.onFxThread(() -> {
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            return null;
        });

        assertCloseRefused(stage);
    }

    /*
     * Alt+F4 (and the taskbar's Close) reaches JavaFX as the same
     * WINDOW_CLOSE_REQUEST the toolkit fires at the stage for the OS, on
     * whatever screen is showing.
     */
    @ParameterizedTest
    @ValueSource(strings = {"vault-selection", "login", "register", "dashboard"})
    void altF4IsRefusedWhileBusyOnEveryScreen(String screen) throws Exception {
        Stage stage = FxTestSupport.showInStage(screen);
        busy = FxTestSupport.holdBusy();

        FxTestSupport.onFxThread(() -> {
            Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            return null;
        });

        assertCloseRefused(stage);
    }

    @Test
    void idleCloseWipesKeysReleasesTheLockAndLeavesNoWorker() throws Exception {
        Stage stage = FxTestSupport.showInStage("dashboard");

        click(stage, "#close");

        assertFalse(FxTestSupport.onFxThread(stage::isShowing));
        assertTrue(userMasterKey.isDestroyed());
        assertTrue(vault.isClosed());
        assertFalse(VaultSessionService.isOpen());
        new VaultService().unlockVault(vault.root(), "correct vault password".toCharArray());
        FxTestSupport.waitUntil(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().equals("EncryptDrive worker")));
    }

    @Test
    void idleWindowCloseRequestWipesKeysAndHidesTheWindow() throws Exception {
        Stage stage = FxTestSupport.showInStage("dashboard");

        FxTestSupport.onFxThread(() -> {
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            return null;
        });

        assertFalse(FxTestSupport.onFxThread(stage::isShowing));
        assertTrue(userMasterKey.isDestroyed());
        assertTrue(vault.isClosed());
        assertFalse(VaultSessionService.isOpen());
    }

    @Test
    void logoutIsDisabledAndRefusedWhileBusy() throws Exception {
        Stage stage = FxTestSupport.showInStage("dashboard");
        busy = FxTestSupport.holdBusy();

        assertTrue(FxTestSupport.onFxThread(() -> lookup(stage, "#logoutButton").isDisabled()));
        assertFalse(FxTestSupport.onFxThread(App::logout));
        assertFalse(userMasterKey.isDestroyed());
        assertTrue(SessionService.isActive());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dashboard", "login", "register"})
    void closeVaultIsDisabledAndRefusedWhileBusy(String screen) throws Exception {
        Stage stage = FxTestSupport.showInStage(screen);
        busy = FxTestSupport.holdBusy();

        assertTrue(FxTestSupport.onFxThread(() -> lookup(stage, "#closeVaultButton").isDisabled()));
        assertFalse(FxTestSupport.onFxThread(App::closeVault));
        assertFalse(vault.isClosed());
        assertTrue(VaultSessionService.isOpen());
    }

    @Test
    void vaultSettingsCloseVaultIsDisabledWhileBusy() throws Exception {
        Stage stage = FxTestSupport.showInStage("dashboard");
        click(stage, "#settingsNavButton");
        busy = FxTestSupport.holdBusy();

        assertTrue(FxTestSupport.onFxThread(() -> {
            // The settings ScrollPane exposes its content only once its skin exists.
            stage.getScene().getRoot().applyCss();
            return lookup(stage, "#settingsCloseVaultButton").isDisabled();
        }));
    }

    /** The window stays open, says why, and no key was wiped. */
    private void assertCloseRefused(Stage stage) throws Exception {
        assertTrue(FxTestSupport.onFxThread(stage::isShowing));
        assertTrue(FxTestSupport.onFxThread(() -> popupShows(App.BUSY_CLOSE_MESSAGE)));
        assertFalse(userMasterKey.isDestroyed());
        assertFalse(vault.isClosed());
        assertTrue(VaultSessionService.isOpen());
    }

    private static Node lookup(Stage stage, String selector) {
        return stage.getScene().getRoot().lookup(selector);
    }

    private static void click(Stage stage, String selector) throws Exception {
        FxTestSupport.onFxThread(() -> {
            ((ButtonBase) lookup(stage, selector)).fire();
            return null;
        });
    }

    private static boolean popupShows(String message) {
        return Window.getWindows().stream()
                .filter(window -> window instanceof Stage stage && stage.isShowing())
                .flatMap(window -> window.getScene().getRoot().lookupAll(".label").stream())
                .anyMatch(node -> node instanceof Labeled label && message.equals(label.getText()));
    }
}
