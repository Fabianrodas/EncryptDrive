package com.fabianrodas.models;

import com.fabianrodas.security.SensitiveBytes;
import java.nio.file.Path;

/**
 * An unlocked vault: non-secret metadata plus the registry master key and
 * the process lock, both released on close.
 */
public final class VaultContext implements AutoCloseable {

    private final Path root;
    private final String vaultId;
    private final int formatVersion;
    private final String createdAt;
    private final SensitiveBytes registryKey;
    private final AutoCloseable lock;

    public VaultContext(
            Path root,
            String vaultId,
            int formatVersion,
            String createdAt,
            SensitiveBytes registryKey,
            AutoCloseable lock
    ) {
        this.root = root;
        this.vaultId = vaultId;
        this.formatVersion = formatVersion;
        this.createdAt = createdAt;
        this.registryKey = registryKey;
        this.lock = lock;
    }

    public Path root() {
        return root;
    }

    public String vaultId() {
        return vaultId;
    }

    public int formatVersion() {
        return formatVersion;
    }

    public String createdAt() {
        return createdAt;
    }

    /** Returns a fresh copy of the registry master key; the caller zeroes it. */
    public byte[] copyRegistryKey() {
        return registryKey.copy();
    }

    public boolean isClosed() {
        return registryKey.isDestroyed();
    }

    @Override
    public void close() {
        registryKey.close();

        try {
            lock.close();
        } catch (Exception ignored) {
            // The OS releases the lock with its channel in any case.
        }
    }
}
