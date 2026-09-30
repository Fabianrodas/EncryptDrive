package com.fabianrodas.repositories;

public final class VaultStorageException extends Exception {

    public enum Reason {
        NOT_FOUND,
        CORRUPTED,
        UNSUPPORTED_VERSION,
        IO
    }

    private final Reason reason;

    public VaultStorageException(Reason reason) {
        this(reason, null);
    }

    public VaultStorageException(Reason reason, Throwable cause) {
        super("Vault storage error: " + reason, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
