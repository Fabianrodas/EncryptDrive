package com.fabianrodas.encryptdrive;

import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultSessionService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;

public class App extends Application {

    private static final double DEFAULT_WIDTH = 1000;
    private static final double DEFAULT_HEIGHT = 600;

    static final String BUSY_CLOSE_MESSAGE
            = "Please wait for the current file operation to finish before closing EncryptDrive.";

    private static Scene scene;

    @Override
    public void start(Stage stage) throws IOException {
        scene = new Scene(
                loadFXML("vault-selection"),
                DEFAULT_WIDTH,
                DEFAULT_HEIGHT
        );

        Image icon = new Image(
                Objects.requireNonNull(
                        App.class.getResource(
                                "/com/fabianrodas/images/logo.png"
                        )
                ).toExternalForm()
        );

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("EncryptDrive");
        stage.getIcons().add(icon);

        stage.setMinWidth(DEFAULT_WIDTH);
        stage.setMinHeight(DEFAULT_HEIGHT);
        stage.setResizable(true);
        installCloseGuard(stage);

        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        // Backstop for exits that bypass the close guard; idempotent.
        VaultSessionService.closeVault();
    }

    /**
     * The single close path. Title-bar buttons (through {@link #requestClose}),
     * Alt+F4 and OS close requests all reach this handler. While background
     * work runs the request is refused; otherwise user and vault keys are
     * wiped and the vault lock released before the window closes.
     */
    static void installCloseGuard(Stage stage) {
        stage.setOnCloseRequest(event -> {
            if (Background.isBusy()) {
                event.consume();
                DialogFactory.inform(stage, "Please wait", BUSY_CLOSE_MESSAGE);
                return;
            }

            VaultSessionService.closeVault();
        });
    }

    /** Asks the window to close the way the OS does, so the close guard decides. */
    static void requestClose(Stage stage) {
        if (stage != null) {
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        }
    }

    /** Ends the account session and shows Login; refused while background work runs. */
    static boolean logout() throws IOException {
        if (Background.isBusy()) {
            return false;
        }

        SessionService.logout();
        setRoot("login");
        return true;
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    /**
     * Logs out, wipes the vault key, releases the vault lock and shows Vault
     * Selection; refused while background work runs.
     */
    static boolean closeVault() throws IOException {
        if (Background.isBusy()) {
            return false;
        }

        VaultSessionService.closeVault();
        setRoot("vault-selection");
        return true;
    }

    /** The Maven project version, filtered into version.properties at build time. */
    static String version() {
        Properties properties = new Properties();

        try (InputStream in = App.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "unknown";
            }

            properties.load(in);
        } catch (IOException e) {
            return "unknown";
        }

        return properties.getProperty("version", "unknown");
    }

    static String openVaultName() {
        if (!VaultSessionService.isOpen()) {
            return "No vault open";
        }

        Path root = VaultSessionService.current().root();
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }

    public static void toggleMaximize(Stage stage) {
        if (stage != null) {
            stage.setMaximized(!stage.isMaximized());
        }
    }

    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(
                App.class.getResource(fxml + ".fxml")
        );

        return fxmlLoader.load();
    }

    public static void main(String[] args) {
        launch();
    }
}