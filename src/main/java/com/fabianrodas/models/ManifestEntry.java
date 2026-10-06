package com.fabianrodas.models;

import java.util.UUID;

/**
 * One file or folder of a user's logical filesystem. File-only fields stay
 * null for folders, so they are omitted from the encrypted JSON.
 */
public final class ManifestEntry {

    private UUID entryId;
    private ManifestEntryKind kind;
    private UUID parentId;
    private String name;
    private String createdAt;
    private String modifiedAt;
    private String deletedAt;
    private UUID originalParentId;
    private Long plainSize;
    private UUID blobId;
    private EncryptedPayload wrappedFileKey;
    private String contentNonce;

    public ManifestEntry() {
    }

    public ManifestEntry(
            UUID entryId,
            ManifestEntryKind kind,
            UUID parentId,
            String name,
            String createdAt
    ) {
        this.entryId = entryId;
        this.kind = kind;
        this.parentId = parentId;
        this.name = name;
        this.createdAt = createdAt;
        this.modifiedAt = createdAt;
    }

    public UUID getEntryId() {
        return entryId;
    }

    public ManifestEntryKind getKind() {
        return kind;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public String getDeletedAt() {
        return deletedAt;
    }

    public UUID getOriginalParentId() {
        return originalParentId;
    }

    public Long getPlainSize() {
        return plainSize;
    }

    public UUID getBlobId() {
        return blobId;
    }

    public EncryptedPayload getWrappedFileKey() {
        return wrappedFileKey;
    }

    public String getContentNonce() {
        return contentNonce;
    }

    public void setDeletedAt(String deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setOriginalParentId(UUID originalParentId) {
        this.originalParentId = originalParentId;
    }

    public void setContent(
            long plainSize,
            UUID blobId,
            EncryptedPayload wrappedFileKey,
            String contentNonce
    ) {
        this.plainSize = plainSize;
        this.blobId = blobId;
        this.wrappedFileKey = wrappedFileKey;
        this.contentNonce = contentNonce;
    }
}
