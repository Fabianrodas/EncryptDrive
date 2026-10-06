package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.ManifestEntry;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
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

    @FXML
    private Button cancelButton;

    @FXML
    private StackPane iconBadge;

    @FXML
    private Label iconLabel;

    @FXML
    private TextField inputField;

    @FXML
    private TreeView<ManifestEntry> folderTree;

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

    /** Red icon and confirm button for actions that cannot be undone. */
    void setDestructive() {
        iconBadge.getStyleClass().add("danger");
        confirmButton.getStyleClass().setAll("button", "popup-danger-button");
    }

    /** A message only: an "i" badge and no Cancel button. */
    void setInformational() {
        iconLabel.setText("i");
        iconBadge.getStyleClass().add("info");
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
    }

    /** Shows a text field for the user's answer, pre-filled and focused. */
    void setPrompt(String initialValue) {
        iconLabel.setText("+");
        iconBadge.getStyleClass().add("info");
        inputField.setText(initialValue);
        inputField.setVisible(true);
        inputField.setManaged(true);
        Platform.runLater(inputField::requestFocus);
    }

    String getInput() {
        return inputField.getText();
    }

    /** Shows a folder tree; Confirm stays disabled until a folder is selected. */
    void setFolderChoice(TreeItem<ManifestEntry> root) {
        iconLabel.setText("→");
        iconBadge.getStyleClass().add("info");
        folderTree.setRoot(root);
        folderTree.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(ManifestEntry folder, boolean empty) {
                super.updateItem(folder, empty);
                setText(empty || folder == null ? null
                        : folder.getParentId() == null ? "My files" : folder.getName());
            }
        });
        folderTree.setVisible(true);
        folderTree.setManaged(true);
        confirmButton.disableProperty().bind(folderTree.getSelectionModel().selectedItemProperty().isNull());
    }

    ManifestEntry getChosenFolder() {
        TreeItem<ManifestEntry> item = folderTree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    BorderPane root() {
        return root;
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
