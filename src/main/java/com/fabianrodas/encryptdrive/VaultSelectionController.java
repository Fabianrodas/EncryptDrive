package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.VaultContext;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.VaultException;
import com.fabianrodas.services.VaultService;
import com.fabianrodas.utils.WindowDragHandler;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

/**
 * FXML Controller class
 *
 * @author Fabian Rodas
 */

public class VaultSelectionController implements Initializable {

    @FXML
    private BorderPane root;

    @FXML
    private VBox formCard;

    @FXML
    private ToggleButton openModeButton;

    @FXML
    private ToggleButton createModeButton;

    @FXML
    private VBox openSection;

    @FXML
    private VBox createSection;

    @FXML
    private TextField openFolderField;

    @FXML
    private PasswordField openPasswordField;

    @FXML
    private TextField visibleOpenPasswordField;

    @FXML
    private Button toggleOpenPasswordButton;

    @FXML
    private TextField createNameField;

    @FXML
    private TextField createParentField;

    @FXML
    private PasswordField createPasswordField;

    @FXML
    private PasswordField createConfirmPasswordField;

    @FXML
    private Label feedbackLabel;

    @FXML
    private Button submitButton;

    private final VaultService vaultService = new VaultService();

    private final WindowDragHandler windowDragHandler
            = new WindowDragHandler();

    private Path openFolder;
    private Path createParent;
    private boolean passwordVisible = false;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        visibleOpenPasswordField.textProperty()
                .bindBidirectional(openPasswordField.textProperty());

        ToggleGroup modes = new ToggleGroup();
        openModeButton.setToggleGroup(modes);
        createModeButton.setToggleGroup(modes);

