package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.VaultContext;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.RecoveryService;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultSessionService;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * FXML Controller class
 *
 * @author Fabian Rodas
 */

public class OverviewController implements Initializable {

    @FXML
    private Label welcomeLabel;

    @FXML
    private Label vaultNameLabel;

    @FXML
    private Label vaultPathLabel;

    @FXML
    private Label fileCountLabel;

    @FXML
    private Label plainSizeLabel;

    @FXML
    private Label encryptedSizeLabel;

    @FXML
    private Label trashCountLabel;

    @FXML
    private Label feedbackLabel;

    @FXML
    private VBox emptyStateCard;

    private Runnable onOpenFiles = () -> { };
    private FileService files;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (!SessionService.isActive() || !VaultSessionService.isOpen()) {
            return;
        }

        VaultContext vault = VaultSessionService.current();
        welcomeLabel.setText("Welcome back, " + SessionService.identity().fullName() + "!");
        vaultNameLabel.setText(App.openVaultName());
        vaultPathLabel.setText(vault.root().toString());

        files = FileService.forCurrentSession();
        loadStats(true);
    }

    /** Spec 9.4: pending deletions are retried at most once per Overview load. */
    private void loadStats(boolean retryPendingDeletions) {
        Background.read(files::stats, stats -> {
            fileCountLabel.setText(String.valueOf(stats.activeFileCount()));
            plainSizeLabel.setText(Formats.bytes(stats.activePlainBytes()));
            encryptedSizeLabel.setText(Formats.bytes(stats.encryptedBytes()));
            trashCountLabel.setText(String.valueOf(stats.trashCount()));
            show(emptyStateCard, stats.activeFileCount() == 0);

            // A view the user already left must leave the one-shot notice for the visible one.
            if (feedbackLabel.getScene() != null && RecoveryService.takeRecoveryNotice()) {
                showFeedback(Formats.RECOVERY_NOTICE);
            }

            if (retryPendingDeletions && stats.pendingDeletions() > 0) {
                resumePendingDeletions();
            }
        }, failure -> showFeedback("Your encrypted file list could not be read."));
    }

    /** Deletions that stopped earlier are retried after login; failures only warn. */
    private void resumePendingDeletions() {
        Background.run(files::resumePendingDeletions, remaining -> {
            if (remaining > 0) {
                showFeedback(Formats.CLEANUP_PENDING);
            }

            loadStats(false);
        }, failure -> showFeedback(Formats.CLEANUP_PENDING));
    }

    private void showFeedback(String message) {
        feedbackLabel.setText(message);

        if (!feedbackLabel.getStyleClass().contains("notice")) {
            feedbackLabel.getStyleClass().add("notice");
        }

        show(feedbackLabel, true);
    }

    void setOnOpenFiles(Runnable onOpenFiles) {
        this.onOpenFiles = onOpenFiles;
    }

    @FXML
    private void openFiles() {
        onOpenFiles.run();
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
