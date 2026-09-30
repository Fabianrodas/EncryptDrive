package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.services.AuthException;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultSessionService;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import com.fabianrodas.utils.WindowDragHandler;

/**
 * FXML Controller class
 * 
 * @author Fabian Rodas
 */

public class DashboardController implements Initializable {

    @FXML
    private BorderPane root;

    @FXML
    private VBox sidebar;

    @FXML
    private VBox workspaceContent;

    @FXML
    private ScrollPane workspaceScrollPane;

    @FXML
    private VBox dashboardView;

    @FXML
    private VBox profileView;

    @FXML
    private Button overviewNavButton;

    @FXML
    private Button profileNavButton;

    @FXML
    private Label welcomeLabel;

    @FXML
    private Label sidebarInitialsLabel;

    @FXML
    private Label sidebarFullNameLabel;

    @FXML
    private Label sidebarUsernameLabel;

    @FXML
    private Label profileInitialsLabel;

    @FXML
    private Label profileFullNameLabel;

    @FXML
    private Label profileUsernameLabel;

    @FXML
    private Label profileFullNameDetailLabel;

    @FXML
    private Label profileUsernameDetailLabel;

    @FXML
    private PasswordField currentPasswordField;

    @FXML
    private PasswordField newPasswordField;

    @FXML
    private PasswordField confirmNewPasswordField;

    @FXML
    private Label passwordFeedbackLabel;

    private final WindowDragHandler windowDragHandler
        = new WindowDragHandler();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configureResponsiveLayout();
        loadUserInformation();
        showDashboard();
    }

    private void configureResponsiveLayout() {
        DoubleBinding sidebarWidth = Bindings.createDoubleBinding(
                () -> Math.max(
                        230,
                        Math.min(290, root.getWidth() * 0.17)
                ),
                root.widthProperty()
        );

        sidebar.minWidthProperty().bind(sidebarWidth);
        sidebar.prefWidthProperty().bind(sidebarWidth);
        sidebar.maxWidthProperty().bind(sidebarWidth);

        workspaceContent.minHeightProperty().bind(
                Bindings.max(
                        0,
                        workspaceScrollPane.heightProperty().subtract(2)
                )
        );
    }

    @FXML
    private void showDashboard() {
        dashboardView.setVisible(true);
        dashboardView.setManaged(true);

        profileView.setVisible(false);
        profileView.setManaged(false);

        setActiveNavigation(overviewNavButton);
        scrollWorkspaceToTop();
    }

    @FXML
    private void showProfile() {
        dashboardView.setVisible(false);
        dashboardView.setManaged(false);

        profileView.setVisible(true);
        profileView.setManaged(true);

        passwordFeedbackLabel.setText("");
        passwordFeedbackLabel.getStyleClass().remove("success");

        setActiveNavigation(profileNavButton);
        scrollWorkspaceToTop();
    }

    @FXML
    private void changePassword() {
        if (!SessionService.isActive() || !VaultSessionService.isOpen()) {
            showPasswordError("Your session has expired. Please log in again.");
            return;
        }

        UserSessionIdentity currentUser = SessionService.identity();

        String currentPassword = currentPasswordField.getText();
        String newPassword = newPasswordField.getText();
        String confirmNewPassword = confirmNewPasswordField.getText();

        if (currentPassword.isBlank()
                || newPassword.isBlank()
                || confirmNewPassword.isBlank()) {

            showPasswordError("Please complete all password fields.");
            return;
        }

        if (newPassword.length() < 8) {
            showPasswordError("Your new password must contain at least 8 characters.");
            return;
        }

        if (!newPassword.equals(confirmNewPassword)) {
            showPasswordError("The new passwords do not match.");
            return;
        }

        if (currentPassword.equals(newPassword)) {
            showPasswordError("Your new password must be different.");
            return;
        }

        char[] currentChars = currentPassword.toCharArray();
        char[] newChars = newPassword.toCharArray();
        currentPasswordField.clear();
        newPasswordField.clear();
        confirmNewPasswordField.clear();

        try {
            new AuthService(VaultSessionService.current())
                    .changePassword(currentUser.userId(), currentChars, newChars);
            showPasswordSuccess("Password updated successfully.");

        } catch (AuthException e) {
            showPasswordError(e.getReason() == AuthException.Reason.INVALID_CREDENTIALS
                    ? "Your current password is incorrect."
                    : "Could not update your password. Please try again.");
        } finally {
            Arrays.fill(currentChars, '\0');
            Arrays.fill(newChars, '\0');
        }
    }

    @FXML
    private void logout() {
        try {
            SessionService.logout();
            App.setRoot("login");

        } catch (IOException e) {
            System.err.println("Could not return to the login screen.");
        }
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
    private void close() {
        Stage stage = getStage();

        if (stage != null) {
            stage.close();
        }
    }

    private void loadUserInformation() {
        if (!SessionService.isActive()) {
            welcomeLabel.setText("Welcome to EncryptDrive");
            return;
        }

        UserSessionIdentity currentUser = SessionService.identity();

        String fullName = currentUser.fullName();
        String username = currentUser.username();
        String initials = getInitials(fullName);

        welcomeLabel.setText("Welcome back, " + fullName + "!");

        sidebarInitialsLabel.setText(initials);
        sidebarFullNameLabel.setText(fullName);
        sidebarUsernameLabel.setText("@" + username);

        profileInitialsLabel.setText(initials);
        profileFullNameLabel.setText(fullName);
        profileUsernameLabel.setText("@" + username);

        profileFullNameDetailLabel.setText(fullName);
        profileUsernameDetailLabel.setText(username);
    }

    private void setActiveNavigation(Button activeButton) {
        overviewNavButton.getStyleClass().remove("active");
        profileNavButton.getStyleClass().remove("active");

        if (!activeButton.getStyleClass().contains("active")) {
            activeButton.getStyleClass().add("active");
        }
    }

    private void scrollWorkspaceToTop() {
        Platform.runLater(() -> workspaceScrollPane.setVvalue(0));
    }

    private String getInitials(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "U";
        }

        String[] parts = fullName.trim().split("\\s+");

        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase();
        }

        String firstInitial = parts[0].substring(0, 1);
        String lastInitial = parts[parts.length - 1].substring(0, 1);

        return (firstInitial + lastInitial).toUpperCase();
    }

    private void showPasswordError(String message) {
        passwordFeedbackLabel.setText(message);
        passwordFeedbackLabel.getStyleClass().remove("success");
    }

    private void showPasswordSuccess(String message) {
        passwordFeedbackLabel.setText(message);

        if (!passwordFeedbackLabel.getStyleClass().contains("success")) {
            passwordFeedbackLabel.getStyleClass().add("success");
        }
    }

    private Stage getStage() {
        if (root == null || root.getScene() == null) {
            return null;
        }

        return (Stage) root.getScene().getWindow();
    }
}