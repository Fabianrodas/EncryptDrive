package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.FileServiceException;
import com.fabianrodas.services.RecoveryService;
import com.fabianrodas.services.SessionService;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyStringWrapper;
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
    private Button emptyTrashButton;

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

        if (!SessionService.isActive()) {
            emptyTrashButton.setDisable(true);
            restoreButton.setDisable(true);
            deleteButton.setDisable(true);
            return;
        }

        files = FileService.forCurrentSession();
        BooleanBinding unavailable = Bindings.isEmpty(table.getSelectionModel().getSelectedItems())
                .or(Background.busyProperty());
        emptyTrashButton.disableProperty().bind(Bindings.isEmpty(table.getItems()).or(Background.busyProperty()));
        restoreButton.disableProperty().bind(unavailable);
        deleteButton.disableProperty().bind(unavailable);
        table.disableProperty().bind(Background.busyProperty());
        refresh();
    }

    @FXML
    private void restore() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        showWorking("Restoring…");
        Background.run(() -> {
            for (ManifestEntry entry : selected) {
                files.restore(entry.getEntryId());
            }

            return null;
        }, done -> {
            refresh();
            showSuccess(selected.size() == 1
                    ? "\"" + selected.get(0).getName() + "\" was restored."
                    : selected.size() + " items were restored.");
        }, failure -> {
            refresh();
            showError(FilesController.describe(failure));
        });
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

        List<UUID> ids = selected.stream().map(ManifestEntry::getEntryId).toList();
        String deleted = selected.size() == 1
                ? "\"" + selected.get(0).getName() + "\" was deleted permanently."
                : selected.size() + " items were deleted permanently.";

        showWorking("Deleting permanently…");
        Background.run(() -> files.permanentlyDelete(ids), pending -> {
            refresh();
            showSuccess(deleted + (pending > 0 ? " " + Formats.CLEANUP_PENDING : ""));
        }, failure -> deleteFailed(failure, ids, deleted));
    }

    @FXML
    private void emptyTrash() {
        // The trash view lists the items the user trashed; what lies inside a trashed folder is not counted.
        int count = table.getItems().size();

        if (count == 0 || !DialogFactory.confirmDestructive(
                root.getScene() == null ? null : root.getScene().getWindow(),
                "Empty the trash?",
                "Permanently delete all " + count + (count == 1 ? " item" : " items")
                        + " in the trash? Recovery through EncryptDrive will no longer be possible.",
                "Empty Trash"
        )) {
            return;
        }

        List<UUID> ids = table.getItems().stream().map(ManifestEntry::getEntryId).toList();

        showWorking("Emptying the trash…");
        Background.run(files::emptyTrash, pending -> {
            refresh();
            showSuccess("The trash was emptied." + (pending > 0 ? " " + Formats.CLEANUP_PENDING : ""));
        }, failure -> deleteFailed(failure, ids, "The trash was emptied."));
    }

    /**
     * A delete that failed may still have removed the entries: the manifest is written first, then the
     * backups, and only then the blobs. When the entries are gone, the user is told what is left to do
     * instead of that the vault could not be written.
     */
    private void deleteFailed(Throwable failure, List<UUID> ids, String deleted) {
        refresh(trash -> {
            if (onlyCleanupLeft(failure, ids, trash)) {
                showSuccess(deleted + " " + Formats.CLEANUP_PENDING);
            } else {
                showError(FilesController.describe(failure));
            }
        });
    }

    /** A storage failure that left none of the targeted entries in the trash: only cleanup is left, and it retries. */
    static boolean onlyCleanupLeft(Throwable failure, Collection<UUID> targeted, List<ManifestEntry> trash) {
        return failure instanceof FileServiceException error
                && error.getReason() == FileServiceException.Reason.STORAGE
                && trash.stream().noneMatch(item -> targeted.contains(item.getEntryId()));
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
        refresh(items -> { });
    }

    /** Reloads the trash, then lets {@code then} look at what is listed now. */
    private void refresh(Consumer<List<ManifestEntry>> then) {
        Background.read(files::listTrash, items -> {
            table.getItems().setAll(items);
            then.accept(items);

            // A view the user already left must leave the one-shot notice for the visible one.
            if (root.getScene() != null && RecoveryService.takeRecoveryNotice()) {
                feedbackLabel.getStyleClass().remove("success");
                feedbackLabel.getStyleClass().add("notice");
                feedbackLabel.setText(Formats.RECOVERY_NOTICE);
            }
        }, failure -> showError(FilesController.describe(failure)));
    }

    private void showError(String message) {
        feedbackLabel.getStyleClass().removeAll("success", "notice");
        feedbackLabel.setText(message);
    }

    /** Says what the locked view is waiting for; the result of the operation replaces it. */
    private void showWorking(String message) {
        feedbackLabel.getStyleClass().remove("success");

        if (!feedbackLabel.getStyleClass().contains("notice")) {
            feedbackLabel.getStyleClass().add("notice");
        }

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
