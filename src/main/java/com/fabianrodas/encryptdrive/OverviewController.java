package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.VaultContext;
import com.fabianrodas.models.WorkspaceStats;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.FileServiceException;
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

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (!SessionService.isActive() || !VaultSessionService.isOpen()) {
            return;
        }

        VaultContext vault = VaultSessionService.current();
        welcomeLabel.setText("Welcome back, " + SessionService.identity().fullName() + "!");
        vaultNameLabel.setText(App.openVaultName());
        vaultPathLabel.setText(vault.root().toString());

        try {
            WorkspaceStats stats = FileService.forCurrentSession().stats();

            fileCountLabel.setText(String.valueOf(stats.activeFileCount()));
            plainSizeLabel.setText(Formats.bytes(stats.activePlainBytes()));
            encryptedSizeLabel.setText(Formats.bytes(stats.encryptedBytes()));
            trashCountLabel.setText(String.valueOf(stats.trashCount()));
            show(emptyStateCard, stats.activeFileCount() == 0);

            if (RecoveryService.takeRecoveryNotice()) {
                feedbackLabel.setText(Formats.RECOVERY_NOTICE);
                feedbackLabel.getStyleClass().add("notice");
                show(feedbackLabel, true);
            }

        } catch (FileServiceException e) {
            feedbackLabel.setText("Your encrypted file list could not be read.");
            show(feedbackLabel, true);
        }
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
