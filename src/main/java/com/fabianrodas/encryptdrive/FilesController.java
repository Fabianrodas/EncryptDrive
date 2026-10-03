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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
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
    private MenuButton importMenu;

    @FXML
    private Button exportButton;

    @FXML
    private Button renameButton;

    @FXML
    private Button moveButton;

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

    /** Results of older folder loads are dropped when they arrive late. */
    private int viewRequest;

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
        refresh();
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

        if (name.isPresent()) {
            UUID parent = currentFolderId;
            change(() -> files.createFolder(name.get(), parent), folder -> showSuccess("Folder created."));
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

        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() {
                long total = Math.max(1, sources.stream().mapToLong(FilesController::sizeOf).sum());
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
    private void importFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Import a folder into EncryptDrive");
        File chosen = chooser.showDialog(window());

        if (chosen != null) {
            importFolder(chosen.toPath());
        }
    }

    /** Encrypts a copy of the folder tree into the current folder; the source is left untouched. */
    private void importFolder(Path source) {
        UUID target = currentFolderId;

        Task<FileService.FolderImport> task = new Task<>() {
            @Override
            protected FileService.FolderImport call() throws FileServiceException {
                updateMessage("Scanning " + source.getFileName() + "...");

                return files.importFolder(source, target, (done, total, bytes, totalBytes) -> {
                    updateMessage("Encrypting file " + Math.min(done + 1, total) + " of " + total + "...");

                    if (totalBytes > 0) {
                        updateProgress(bytes, totalBytes);
                    } else {
                        updateProgress(done, Math.max(1, total));
                    }
                });
            }
        };

        runWithProgress(task, result -> showFolderImport(source, result));
    }

    private void showFolderImport(Path source, FileService.FolderImport result) {
        String summary = count(result.filesImported(), "file") + " and " + count(result.foldersCreated(), "folder")
                + " imported from \"" + source.getFileName() + "\".";

        if (result.linksSkipped() > 0) {
            summary += " " + count(result.linksSkipped(), "link") + " (symbolic links or junctions) skipped.";
        }

        if (result.failures().isEmpty()) {
            showSuccess(summary);
            return;
        }

        String failed = result.failures().stream()
                .limit(5)
                .map(failure -> failure.path() + " (" + describe(failure.reason()) + ")")
                .collect(Collectors.joining("; "));

        showError(summary
                + (result.stopped() ? " The import stopped early." : "")
                + " Not imported: " + failed
                + (result.failures().size() > 5 ? " and " + (result.failures().size() - 5) + " more." : "."));
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
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

        if (selected.size() == 1 && selected.get(0).getKind() == ManifestEntryKind.FILE) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save a decrypted copy");
            chooser.setInitialFileName(FileService.safeFileName(selected.get(0).getName()));
            File chosen = chooser.showSaveDialog(window());

            if (chosen != null) {
                exportTo(selected, List.of(chosen.toPath()));
            }

            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a folder for the decrypted copies");
        File directory = chooser.showDialog(window());

        if (directory == null) {
            return;
        }

        List<UUID> ids = selected.stream().map(ManifestEntry::getEntryId).toList();

        record Plan(List<Path> targets, long existing) { }

        Background.read(() -> {
            List<Path> targets = files.exportTargets(ids, directory.toPath());
            return new Plan(targets, targets.stream().filter(Files::exists).count());
        }, plan -> {
            long existing = plan.existing();

            if (existing == 0 || DialogFactory.confirm(
                    window(),
                    "Replace existing items?",
                    (existing == 1 ? "1 item with the same name already exists"
                            : existing + " items with the same names already exist")
                            + " in that folder. Replace them with the decrypted copies?",
                    "Replace"
            )) {
                exportTo(selected, plan.targets());
            }
        }, failure -> showError(describe(failure)));
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
    private void rename() {
        List<ManifestEntry> selected = table.getSelectionModel().getSelectedItems();

        if (selected.size() != 1) {
            return;
        }

        ManifestEntry entry = selected.get(0);
        Optional<String> name = DialogFactory.prompt(
                window(), "Rename", "Enter a new name for \"" + entry.getName() + "\".", entry.getName(), "Rename"
        );

        if (name.isPresent() && !name.get().equals(entry.getName())) {
            change(() -> {
                files.rename(entry.getEntryId(), name.get());
                return null;
            }, done -> showSuccess("Renamed to \"" + name.get().strip() + "\"."));
        }
    }

    @FXML
    private void move() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        if (selected.isEmpty()) {
            return;
        }

        Set<UUID> moving = selected.stream().map(ManifestEntry::getEntryId).collect(Collectors.toSet());

        Background.read(files::activeFolders, folders -> {
            String what = selected.size() == 1 ? "\"" + selected.get(0).getName() + "\"" : selected.size() + " items";

            Optional<ManifestEntry> chosen = DialogFactory.chooseFolder(
                    window(), "Move", "Choose the folder to move " + what + " into.",
                    folderTree(folders, moving), "Move here"
            );

            // The folder everything already lives in: nothing to save, nothing to announce.
            chosen.filter(folder -> !selected.stream().allMatch(entry -> folder.getEntryId().equals(entry.getParentId())))
                    .ifPresent(folder -> change(() -> {
                        files.move(List.copyOf(moving), folder.getEntryId());
                        return null;
                    }, done -> showSuccess(what + (selected.size() == 1 ? " was" : " were") + " moved to "
                            + (folder.getParentId() == null ? "My files" : "\"" + folder.getName() + "\"") + ".")));
        }, failure -> showError(describe(failure)));
    }

    /** Active folders as a tree, leaving out the folders being moved and everything inside them. */
    static TreeItem<ManifestEntry> folderTree(List<ManifestEntry> folders, Set<UUID> moving) {
        Map<UUID, List<ManifestEntry>> children = folders.stream()
                .filter(folder -> folder.getParentId() != null)
                .collect(Collectors.groupingBy(ManifestEntry::getParentId));
        ManifestEntry root = folders.stream()
                .filter(folder -> folder.getParentId() == null)
                .findFirst()
                .orElseThrow();

        return treeItem(root, children, moving);
    }

    // ponytail: recursion depth equals folder depth; fine below thousands of levels.
    private static TreeItem<ManifestEntry> treeItem(
            ManifestEntry folder,
            Map<UUID, List<ManifestEntry>> children,
            Set<UUID> moving
    ) {
        TreeItem<ManifestEntry> item = new TreeItem<>(folder);
        item.setExpanded(true);

        children.getOrDefault(folder.getEntryId(), List.of()).stream()
                .filter(child -> !moving.contains(child.getEntryId()))
                .sorted(Comparator.comparing(ManifestEntry::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(child -> item.getChildren().add(treeItem(child, children, moving)));

        return item;
    }

    @FXML
    private void moveToTrash() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        if (selected.isEmpty()) {
            return;
        }

        change(() -> {
            for (ManifestEntry entry : selected) {
                files.moveToTrash(entry.getEntryId());
            }

            return null;
        }, done -> showSuccess(selected.size() == 1
                ? "\"" + selected.get(0).getName() + "\" moved to the trash."
                : selected.size() + " items moved to the trash."));
    }

    static String describe(Throwable failure) {
        return failure instanceof FileServiceException error
                ? describe(error)
                : "The operation could not be completed.";
    }

    static String describe(FileServiceException e) {
        return describe(e.getReason());
    }

    static String describe(FileServiceException.Reason reason) {
        return switch (reason) {
            case INVALID_NAME -> "Names cannot be empty, \".\" or \"..\", or longer than 255 characters.";
            case DUPLICATE_NAME -> "An item with that name already exists in this folder.";
            case NOT_FOUND -> "The item no longer exists.";
            case NOT_A_FOLDER -> "Items can only be placed inside folders.";
            case INVALID_MOVE -> "A folder cannot be moved into itself or one of its subfolders.";
            case PROTECTED -> "Your top-level folder cannot be removed.";
            case NOT_IN_TRASH -> "Only items in the trash can be deleted permanently.";
            case SOURCE_UNREADABLE -> "The file or folder could not be read.";
            case INSIDE_VAULT -> "Choose a location outside the vault folder.";
            case INTEGRITY -> "The encrypted data failed verification, so nothing was exported.";
            case CORRUPTED -> "Your encrypted file list could not be read.";
            case STORAGE -> "The vault or destination folder could not be written.";
            case LIMIT -> "Your encrypted file list has reached EncryptDrive's size limit. Empty the trash or remove files first.";
        };
    }

    /** Runs a vault change in the background with the view locked, then reloads the folder. */
    private <T> void change(Callable<T> work, Consumer<T> onDone) {
        setBusy(true);
        Background.run(work, result -> {
            setBusy(false);
            refresh();
            onDone.accept(result);
        }, failure -> {
            setBusy(false);
            refresh();
            showError(describe(failure));
        });
    }

    private <T> void runWithProgress(Task<T> task, Consumer<T> onDone) {
        setBusy(true);
        showProgress(true);
        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(event -> {
            showProgress(false);
            setBusy(false);
            refresh();
            onDone.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            showProgress(false);
            setBusy(false);
            refresh();
            showError(describe(task.getException()));
        });

        Background.start(task);
    }

    private void showProgress(boolean visible) {
        if (!visible) {
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();
        }

        progressBox.setVisible(visible);
        progressBox.setManaged(visible);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
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

        int request = ++viewRequest;
        UUID folderId = currentFolderId;

        Background.read(() -> files.folderView(folderId), view -> {
            if (request == viewRequest) {
                show(view);
            }
        }, failure -> {
            if (request == viewRequest) {
                showError(describe(failure));
            }
        });
    }

    private void show(FileService.FolderView view) {
        currentFolderId = view.folderId();
        table.getItems().setAll(view.children());
        renderBreadcrumbs(view.path());

        // A view the user already left must leave the one-shot notice for the visible one.
        if (root.getScene() != null && RecoveryService.takeRecoveryNotice()) {
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
        // Until the first folder view arrives there is no folder to act in.
        boolean unavailable = busy || files == null || currentFolderId == null;
        boolean nothingSelected = table.getSelectionModel().getSelectedItems().isEmpty();

        newFolderButton.setDisable(unavailable);
        importMenu.setDisable(unavailable);
        exportButton.setDisable(unavailable || nothingSelected);
        renameButton.setDisable(unavailable || table.getSelectionModel().getSelectedItems().size() != 1);
        moveButton.setDisable(unavailable || nothingSelected);
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
