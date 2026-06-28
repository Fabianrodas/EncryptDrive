package com.fabianrodas.encryptdrive;

import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class SuccessPopupController implements Initializable {

    @FXML
    private BorderPane root;
    private Stage stage;
    private double xOffset = 0;
    private double yOffset = 0;
    
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        root.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        root.setOnMouseDragged(event -> {
            stage = (Stage) root.getScene().getWindow();
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });
    }

    private boolean goToLoginRequested = false;

    @FXML
    private void closePopup() {
        stage = (Stage) root.getScene().getWindow();
        stage.close();
    }

    @FXML
    private void goToLogin() {
        goToLoginRequested = true;
        closePopup();
    }

    public boolean isGoToLoginRequested() {
        return goToLoginRequested;
    }
}