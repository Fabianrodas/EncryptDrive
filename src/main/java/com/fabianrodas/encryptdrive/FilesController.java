package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.FileServiceException;
import com.fabianrodas.services.SessionService;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * FXML Controller class. Browses the signed-in user's logical folders;
 * files are never opened as temporary plaintext.
 *
 * @author Fabian Rodas
 */

public class FilesController implements Initializable {

    @FXML
    private VBox root;

    @FXML
    private HBox breadcrumbBar;

    @FXML
    private Button newFolderButton;

    @FXML
    private Button importButton;

    @FXML
    private Button exportButton;

    @FXML
    private Button trashButton;

    @FXML
    private Label feedbackLabel;

    @FXML
    private TableView<ManifestEntry> table;

    @FXML
    private TableColumn<ManifestEntry, String> nameColumn;

    @FXML
    private TableColumn<ManifestEntry, String> typeColumn;

    @FXML
    private TableColumn<ManifestEntry, String> sizeColumn;

    @FXML
    private TableColumn<ManifestEntry, String> modifiedColumn;

    private FileService files;
    private UUID currentFolderId;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        column(nameColumn, ManifestEntry::getName);
        column(typeColumn, Formats::type);
        column(sizeColumn, entry -> entry.getKind() == ManifestEntryKind.FILE
                ? Formats.bytes(entry.getPlainSize())
                : "—");
        column(modifiedColumn, entry -> Formats.dateTime(entry.getModifiedAt()));

        table.setRowFactory(view -> {
            TableRow<ManifestEntry> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    open(row.getItem());
                }
            });
            return row;
        });

        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<ManifestEntry>) change -> updateActions()
        );

        if (!SessionService.isActive()) {
            return;
        }

        files = FileService.forCurrentSession();

        try {
            currentFolderId = files.rootFolderId();
            refresh();
        } catch (FileServiceException e) {
            showError(describe(e));
        }
    }

    @FXML
    private void newFolder() {
        Optional<String> name = DialogFactory.prompt(
                root.getScene().getWindow(),
                "New folder",
                "Enter a name for the new folder.",
                "",
                "Create"
        );

        if (name.isEmpty()) {
            return;
        }

        try {
            files.createFolder(name.get(), currentFolderId);
            refresh();
            showSuccess("Folder created.");
        } catch (FileServiceException e) {
            showError(describe(e));
        }
    }

    @FXML
    private void moveToTrash() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        if (selected.isEmpty()) {
            return;
        }

        try {
            for (ManifestEntry entry : selected) {
                files.moveToTrash(entry.getEntryId());
            }

            refresh();
            showSuccess(selected.size() == 1
                    ? "\"" + selected.get(0).getName() + "\" moved to the trash."
                    : selected.size() + " items moved to the trash.");

        } catch (FileServiceException e) {
            refresh();
            showError(describe(e));
        }
    }

    static String describe(FileServiceException e) {
        return switch (e.getReason()) {
            case INVALID_NAME -> "Names cannot be empty, \".\" or \"..\", or longer than 255 characters.";
            case DUPLICATE_NAME -> "An item with that name already exists in this folder.";
            case NOT_FOUND -> "The item no longer exists.";
            case NOT_A_FOLDER -> "Items can only be placed inside folders.";
            case PROTECTED -> "Your top-level folder cannot be removed.";
            case NOT_IN_TRASH -> "Only items in the trash can be deleted permanently.";
            case SOURCE_UNREADABLE -> "The selected file could not be read.";
            case INTEGRITY -> "The encrypted data failed verification, so nothing was exported.";
            case CORRUPTED -> "Your encrypted file list could not be read.";
            case STORAGE -> "The vault folder could not be written.";
        };
    }

    private void open(ManifestEntry entry) {
        if (entry.getKind() == ManifestEntryKind.FOLDER) {
            navigateTo(entry.getEntryId());
        } else {
            showSuccess("Use Export to save a decrypted copy of \"" + entry.getName() + "\".");
        }
    }

    private void navigateTo(UUID folderId) {
        currentFolderId = folderId;
        feedbackLabel.setText("");
        refresh();
    }

    private void refresh() {
        try {
            table.getItems().setAll(files.listChildren(currentFolderId));
            renderBreadcrumbs(files.pathTo(currentFolderId));
        } catch (FileServiceException e) {
            showError(describe(e));
        }

        updateActions();
    }

    private void renderBreadcrumbs(List<ManifestEntry> path) {
        breadcrumbBar.getChildren().clear();

        for (int i = 0; i < path.size(); i++) {
            ManifestEntry folder = path.get(i);
            String text = i == 0 ? "My files" : folder.getName();

            if (i == path.size() - 1) {
                Label current = new Label(text);
                current.getStyleClass().add("breadcrumb-current");
                breadcrumbBar.getChildren().add(current);
            } else {
                Button link = new Button(text);
                link.getStyleClass().add("breadcrumb-button");
                link.setMnemonicParsing(false);
                link.setOnAction(event -> navigateTo(folder.getEntryId()));

                Label separator = new Label("›");
                separator.getStyleClass().add("breadcrumb-separator");
                breadcrumbBar.getChildren().addAll(link, separator);
            }
        }
    }

    private void updateActions() {
        boolean nothingSelected = table.getSelectionModel().getSelectedItems().isEmpty();
        trashButton.setDisable(files == null || nothingSelected);
        newFolderButton.setDisable(files == null);
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
