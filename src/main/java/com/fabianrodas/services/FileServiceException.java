package com.fabianrodas.services;

public final class FileServiceException extends Exception {

    public enum Reason {
        INVALID_NAME,
        DUPLICATE_NAME,
        NOT_FOUND,
        NOT_A_FOLDER,
        PROTECTED,
        NOT_IN_TRASH,
        SOURCE_UNREADABLE,
        INTEGRITY,
        CORRUPTED,
        STORAGE
    }

    private final Reason reason;

    public FileServiceException(Reason reason) {
        this(reason, null);
    }

    public FileServiceException(Reason reason, Throwable cause) {
        super("File operation failed: " + reason, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
