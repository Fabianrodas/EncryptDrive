package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/*
 * The background boundary: work runs off the JavaFX thread and reports back
 * on it. The busy state guards every exit path, so it must return to idle
 * however a task ends, including when the screen's own callback throws.
 */
class BackgroundTest {

    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private Thread.UncaughtExceptionHandler original;

    @BeforeAll
    static void startJavaFx() {
        assumeTrue(FxTestSupport.start(), "JavaFX needs a desktop session");
    }

    /** A throwing callback reaches the FX thread's handler; collect it instead of printing it. */
    @BeforeEach
    void collectUncaughtCallbackExceptions() throws Exception {
        FxTestSupport.onFxThread(() -> {
            original = Thread.currentThread().getUncaughtExceptionHandler();
            Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> uncaught.add(failure));
            return null;
        });
    }

    @AfterEach
    void restoreUncaughtExceptionHandler() throws Exception {
        FxTestSupport.onFxThread(() -> {
            Thread.currentThread().setUncaughtExceptionHandler(original);
            return null;
        });
    }

    @Test
    void readRunsOffTheFxThreadReportsOnItAndIsNotBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> workOnFx = new CompletableFuture<>();
        CompletableFuture<Boolean> callbackOnFx = new CompletableFuture<>();

        FxTestSupport.onFxThread(() -> {
            Background.read(() -> {
                workOnFx.complete(Platform.isFxApplicationThread());
                release.await();
                return "value";
            }, value -> callbackOnFx.complete(Platform.isFxApplicationThread()), failure -> callbackOnFx.complete(false));
            return null;
        });

        assertFalse(workOnFx.get(10, TimeUnit.SECONDS));
        assertFalse(FxTestSupport.onFxThread(Background::isBusy));
        release.countDown();
        assertTrue(callbackOnFx.get(10, TimeUnit.SECONDS));
    }

    @Test
    void runIsBusyUntilItFinishesAndFailuresArriveOnTheFxThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> failure = new CompletableFuture<>();

        FxTestSupport.onFxThread(() -> {
            Background.run(() -> {
                release.await();
                throw new IllegalStateException("boom");
            }, value -> failure.complete("unexpected success"), error -> failure.complete(
                    Platform.isFxApplicationThread() ? error.getMessage() : "failure reported off the FX thread"));
            return null;
        });

        assertTrue(FxTestSupport.onFxThread(Background::isBusy));
        release.countDown();
        assertEquals("boom", failure.get(10, TimeUnit.SECONDS));
        FxTestSupport.waitUntil(() -> !Background.isBusy());
    }

    @Test
    void busyClearsWhenTheSuccessCallbackThrows() throws Exception {
        FxTestSupport.onFxThread(() -> {
            Background.run(
                    () -> "done",
                    value -> {
                        throw new IllegalStateException("success callback");
                    },
                    failure -> { }
            );
            return null;
        });

        FxTestSupport.waitUntil(() -> !uncaught.isEmpty());
        FxTestSupport.waitUntil(() -> !Background.isBusy());
        assertEquals("success callback", uncaught.get(0).getMessage());
    }

    @Test
    void busyClearsWhenTheFailureCallbackThrows() throws Exception {
        FxTestSupport.onFxThread(() -> {
            Background.run(
                    () -> {
                        throw new IllegalArgumentException("work");
                    },
                    value -> { },
                    failure -> {
                        throw new IllegalStateException("failure callback");
                    }
            );
            return null;
        });

        FxTestSupport.waitUntil(() -> !uncaught.isEmpty());
        FxTestSupport.waitUntil(() -> !Background.isBusy());
        assertEquals("failure callback", uncaught.get(0).getMessage());
    }

    @Test
    void busyClearsWhenATaskIsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                started.countDown();

                while (!isCancelled()) {
                    Thread.sleep(10);
                }

                return null;
            }
        };

        FxTestSupport.onFxThread(() -> {
            Background.start(task);
            return null;
        });
        assertTrue(started.await(20, TimeUnit.SECONDS));
        FxTestSupport.onFxThread(() -> {
            task.cancel();
            return null;
        });

        FxTestSupport.waitUntil(() -> !Background.isBusy());
    }
}
