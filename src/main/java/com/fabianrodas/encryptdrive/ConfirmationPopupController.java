package com.fabianrodas.encryptdrive;

import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class ConfirmationPopupController implements Initializable {

    @FXML
    private BorderPane root;

    @FXML
    private Label titleLabel;

    @FXML
    private Label messageLabel;

    @FXML
    private Button confirmButton;

    private boolean confirmed = false;
    private double xOffset = 0;
    private double yOffset = 0;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        root.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        root.setOnMouseDragged(event -> {
            Stage stage = (Stage) root.getScene().getWindow();
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });
    }

    void setContent(String title, String message, String confirmText) {
        titleLabel.setText(title);
        messageLabel.setText(message);
        confirmButton.setText(confirmText);
    }

    boolean isConfirmed() {
        return confirmed;
    }

    @FXML
    private void confirm() {
        confirmed = true;
        close();
    }

    @FXML
    private void cancel() {
        close();
    }

    private void close() {
        ((Stage) root.getScene().getWindow()).close();
    }
}
