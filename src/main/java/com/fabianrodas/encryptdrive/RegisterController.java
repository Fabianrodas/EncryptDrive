package com.fabianrodas.encryptdrive;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import com.fabianrodas.services.AuthException;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.VaultSessionService;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.scene.layout.VBox;
import com.fabianrodas.utils.WindowDragHandler;

/**
 * FXML Controller class
 * 
 * @author Fabian Rodas
 */

public class RegisterController implements Initializable {

    @FXML
    private BorderPane root;
    
    @FXML
    private VBox formCard;

    @FXML
    private TextField fullNameField;

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private PasswordField confirmPasswordField;
    
    @FXML
    private Button toggleConfirmPasswordButton;

    @FXML
    private TextField visiblePasswordField;

    @FXML
    private TextField visibleConfirmPasswordField;

    @FXML
    private Button togglePasswordButton;

    @FXML
    private Label feedbackLabel;

    @FXML
    private Label vaultNameLabel;

    private boolean passwordVisible = false;
    private boolean confirmPasswordVisible = false;

    private final WindowDragHandler windowDragHandler
        = new WindowDragHandler();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        visiblePasswordField.textProperty()
                .bindBidirectional(passwordField.textProperty());

        visibleConfirmPasswordField.textProperty()
                .bindBidirectional(confirmPasswordField.textProperty());

        String hint = "At least " + AuthService.MIN_PASSWORD_LENGTH + " characters";
        passwordField.setPromptText(hint);
        visiblePasswordField.setPromptText(hint);

        vaultNameLabel.setText(App.openVaultName());
        configureResponsiveForm();
    }

    @FXML
    private void beginDrag(MouseEvent event) {
        windowDragHandler.beginDrag(event, getStage());
    }

    @FXML
    private void dragWindow(MouseEvent event) {
        windowDragHandler.dragWindow(event, getStage());
    }

    @FXML
    private void close() {
        Stage stage = getStage();

        if (stage != null) {
            stage.close();
        }
    }

    @FXML
    private void minimize() {
        Stage stage = getStage();

        if (stage != null) {
            stage.setIconified(true);
        }
    }
    
    @FXML
    private void toggleMaximize() {
        App.toggleMaximize(getStage());
    }

    @FXML
    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;

        passwordField.setVisible(!passwordVisible);
        passwordField.setManaged(!passwordVisible);

        visiblePasswordField.setVisible(passwordVisible);
        visiblePasswordField.setManaged(passwordVisible);

        togglePasswordButton.setText(passwordVisible ? "Hide" : "View");

        if (passwordVisible) {
            visiblePasswordField.requestFocus();
            visiblePasswordField.positionCaret(
                    visiblePasswordField.getText().length()
            );
        } else {
            passwordField.requestFocus();
            passwordField.positionCaret(
                    passwordField.getText().length()
            );
        }
    }

    @FXML
    private void toggleConfirmPasswordVisibility() {
        confirmPasswordVisible = !confirmPasswordVisible;

        confirmPasswordField.setVisible(!confirmPasswordVisible);
        confirmPasswordField.setManaged(!confirmPasswordVisible);

        visibleConfirmPasswordField.setVisible(confirmPasswordVisible);
        visibleConfirmPasswordField.setManaged(confirmPasswordVisible);

        toggleConfirmPasswordButton.setText(
                confirmPasswordVisible ? "Hide" : "View"
        );

        if (confirmPasswordVisible) {
            visibleConfirmPasswordField.requestFocus();
            visibleConfirmPasswordField.positionCaret(
                    visibleConfirmPasswordField.getText().length()
            );
        } else {
            confirmPasswordField.requestFocus();
            confirmPasswordField.positionCaret(
                    confirmPasswordField.getText().length()
            );
        }
    }

    @FXML
    private void register() {
        String fullName = fullNameField.getText().trim();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        String confirmPassword = confirmPasswordField.getText();

        if (fullName.isEmpty() || username.isEmpty()
                || password.isBlank() || confirmPassword.isBlank()) {

            showError("Please complete all fields.");
            return;
        }

        if (username.length() < AuthService.MIN_USERNAME_LENGTH) {
            showError("Username must contain at least " + AuthService.MIN_USERNAME_LENGTH + " characters.");
            return;
        }

        if (password.length() < AuthService.MIN_PASSWORD_LENGTH) {
            showError("Password must contain at least " + AuthService.MIN_PASSWORD_LENGTH + " characters.");
            return;
        }

        if (!password.equals(confirmPassword)) {
            showError("Passwords do not match.");
            return;
        }

        if (!VaultSessionService.isOpen()) {
            showError("Open a vault before creating an account.");
            return;
        }

        char[] passwordChars = password.toCharArray();
        passwordField.clear();
        confirmPasswordField.clear();
        AuthService authService = new AuthService(VaultSessionService.current());
        formCard.setDisable(true);

        Background.run(
                () -> {
                    try {
                        return authService.register(fullName, username, passwordChars);
                    } finally {
                        Arrays.fill(passwordChars, '\0');
                    }
                },
                identity -> {
                    formCard.setDisable(false);
                    showRegistrationSuccessPopup();
                },
                failure -> {
                    formCard.setDisable(false);
                    showError(failure instanceof AuthException authError
                            && authError.getReason() == AuthException.Reason.USERNAME_TAKEN
                            ? "That username is already in use."
                            : "Could not create the account. Please try again.");
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

    @FXML
    private void goToLogin() {
        try {
            App.setRoot("login");
        } catch (IOException e) {
            showError("Could not open the login screen.");
        }
    }

    private void showError(String message) {
        feedbackLabel.setText(message);
        feedbackLabel.getStyleClass().remove("success");
    }

    private void showRegistrationSuccessPopup() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    App.class.getResource("success-popup.fxml")
            );

            Parent popupRoot = loader.load();

            SuccessPopupController popupController = loader.getController();

            Stage popupStage = new Stage();

            Stage owner = getStage();
            if (owner != null) {
                popupStage.initOwner(owner);
            }

            popupStage.initModality(Modality.APPLICATION_MODAL);
            popupStage.initStyle(StageStyle.UNDECORATED);
            popupStage.setResizable(false);
            popupStage.setScene(new Scene(popupRoot));

            popupStage.showAndWait();

            if (popupController.isGoToLoginRequested()) {
                App.setRoot("login");
            }

        } catch (IOException e) {
            showError("Account was created, but the login screen could not be opened.");
        }
    }

    private void configureResponsiveForm() {
        DoubleBinding formWidth = Bindings.createDoubleBinding(
                () -> Math.max(
                        480,
                        Math.min(640, root.getWidth() * 0.38)
                ),
                root.widthProperty()
        );

        formCard.prefWidthProperty().bind(formWidth);
    }

    private Stage getStage() {
        if (root == null || root.getScene() == null) {
            return null;
        }
        return (Stage) root.getScene().getWindow();
    }
}