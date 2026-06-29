package com.fabianrodas.encryptdrive;

import com.fabianrodas.controllers.UserController;
import com.fabianrodas.models.User;
import com.fabianrodas.services.SessionService;
import java.io.IOException;
import java.net.URL;
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

    private final UserController userController = new UserController();

    private double xOffset;
    private double yOffset;

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
        User currentUser = SessionService.getCurrentUser();

        if (currentUser == null) {
            showPasswordError("Your session has expired. Please log in again.");
            return;
        }

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

        int result = userController.changePassword(
                currentUser.getId(),
                currentPassword,
                newPassword
        );

        if (result == UserController.SUCCESS) {
            currentPasswordField.clear();
            newPasswordField.clear();
            confirmNewPasswordField.clear();

            showPasswordSuccess("Password updated successfully.");
            return;
        }

        if (result == UserController.INCORRECT_CURRENT_PASSWORD) {
            showPasswordError("Your current password is incorrect.");
            return;
        }

        if (result == UserController.USER_NOT_FOUND) {
            showPasswordError("Your account could not be found.");
            return;
        }

        showPasswordError("Could not update your password. Please try again.");
    }

    @FXML
    private void logout() {
        try {
            SessionService.closeSession();
            App.setRoot("login");

        } catch (IOException e) {
            System.err.println("Could not return to the login screen.");
        }
    }

    @FXML
    private void beginDrag(MouseEvent event) {
        xOffset = event.getSceneX();
        yOffset = event.getSceneY();
    }

    @FXML
    private void dragWindow(MouseEvent event) {
        Stage stage = getStage();

        if (stage != null && !stage.isMaximized()) {
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
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
    private void close() {
        Stage stage = getStage();

        if (stage != null) {
            stage.close();
        }
    }

    private void loadUserInformation() {
        User currentUser = SessionService.getCurrentUser();

        if (currentUser == null) {
            welcomeLabel.setText("Welcome to EncryptDrive");
            return;
        }

        String fullName = currentUser.getFullName();
        String username = currentUser.getUsername();
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