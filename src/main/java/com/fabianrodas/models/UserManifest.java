package com.fabianrodas.models;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decrypted content of one user's manifest.
 */
public final class UserManifest {

    private int formatVersion;
    private UUID userId;
    private UUID rootFolderId;
    private List<ManifestEntry> entries;
    private List<PendingDeletion> pendingDeletions;

    public UserManifest() {
    }

    public UserManifest(
            int formatVersion,
            UUID userId,
            UUID rootFolderId,
            List<ManifestEntry> entries
    ) {
        this.formatVersion = formatVersion;
        this.userId = userId;
        this.rootFolderId = rootFolderId;
        this.entries = entries;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getRootFolderId() {
        return rootFolderId;
    }

    public List<ManifestEntry> getEntries() {
        return entries;
    }

    /** Blobs queued for physical deletion; empty, never null. */
    public List<PendingDeletion> getPendingDeletions() {
        if (pendingDeletions == null) {
            pendingDeletions = new ArrayList<>();
        }

        return pendingDeletions;
    }
}
