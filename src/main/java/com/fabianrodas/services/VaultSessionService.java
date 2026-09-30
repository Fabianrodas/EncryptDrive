package com.fabianrodas.services;

import com.fabianrodas.models.VaultContext;

/**
 * Holds the single unlocked vault of this process.
 */
public final class VaultSessionService {

    private static VaultContext current;

    private VaultSessionService() {
    }

    static synchronized void open(VaultContext context) {
        if (current != null && current != context) {
            current.close();
        }

        current = context;
    }

    public static synchronized boolean isOpen() {
        return current != null;
    }

    public static synchronized VaultContext current() {
        if (current == null) {
            throw new IllegalStateException("No EncryptDrive vault is open.");
        }

        return current;
    }

    /** Destroys the registry key and releases the vault lock. Idempotent. */
    public static synchronized void closeVault() {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
