package com.fabianrodas.encryptdrive;

/**
 *
 * @author Fabian
 */

import java.io.IOException;
import java.net.URL;
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

public class RegisterController implements Initializable {

    @FXML
    private BorderPane root;

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

    private double xOffset;
    private double yOffset;
    private boolean passwordVisible = false;
    private boolean confirmPasswordVisible = false;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        visiblePasswordField.textProperty()
                .bindBidirectional(passwordField.textProperty());

        visibleConfirmPasswordField.textProperty()
                .bindBidirectional(confirmPasswordField.textProperty());
    }

    @FXML
    private void beginDrag(MouseEvent event) {
        xOffset = event.getSceneX();
        yOffset = event.getSceneY();
    }

    @FXML
    private void dragWindow(MouseEvent event) {
        Stage stage = getStage();

        if (stage != null) {
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        }
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

        if (username.length() < 3) {
            showError("Username must contain at least 3 characters.");
            return;
        }

        if (password.length() < 8) {
            showError("Password must contain at least 8 characters.");
            return;
        }

        if (!password.equals(confirmPassword)) {
            showError("Passwords do not match.");
            return;
        }

        showSuccess("Information validated. Ready to create the account.");
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

    private void showSuccess(String message) {
        feedbackLabel.setText(message);

        if (!feedbackLabel.getStyleClass().contains("success")) {
            feedbackLabel.getStyleClass().add("success");
        }
    }

    private Stage getStage() {
        if (root == null || root.getScene() == null) {
            return null;
        }
        return (Stage) root.getScene().getWindow();
    }
}