package com.fabianrodas.encryptdrive;

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
import com.fabianrodas.controllers.UserController;
import com.fabianrodas.models.User;
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

    private boolean passwordVisible = false;
    private boolean confirmPasswordVisible = false;
    
    private final UserController userController = new UserController();
    
    private final WindowDragHandler windowDragHandler
        = new WindowDragHandler();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        visiblePasswordField.textProperty()
                .bindBidirectional(passwordField.textProperty());

        visibleConfirmPasswordField.textProperty()
                .bindBidirectional(confirmPasswordField.textProperty());
        
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

        User user = new User(fullName, username, password);

        int result = userController.create(user);

        if (result == UserController.SUCCESS) {
            showRegistrationSuccessPopup();
            return;
        }

        if (result == UserController.USERNAME_ALREADY_EXISTS) {
            showError("That username is already in use.");
            return;
        }

        showError("Could not create the account. Please try again.");
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