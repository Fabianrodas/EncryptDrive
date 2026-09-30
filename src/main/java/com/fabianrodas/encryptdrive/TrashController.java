package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.FileServiceException;
import com.fabianrodas.services.SessionService;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

/**
 * FXML Controller class
 *
 * @author Fabian Rodas
 */

public class TrashController implements Initializable {

    @FXML
    private VBox root;

    @FXML
    private Button restoreButton;

    @FXML
    private Button deleteButton;

    @FXML
    private Label feedbackLabel;

    @FXML
    private TableView<ManifestEntry> table;

    @FXML
    private TableColumn<ManifestEntry, String> nameColumn;

    @FXML
    private TableColumn<ManifestEntry, String> typeColumn;

    @FXML
    private TableColumn<ManifestEntry, String> deletedColumn;

    private FileService files;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        column(nameColumn, ManifestEntry::getName);
        column(typeColumn, Formats::type);
        column(deletedColumn, entry -> Formats.dateTime(entry.getDeletedAt()));

        table.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<ManifestEntry>) change -> updateActions()
        );

        if (SessionService.isActive()) {
            files = FileService.forCurrentSession();
            refresh();
        }
    }

    @FXML
    private void restore() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        try {
            for (ManifestEntry entry : selected) {
                files.restore(entry.getEntryId());
            }

            refresh();
            showSuccess(selected.size() == 1
                    ? "\"" + selected.get(0).getName() + "\" was restored."
                    : selected.size() + " items were restored.");

        } catch (FileServiceException e) {
            refresh();
            showError(FilesController.describe(e));
        }
    }

    private void refresh() {
        try {
            table.getItems().setAll(files.listTrash());
        } catch (FileServiceException e) {
            showError(FilesController.describe(e));
        }

        updateActions();
    }

    private void updateActions() {
        restoreButton.setDisable(files == null || table.getSelectionModel().getSelectedItems().isEmpty());
    }

    private void showError(String message) {
        feedbackLabel.getStyleClass().remove("success");
        feedbackLabel.setText(message);
    }

    private void showSuccess(String message) {
        if (!feedbackLabel.getStyleClass().contains("success")) {
            feedbackLabel.getStyleClass().add("success");
        }

        feedbackLabel.setText(message);
    }

    private static void column(
            TableColumn<ManifestEntry, String> column,
            Function<ManifestEntry, String> value
    ) {
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
    }
}
