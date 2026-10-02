package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.FileServiceException;
import com.fabianrodas.services.RecoveryService;
import com.fabianrodas.services.SessionService;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * FXML Controller class. Browses the signed-in user's logical folders and
 * runs imports and exports in the background. Files are never opened as
 * temporary plaintext; Export is the only way to write a decrypted copy.
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
    private HBox progressBox;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private Label progressLabel;

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
    private boolean busy = false;

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
                (ListChangeListener<ManifestEntry>) change -> updateActions()
        );

        if (!SessionService.isActive()) {
            updateActions();
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
                window(),
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
    private void importFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import files into EncryptDrive");
        List<File> chosen = chooser.showOpenMultipleDialog(window());

        if (chosen != null && !chosen.isEmpty()) {
            importFiles(chosen.stream().map(File::toPath).toList());
        }
    }

    /** Encrypts copies of the sources into the current folder; sources are left untouched. */
    void importFiles(List<Path> sources) {
        UUID target = currentFolderId;
        long total = Math.max(1, sources.stream().mapToLong(FilesController::sizeOf).sum());

        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() {
                List<String> failures = new ArrayList<>();
                long done = 0;

                for (Path source : sources) {
                    long before = done;
                    updateMessage("Encrypting " + source.getFileName() + "...");

                    try {
                        files.importFile(source, target, bytes -> updateProgress(before + bytes, total));
                    } catch (FileServiceException e) {
                        failures.add(source.getFileName() + " (" + describe(e) + ")");
                    }

                    done = before + sizeOf(source);
                    updateProgress(done, total);
                }

                return failures;
            }
        };

        runWithProgress(task, failures -> {
            int imported = sources.size() - failures.size();

            if (failures.isEmpty()) {
                showSuccess(imported == 1
                        ? "1 file imported and encrypted."
                        : imported + " files imported and encrypted.");
            } else {
                showError((imported > 0 ? imported + " imported. " : "")
                        + "Not imported: " + String.join("; ", failures));
            }
        });
    }

    @FXML
    private void export() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        if (selected.isEmpty()) {
            return;
        }

        boolean accepted = DialogFactory.confirm(
                window(),
                "Export decrypted copies",
                "Exported files are not encrypted by EncryptDrive at the selected destination.",
                "Continue"
        );

        if (!accepted) {
            return;
        }

        List<Path> targets;

        if (selected.size() == 1 && selected.get(0).getKind() == ManifestEntryKind.FILE) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save a decrypted copy");
            chooser.setInitialFileName(FileService.safeFileName(selected.get(0).getName()));
            File chosen = chooser.showSaveDialog(window());

            if (chosen == null) {
                return;
            }

            targets = List.of(chosen.toPath());

        } else {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose a folder for the decrypted copies");
            File directory = chooser.showDialog(window());

            if (directory == null) {
                return;
            }

            try {
                targets = files.exportTargets(
                        selected.stream().map(ManifestEntry::getEntryId).toList(),
                        directory.toPath()
                );
            } catch (FileServiceException e) {
                showError(describe(e));
                return;
            }

            long existing = targets.stream().filter(Files::exists).count();

            if (existing > 0 && !DialogFactory.confirm(
                    window(),
                    "Replace existing items?",
                    (existing == 1 ? "1 item with the same name already exists"
                            : existing + " items with the same names already exist")
                            + " in that folder. Replace them with the decrypted copies?",
                    "Replace"
            )) {
                return;
            }
        }

        exportTo(selected, targets);
    }

    /** Writes decrypted copies of the entries to the given targets, which may be replaced. */
    void exportTo(List<ManifestEntry> entries, List<Path> targets) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws FileServiceException {
                for (int i = 0; i < entries.size(); i++) {
                    updateMessage("Decrypting " + entries.get(i).getName() + "...");
                    updateProgress(i, entries.size());
                    files.exportEntry(entries.get(i).getEntryId(), targets.get(i));
                }

                updateProgress(entries.size(), entries.size());
                return null;
            }
        };

        runWithProgress(task, done -> showSuccess(entries.size() == 1
                ? "Decrypted copy saved to " + targets.get(0) + "."
                : entries.size() + " items exported to " + targets.get(0).getParent() + "."));
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
            case STORAGE -> "The vault or destination folder could not be written.";
            case LIMIT -> "Your encrypted file list has reached EncryptDrive's size limit. Empty the trash or remove files first.";
        };
    }

    private <T> void runWithProgress(Task<T> task, Consumer<T> onDone) {
        setBusy(true);
        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(event -> {
            setBusy(false);
            refresh();
            onDone.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            setBusy(false);
            refresh();
            showError(task.getException() instanceof FileServiceException error
                    ? describe(error)
                    : "The operation could not be completed.");
        });

        Background.start(task);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;

        if (!busy) {
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();
        }

        progressBox.setVisible(busy);
        progressBox.setManaged(busy);
        table.setDisable(busy);
        breadcrumbBar.setDisable(busy);
        updateActions();
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
        if (files == null) {
            return;
        }

        try {
            table.getItems().setAll(files.listChildren(currentFolderId));
            renderBreadcrumbs(files.pathTo(currentFolderId));
        } catch (FileServiceException e) {
            showError(describe(e));
        }

        if (RecoveryService.takeRecoveryNotice()) {
            feedbackLabel.getStyleClass().remove("success");
            feedbackLabel.getStyleClass().add("notice");
            feedbackLabel.setText(Formats.RECOVERY_NOTICE);
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
        boolean unavailable = busy || files == null;
        boolean nothingSelected = table.getSelectionModel().getSelectedItems().isEmpty();

        newFolderButton.setDisable(unavailable);
        importButton.setDisable(unavailable);
        exportButton.setDisable(unavailable || nothingSelected);
        trashButton.setDisable(unavailable || nothingSelected);
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

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void column(
            TableColumn<ManifestEntry, String> column,
            Function<ManifestEntry, String> value
    ) {
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
    }
}
