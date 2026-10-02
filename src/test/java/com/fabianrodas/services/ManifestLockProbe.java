package com.fabianrodas.services;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;

/**
 * Holds FileService's manifest lock from a helper thread, so every manifest
 * load and save blocks until close(). A UI flow that touches the manifest
 * on the JavaFX thread then freezes that thread, which tests can observe.
 */
public final class ManifestLockProbe implements AutoCloseable {

    private final CountDownLatch release = new CountDownLatch(1);

    public ManifestLockProbe() throws Exception {
        Field field = FileService.class.getDeclaredField("MANIFEST_LOCK");
        field.setAccessible(true);
        Object lock = field.get(null);
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                held.countDown();

                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "manifest lock probe");
        holder.setDaemon(true);
        holder.start();
        held.await();
    }

    @Override
    public void close() {
        release.countDown();
    }
}
