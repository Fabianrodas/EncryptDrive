package com.fabianrodas.security;

import java.util.Arrays;

/**
 * Holds key material and zeroes it on close. Methods are synchronized
 * because keys are read by background tasks and destroyed on logout.
 */
public final class SensitiveBytes implements AutoCloseable {

    private final byte[] bytes;
    private boolean destroyed;

    private SensitiveBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    public static SensitiveBytes copyOf(byte[] source) {
        return new SensitiveBytes(source.clone());
    }

    /**
     * Takes ownership of {@code owned} without copying it; the array is
     * zeroed when this instance is closed.
     */
    public static SensitiveBytes wrap(byte[] owned) {
        return new SensitiveBytes(owned);
    }

    public synchronized byte[] copy() {
        if (destroyed) {
            throw new IllegalStateException("Key material was destroyed.");
        }

        return bytes.clone();
    }

    public synchronized boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public synchronized void close() {
        Arrays.fill(bytes, (byte) 0);
        destroyed = true;
    }
}
