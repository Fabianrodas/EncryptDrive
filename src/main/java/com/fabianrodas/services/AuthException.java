package com.fabianrodas.services;

public final class AuthException extends Exception {

    public enum Reason {
        INVALID_INPUT,
        USERNAME_TAKEN,
        INVALID_CREDENTIALS,
        USER_NOT_FOUND,
        STORAGE,
        CORRUPTED
    }

    private final Reason reason;

    public AuthException(Reason reason) {
        this(reason, null);
    }

    public AuthException(Reason reason, Throwable cause) {
        super("Authentication failed: " + reason, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
