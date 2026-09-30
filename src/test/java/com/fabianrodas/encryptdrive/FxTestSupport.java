package com.fabianrodas.encryptdrive;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

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
