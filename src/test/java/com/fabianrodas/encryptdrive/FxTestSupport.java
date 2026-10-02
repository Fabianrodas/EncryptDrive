package com.fabianrodas.encryptdrive;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Starts the JavaFX toolkit once for UI tests and runs work on its thread.
 */
final class FxTestSupport {

    private static Boolean started;

    private FxTestSupport() {
    }

    /** Returns false when no desktop session can host JavaFX (e.g. headless CI). */
    static synchronized boolean start() {
        if (started == null) {
            try {
                CountDownLatch ready = new CountDownLatch(1);
                Platform.startup(ready::countDown);
                Platform.setImplicitExit(false);
                started = ready.await(20, TimeUnit.SECONDS);
            } catch (IllegalStateException alreadyRunning) {
                started = true;
            } catch (Exception | Error unavailable) {
                started = false;
            }
        }

        return started;
    }

    /** Loads a screen into a new scene and makes it the scene App.setRoot navigates. */
    static Scene showScreen(String fxml) throws Exception {
        return onFxThread(() -> {
            Scene scene = new Scene(FXMLLoader.load(App.class.getResource(fxml + ".fxml")), 1000, 600);
            Field appScene = App.class.getDeclaredField("scene");
            appScene.setAccessible(true);
            appScene.set(null, scene);
            return scene;
        });
    }

    /** Shows a screen in its own stage with the application's close guard installed. */
    static Stage showInStage(String fxml) throws Exception {
        Scene scene = showScreen(fxml);

        return onFxThread(() -> {
            Stage stage = new Stage();
            App.installCloseGuard(stage);
            stage.setScene(scene);
            stage.show();
            return stage;
        });
    }

    /** Starts a counted background task that runs until the returned latch is released. */
    static CountDownLatch holdBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);

        onFxThread(() -> {
            Background.run(() -> {
                release.await();
                return null;
            }, done -> { }, failure -> { });
            return null;
        });

        return release;
    }

    /**
     * Fires a button that opens a modal popup and answers the popup by
     * clicking the button with the given text.
     */
    static void fireAndAnswerPopup(Scene scene, String selector, String answer) throws Exception {
        fireAndAnswer(scene, selector, popup -> clickButton(popup, answer));
    }

    /**
     * Fires a button that opens a modal popup (possibly after background
     * work), waits for the popup, then lets {@code answer} act on its stage:
     * type text, pick a folder, click a button. The button is fired without
     * waiting, because a modal popup blocks its handler until it closes.
     */
    static void fireAndAnswer(Scene scene, String selector, Consumer<Stage> answer) throws Exception {
        Platform.runLater(() -> ((ButtonBase) scene.getRoot().lookup(selector)).fire());
        waitUntil(() -> modalPopup() != null);
        onFxThread(() -> {
            answer.accept(modalPopup());
            return null;
        });
    }

    /** The showing application-modal popup, if any. */
    private static Stage modalPopup() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage
                    && stage.isShowing()
                    && stage.getModality() == Modality.APPLICATION_MODAL) {
                return stage;
            }
        }

        return null;
    }

    /** Clicks the button with the given text in a popup stage. */
    static void clickButton(Stage popup, String text) {
        for (Node node : popup.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof ButtonBase button && text.equals(button.getText())) {
                button.fire();
                return;
            }
        }

        throw new AssertionError("No button \"" + text + "\" in the popup");
    }

    static void waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;

        while (!onFxThread(condition)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for the UI");
            }

            Thread.sleep(50);
        }
    }

    static <T> T onFxThread(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });

        return result.get(30, TimeUnit.SECONDS);
    }
}
