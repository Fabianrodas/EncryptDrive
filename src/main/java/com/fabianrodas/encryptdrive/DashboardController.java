package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.User;
import com.fabianrodas.services.SessionService;
import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class DashboardController implements Initializable {

    @FXML
    private BorderPane root;

    @FXML
    private Label welcomeLabel;

    private double xOffset;
    private double yOffset;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        User currentUser = SessionService.getCurrentUser();

        if (currentUser != null) {
            welcomeLabel.setText(
                    "Welcome, " + currentUser.getFullName() + "!"
            );
        } else {
            welcomeLabel.setText("Welcome to EncryptDrive");
        }
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

    private Stage getStage() {
        if (root == null || root.getScene() == null) {
            return null;
        }

        return (Stage) root.getScene().getWindow();
    }
}