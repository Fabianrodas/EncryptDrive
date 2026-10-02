package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.VaultContext;
import com.fabianrodas.services.VaultException;
import com.fabianrodas.services.VaultService;
import com.fabianrodas.services.VaultSessionService;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

/**
 * FXML Controller class
 *
 * @author Fabian Rodas
 */

public class VaultSettingsController implements Initializable {

    @FXML
    private Label locationLabel;

    @FXML
    private Label vaultIdLabel;

    @FXML
    private Label formatVersionLabel;

    @FXML
    private Label createdLabel;

    @FXML
    private Label appVersionLabel;

    @FXML
    private PasswordField currentPasswordField;

    @FXML
    private PasswordField newPasswordField;

    @FXML
    private PasswordField confirmNewPasswordField;

    @FXML
    private Label passwordFeedbackLabel;

    @FXML
    private Button changePasswordButton;

    private final VaultService vaultService = new VaultService();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        appVersionLabel.setText(App.version());

        if (!VaultSessionService.isOpen()) {
            return;
        }

        VaultContext vault = VaultSessionService.current();
        locationLabel.setText(vault.root().toString());
        vaultIdLabel.setText(vault.vaultId());
        formatVersionLabel.setText("Version " + vault.formatVersion());
        createdLabel.setText(Formats.dateTime(vault.createdAt()));
    }

    @FXML
    private void changeVaultPassword() {
        String currentPassword = currentPasswordField.getText();
        String newPassword = newPasswordField.getText();

        if (currentPassword.isEmpty()
                || newPassword.isEmpty()
                || confirmNewPasswordField.getText().isEmpty()) {
            showError("Please complete all password fields.");
            return;
        }

        if (newPassword.length() < VaultService.MIN_PASSWORD_LENGTH) {
            showError("The new vault password must contain at least 12 characters.");
            return;
        }

        if (!newPassword.equals(confirmNewPasswordField.getText())) {
            showError("The new vault passwords do not match.");
            return;
        }

        if (currentPassword.equals(newPassword)) {
            showError("The new vault password must be different.");
            return;
        }

        char[] currentChars = currentPassword.toCharArray();
        char[] newChars = newPassword.toCharArray();
        currentPasswordField.clear();
        newPasswordField.clear();
        confirmNewPasswordField.clear();
        changePasswordButton.setDisable(true);

        Background.run(
                () -> {
                    try {
                        vaultService.changeVaultPassword(currentChars, newChars);
                        return null;
                    } finally {
                        Arrays.fill(currentChars, '\0');
                        Arrays.fill(newChars, '\0');
                    }
                },
                done -> {
                    changePasswordButton.setDisable(false);
                    showSuccess("Vault password changed. Use the new password next time you open this vault.");
                },
                failure -> {
                    changePasswordButton.setDisable(false);
                    showError(failure instanceof VaultException vaultError
                            && vaultError.getReason() == VaultException.Reason.UNLOCK_FAILED
                            ? "The current vault password is incorrect."
                            : "Could not change the vault password. Please try again.");
                }
        );
    }

    @FXML
    private void closeVault() {
        try {
            App.closeVault();
        } catch (IOException e) {
            showError("Could not return to vault selection.");
        }
    }

    private void showError(String message) {
        passwordFeedbackLabel.setText(message);
        passwordFeedbackLabel.getStyleClass().remove("success");
    }

    private void showSuccess(String message) {
        passwordFeedbackLabel.setText(message);

        if (!passwordFeedbackLabel.getStyleClass().contains("success")) {
            passwordFeedbackLabel.getStyleClass().add("success");
        }
    }
}
