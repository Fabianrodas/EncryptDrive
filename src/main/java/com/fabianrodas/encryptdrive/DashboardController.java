package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.utils.WindowDragHandler;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * FXML Controller class. Owns the sidebar and swaps workspace views into
 * {@code contentHost}; each view is loaded fresh so it shows current data.
 *
 * @author Fabian Rodas
 */

public class DashboardController implements Initializable {

    @FXML
    private BorderPane root;

    @FXML
    private VBox sidebar;

    @FXML
    private StackPane contentHost;

    @FXML
    private Button overviewNavButton;

    @FXML
    private Button filesNavButton;

    @FXML
    private Button trashNavButton;

    @FXML
    private Button profileNavButton;

    @FXML
    private Button settingsNavButton;

    @FXML
    private Label sidebarInitialsLabel;

    @FXML
    private Label sidebarFullNameLabel;

    @FXML
    private Label sidebarUsernameLabel;

    private final WindowDragHandler windowDragHandler
        = new WindowDragHandler();

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        configureResponsiveLayout();
        loadUserInformation();
        showOverview();
    }

    @FXML
    private void showOverview() {
        OverviewController overview = show("overview", overviewNavButton);

        if (overview != null) {
            overview.setOnOpenFiles(this::showFiles);
        }
    }

    @FXML
    private void showFiles() {
        show("files", filesNavButton);
    }

    @FXML
    private void showTrash() {
        show("trash", trashNavButton);
    }

    @FXML
    private void showProfile() {
        show("profile", profileNavButton);
    }

    @FXML
    private void showSettings() {
        show("vault-settings", settingsNavButton);
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
    private void closeVault() {
        try {
            App.closeVault();
        } catch (IOException e) {
            System.err.println("Could not return to vault selection.");
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

    private <T> T show(String fxml, Button navigation) {
        setActiveNavigation(navigation);

        try {
            FXMLLoader loader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
            contentHost.getChildren().setAll((Node) loader.load());
            return loader.getController();

        } catch (IOException e) {
            contentHost.getChildren().setAll(new Label("This view could not be opened."));
            return null;
        }
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
    }

    private void loadUserInformation() {
        if (!SessionService.isActive()) {
            return;
        }

        UserSessionIdentity user = SessionService.identity();

        sidebarInitialsLabel.setText(Formats.initials(user.fullName()));
        sidebarFullNameLabel.setText(user.fullName());
        sidebarUsernameLabel.setText("@" + user.username());
    }

    private void setActiveNavigation(Button activeButton) {
        for (Button button : List.of(
                overviewNavButton,
                filesNavButton,
                trashNavButton,
                profileNavButton,
                settingsNavButton
        )) {
            button.getStyleClass().remove("active");
        }

        activeButton.getStyleClass().add("active");
    }

    private Stage getStage() {
        if (root == null || root.getScene() == null) {
            return null;
        }

        return (Stage) root.getScene().getWindow();
    }
}
