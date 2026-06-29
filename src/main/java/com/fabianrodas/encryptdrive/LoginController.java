package com.fabianrodas.encryptdrive;

import com.fabianrodas.controllers.UserController;
import com.fabianrodas.models.User;
import com.fabianrodas.services.SessionService;
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
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.scene.layout.VBox;
import com.fabianrodas.utils.WindowDragHandler;

/**
 * FXML Controller class
 * 
 * @author Fabian Rodas
 */

public class LoginController implements Initializable {

    @FXML
    private BorderPane root;
    
    @FXML
    private VBox formCard;

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private TextField visiblePasswordField;

    @FXML
    private Button togglePasswordButton;

    @FXML
    private Label feedbackLabel;

    private final UserController userController = new UserController();
    
    private final WindowDragHandler windowDragHandler
        = new WindowDragHandler();
    
    private boolean passwordVisible = false;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        visiblePasswordField.textProperty()
                .bindBidirectional(passwordField.textProperty());
        
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
    private void login() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isBlank()) {
            showError("Enter username and password to continue.");
            return;
        }

        User user = userController.authenticate(username, password);

        if (user == null) {
            if (userController.getLastError().isEmpty()) {
                showError("Invalid username or password.");
            } else {
                showError("Could not access the local user database.");
            }

            return;
        }

        try {
            SessionService.startSession(user);

            passwordField.clear();
            App.setRoot("dashboard");

        } catch (IOException e) {
            showError("Could not open the dashboard.");
        }
    }

    @FXML
    private void openRegister() {
        try {
            App.setRoot("register");
        } catch (IOException e) {
            showError("Could not open the registration screen.");
        }
    }

    private void configureResponsiveForm() {
        DoubleBinding formWidth = Bindings.createDoubleBinding(
                () -> Math.max(
                        410,
                        Math.min(560, root.getWidth() * 0.34)
                ),
                root.widthProperty()
        );

        formCard.prefWidthProperty().bind(formWidth);
    }

    private void showError(String message) {
        feedbackLabel.setText(message);
        feedbackLabel.getStyleClass().remove("success");
    }

    private Stage getStage() {
        if (root == null || root.getScene() == null) {
            return null;
        }

        return (Stage) root.getScene().getWindow();
    }
}