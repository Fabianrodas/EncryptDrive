package com.fabianrodas.encryptdrive;

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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
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
