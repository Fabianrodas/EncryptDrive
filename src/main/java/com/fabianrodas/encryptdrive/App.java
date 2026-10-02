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

public class App extends Application {

    private static final double DEFAULT_WIDTH = 1000;
    private static final double DEFAULT_HEIGHT = 600;

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
        stage.setOnCloseRequest(event -> destroySessionKeys());

        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        destroySessionKeys();
    }

    /**
     * Destroys user and vault key material and releases the vault lock.
     * Idempotent, so every exit path can call it.
     */
    private static void destroySessionKeys() {
        SessionService.logout();
        VaultSessionService.closeVault();
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    /**
     * Logs out, destroys user and vault key material, releases the vault
     * lock, and returns to Vault Selection.
     */
    static void closeVault() throws IOException {
        VaultSessionService.closeVault();
        setRoot("vault-selection");
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