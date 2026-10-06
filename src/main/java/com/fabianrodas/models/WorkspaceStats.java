package com.fabianrodas.models;

/**
 * Totals for the signed-in user, derived from the encrypted manifest and the
 * blob sizes on disk. {@code encryptedBytes} includes blobs still in the trash.
 * {@code pendingDeletions} counts blobs still queued for removal.
 */
public record WorkspaceStats(
        int activeFileCount,
        long activePlainBytes,
        long encryptedBytes,
        int trashCount,
        int pendingDeletions
) {
}
