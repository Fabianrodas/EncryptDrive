package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.FileServiceException;
import com.fabianrodas.services.RecoveryService;
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

    @FXML
    private void permanentlyDelete() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        if (selected.isEmpty()) {
            return;
        }

        boolean confirmed = DialogFactory.confirmDestructive(
                root.getScene() == null ? null : root.getScene().getWindow(),
                "Delete permanently?",
                "Permanently delete " + describeItems(selected)
                        + "? Recovery through EncryptDrive will no longer be possible.",
                "Delete permanently"
        );

        if (!confirmed) {
            return;
        }

        try {
            int pending = files.permanentlyDelete(
                    selected.stream().map(ManifestEntry::getEntryId).toList()
            );

            refresh();
            showSuccess((selected.size() == 1
                    ? "\"" + selected.get(0).getName() + "\" was deleted permanently."
                    : selected.size() + " items were deleted permanently.")
                    + (pending > 0 ? " " + Formats.CLEANUP_PENDING : ""));

        } catch (FileServiceException e) {
            refresh();
            showError(FilesController.describe(e));
        }
    }

    static String describeItems(List<ManifestEntry> items) {
        List<String> names = items.stream()
                .limit(3)
                .map(item -> "\"" + item.getName() + "\"")
                .toList();

        if (items.size() <= 3) {
            return names.size() == 1
                    ? names.get(0)
                    : String.join(", ", names.subList(0, names.size() - 1))
                            + " and " + names.get(names.size() - 1);
        }

        return String.join(", ", names) + " and " + (items.size() - 3) + " more items";
    }

    private void refresh() {
        try {
            table.getItems().setAll(files.listTrash());
        } catch (FileServiceException e) {
            showError(FilesController.describe(e));
        }

        if (RecoveryService.takeRecoveryNotice()) {
            feedbackLabel.getStyleClass().remove("success");
            feedbackLabel.getStyleClass().add("notice");
            feedbackLabel.setText(Formats.RECOVERY_NOTICE);
        }

        updateActions();
    }

    private void updateActions() {
        boolean nothingSelected = files == null
                || table.getSelectionModel().getSelectedItems().isEmpty();

        restoreButton.setDisable(nothingSelected);
        deleteButton.setDisable(nothingSelected);
    }

    private void showError(String message) {
        feedbackLabel.getStyleClass().removeAll("success", "notice");
        feedbackLabel.setText(message);
    }

    private void showSuccess(String message) {
        feedbackLabel.getStyleClass().remove("notice");

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
