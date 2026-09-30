package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.services.AuthException;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultSessionService;
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

public class ProfileController implements Initializable {

    @FXML
    private Label initialsLabel;

    @FXML
    private Label fullNameLabel;

    @FXML
    private Label usernameLabel;

    @FXML
    private Label fullNameDetailLabel;

    @FXML
    private Label usernameDetailLabel;

    @FXML
    private PasswordField currentPasswordField;

    @FXML
    private PasswordField newPasswordField;

    @FXML
    private PasswordField confirmNewPasswordField;

    @FXML
    private Label passwordFeedbackLabel;

    @FXML
    private Button updatePasswordButton;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        if (!SessionService.isActive()) {
            return;
        }

        UserSessionIdentity user = SessionService.identity();

        initialsLabel.setText(Formats.initials(user.fullName()));
        fullNameLabel.setText(user.fullName());
        usernameLabel.setText("@" + user.username());
        fullNameDetailLabel.setText(user.fullName());
        usernameDetailLabel.setText(user.username());
    }

    @FXML
    private void changePassword() {
        if (!SessionService.isActive() || !VaultSessionService.isOpen()) {
            showError("Your session has expired. Please log in again.");
            return;
        }

        String currentPassword = currentPasswordField.getText();
        String newPassword = newPasswordField.getText();

        if (currentPassword.isBlank()
                || newPassword.isBlank()
                || confirmNewPasswordField.getText().isBlank()) {
            showError("Please complete all password fields.");
            return;
        }

        if (newPassword.length() < AuthService.MIN_PASSWORD_LENGTH) {
            showError("Your new password must contain at least 8 characters.");
            return;
        }

        if (!newPassword.equals(confirmNewPasswordField.getText())) {
            showError("The new passwords do not match.");
            return;
        }

        if (currentPassword.equals(newPassword)) {
            showError("Your new password must be different.");
            return;
        }

        char[] currentChars = currentPassword.toCharArray();
        char[] newChars = newPassword.toCharArray();
        currentPasswordField.clear();
        newPasswordField.clear();
        confirmNewPasswordField.clear();

        AuthService authService = new AuthService(VaultSessionService.current());
        UserSessionIdentity user = SessionService.identity();
        updatePasswordButton.setDisable(true);

        Background.run(
                () -> {
                    try {
                        authService.changePassword(user.userId(), currentChars, newChars);
                        return null;
                    } finally {
                        Arrays.fill(currentChars, '\0');
                        Arrays.fill(newChars, '\0');
                    }
                },
                done -> {
                    updatePasswordButton.setDisable(false);
                    showSuccess("Password updated successfully.");
                },
                failure -> {
                    updatePasswordButton.setDisable(false);
                    showError(failure instanceof AuthException authError
                            && authError.getReason() == AuthException.Reason.INVALID_CREDENTIALS
                            ? "Your current password is incorrect."
                            : "Could not update your password. Please try again.");
                }
        );
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
