package com.fabianrodas.encryptdrive;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.concurrent.Task;
import javafx.concurrent.WorkerStateEvent;

/**
 * Runs slow work (key derivation, file encryption) off the JavaFX thread and
 * reports back on it. Call from the JavaFX thread.
 */
final class Background {

    private static final SimpleIntegerProperty RUNNING = new SimpleIntegerProperty(0);
    private static final BooleanBinding BUSY = Bindings.greaterThan(RUNNING, 0);

    private Background() {
    }

    /** True while any background task is running, e.g. to lock navigation. */
    static BooleanBinding busyProperty() {
        return BUSY;
    }

    /** True while a counted background task runs. Call on the JavaFX thread. */
    static boolean isBusy() {
        return BUSY.get();
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
        start(task);
    }

    static void start(Task<?> task) {
        RUNNING.set(RUNNING.get() + 1);
        // A filter, not a handler: it runs before the task's onSucceeded/onFailed
        // callbacks, so a throwing callback cannot leave the app permanently busy.
        task.addEventFilter(WorkerStateEvent.ANY, event -> {
            if (event.getEventType() == WorkerStateEvent.WORKER_STATE_SUCCEEDED
                    || event.getEventType() == WorkerStateEvent.WORKER_STATE_FAILED
                    || event.getEventType() == WorkerStateEvent.WORKER_STATE_CANCELLED) {
                RUNNING.set(RUNNING.get() - 1);
            }
        });

        Thread worker = new Thread(task, "EncryptDrive worker");
        worker.setDaemon(true);
        worker.start();
    }
}
