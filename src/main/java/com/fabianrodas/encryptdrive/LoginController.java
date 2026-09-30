package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.services.AuthException;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.SessionService;
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

        if (username.isEmpty() || passwordField.getText().isBlank()) {
            showError("Enter username and password to continue.");
            return;
        }

        if (!VaultSessionService.isOpen()) {
            showError("Open a vault before logging in.");
            return;
        }

        char[] password = passwordField.getText().toCharArray();
        passwordField.clear();

        try {
            UserLoginResult result = new AuthService(VaultSessionService.current())
                    .login(username, password);

            SessionService.start(result.identity(), result.userMasterKey());
            App.setRoot("dashboard");

        } catch (AuthException e) {
            showError(e.getReason() == AuthException.Reason.INVALID_CREDENTIALS
                    ? "Invalid username or password."
                    : "Could not read the accounts of this vault.");
        } catch (IOException e) {
            SessionService.logout();
            showError("Could not open the dashboard.");
        } finally {
            Arrays.fill(password, '\0');
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