package com.fabianrodas.encryptdrive;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.concurrent.Task;

/**
 * Runs slow work (key derivation, file encryption) off the JavaFX thread and
 * reports back on it.
 */
final class Background {

    private Background() {
    }

    static <T> void run(
            Callable<T> work,
            Consumer<T> onSuccess,
            Consumer<Throwable> onFailure
    ) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };

        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> onFailure.accept(task.getException()));

        Thread worker = new Thread(task, "EncryptDrive worker");
        worker.setDaemon(true);
        worker.start();
    }
}