        configureResponsiveForm();
        setCreateMode(false);
    }

    @FXML
    private void beginDrag(MouseEvent event) {
        windowDragHandler.beginDrag(event, getStage());
    }

    @FXML
    private void dragWindow(MouseEvent event) {
        windowDragHandler.dragWindow(event, getStage());
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

    @FXML
    private void toggleMaximize() {
        App.toggleMaximize(getStage());
    }

    @FXML
    private void showOpenMode() {
        setCreateMode(false);
    }

    @FXML
    private void showCreateMode() {
        setCreateMode(true);
    }

    @FXML
    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;

        openPasswordField.setVisible(!passwordVisible);
        openPasswordField.setManaged(!passwordVisible);

        visibleOpenPasswordField.setVisible(passwordVisible);
        visibleOpenPasswordField.setManaged(passwordVisible);

        toggleOpenPasswordButton.setText(passwordVisible ? "Hide" : "View");
    }

    @FXML
    private void browseOpenFolder() {
        Path chosen = chooseDirectory("Select your EncryptDrive vault folder");

        if (chosen != null) {
            openFolder = chosen;
            openFolderField.setText(chosen.toString());
        }
    }

    @FXML
    private void browseCreateParent() {
        Path chosen = chooseDirectory("Choose where to create the vault");

        if (chosen != null) {
            createParent = chosen;
            createParentField.setText(chosen.toString());
        }
    }

    @FXML
    private void submit() {
        if (createModeButton.isSelected()) {
            createVault();
        } else {
            unlockVault();
        }
    }

    static String vaultNameError(String name) {
        if (name.isEmpty()) {
            return "Enter a name for the vault folder.";
        }

        if (!FileService.safeFileName(name).equals(name)) {
            return "Use a folder name without \\ / : * ? \" < > | or a trailing dot.";
        }

        return null;
    }

    private void unlockVault() {
        if (openFolder == null) {
            showError("Choose the vault folder to open.");
            return;
        }

        if (openPasswordField.getText().isEmpty()) {
            showError("Enter the vault password.");
            return;
        }

        char[] password = openPasswordField.getText().toCharArray();
        openPasswordField.clear();
        Path folder = openFolder;

        runVaultOperation(
                "Unlocking...",
                () -> vaultService.unlockVault(folder, password),
                password,
                "login"
        );
    }

    private void createVault() {
        String name = createNameField.getText().trim();
        String nameError = vaultNameError(name);

        if (nameError != null) {
            showError(nameError);
            return;
        }

        if (createParent == null) {
            showError("Choose where to create the vault.");
            return;
        }

        String password = createPasswordField.getText();

        if (password.length() < VaultService.MIN_PASSWORD_LENGTH) {
            showError("The vault password must contain at least 12 characters.");
            return;
        }

        if (!password.equals(createConfirmPasswordField.getText())) {
            showError("The vault passwords do not match.");
            return;
        }

        boolean confirmed = DialogFactory.confirm(
                getStage(),
                "Keep your vault password safe",
                "EncryptDrive cannot recover a lost vault password. "
                        + "If you lose it, the vault cannot be unlocked.",
                "Continue"
        );

        if (!confirmed) {
            return;
        }

        char[] passwordChars = password.toCharArray();
        createPasswordField.clear();
        createConfirmPasswordField.clear();
        Path vaultRoot = createParent.resolve(name);

        runVaultOperation(
                "Creating vault...",
                () -> vaultService.createVault(vaultRoot, passwordChars),
                passwordChars,
                "register"
        );
    }

    private void runVaultOperation(
            String busyText,
            Callable<VaultContext> operation,
            char[] password,
            String nextScreen
    ) {
        setBusy(true, busyText);

        Background.run(
                () -> {
                    try {
                        return operation.call();
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                },
                context -> {
                    try {
                        App.setRoot(nextScreen);
                    } catch (IOException e) {
                        vaultService.closeVault();
                        setBusy(false, null);
                        showError("Could not open the next screen.");
                    }
                },
                failure -> {
                    setBusy(false, null);
                    showError(failure instanceof VaultException vaultError
                            ? message(vaultError.getReason())
                            : "Something went wrong while opening the vault.");
                }
        );
    }

    private static String message(VaultException.Reason reason) {
        return switch (reason) {
            case BUSY -> "This vault is already open in another EncryptDrive process.";
            case UNLOCK_FAILED -> "The vault could not be unlocked. Check the vault password.";
            case NOT_A_VAULT -> "The selected folder is not an EncryptDrive vault.";
            case CORRUPTED -> "The vault data is damaged and could not be opened.";
            case UNSUPPORTED_VERSION -> "This vault was created by a newer version of EncryptDrive.";
            case ALREADY_EXISTS -> "A folder with that name already exists there and is not empty.";
            case INVALID_PASSWORD -> "The vault password must contain at least 12 characters.";
            case STORAGE, NOT_OPEN -> "The vault folder could not be accessed.";
        };
    }

    private void setCreateMode(boolean create) {
        openModeButton.setSelected(!create);
        createModeButton.setSelected(create);

        openSection.setVisible(!create);
        openSection.setManaged(!create);

        createSection.setVisible(create);
        createSection.setManaged(create);

        submitButton.setText(create ? "Create vault" : "Unlock vault");
        feedbackLabel.setText("");
    }

    private void setBusy(boolean busy, String busyText) {
        formCard.setDisable(busy);

        if (busy) {
            submitButton.setText(busyText);
        } else {
            submitButton.setText(createModeButton.isSelected() ? "Create vault" : "Unlock vault");
        }
    }

    private Path chooseDirectory(String title) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);

        File chosen = chooser.showDialog(getStage());
        return chosen == null ? null : chosen.toPath();
    }

    private void configureResponsiveForm() {
        DoubleBinding formWidth = Bindings.createDoubleBinding(
                () -> Math.max(
                        430,
                        Math.min(600, root.getWidth() * 0.36)
                ),
                root.widthProperty()
        );

        formCard.prefWidthProperty().bind(formWidth);
    }

    private void showError(String message) {
        feedbackLabel.setText(message);
    }

    private Stage getStage() {
        if (root == null || root.getScene() == null) {
            return null;
        }

        return (Stage) root.getScene().getWindow();
    }
}
