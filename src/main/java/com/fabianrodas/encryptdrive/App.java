package com.fabianrodas.encryptdrive;

import java.io.IOException;
import java.util.Objects;
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
                loadFXML("login"),
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

        stage.setScene(scene);
        stage.show();
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
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