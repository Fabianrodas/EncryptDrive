package com.fabianrodas.services;

public final class VaultException extends Exception {

    public enum Reason {
        BUSY,
        STORAGE
    }

    private final Reason reason;

    public VaultException(Reason reason) {
        this(reason, null);
    }

    public VaultException(Reason reason, Throwable cause) {
        super("Vault operation failed: " + reason, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
