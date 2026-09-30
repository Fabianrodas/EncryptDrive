package com.fabianrodas.models;

/**
 * One account inside the encrypted user registry. There is no password
 * hash: unwrapping the user master key is the password check.
 */
public final class UserRecord {

    private String userId;
    private String fullName;
    private String username;
    private String normalizedUsername;
    private String createdAt;
    private String manifestId;
    private KdfConfig userKdf;
    private EncryptedPayload wrappedUserMasterKey;

    public UserRecord() {
    }

    public UserRecord(
            String userId,
            String fullName,
            String username,
            String normalizedUsername,
            String createdAt,
            String manifestId,
            KdfConfig userKdf,
            EncryptedPayload wrappedUserMasterKey
    ) {
        this.userId = userId;
        this.fullName = fullName;
        this.username = username;
        this.normalizedUsername = normalizedUsername;
        this.createdAt = createdAt;
        this.manifestId = manifestId;
        this.userKdf = userKdf;
        this.wrappedUserMasterKey = wrappedUserMasterKey;
    }

    public String getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getUsername() {
        return username;
    }

    public String getNormalizedUsername() {
        return normalizedUsername;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getManifestId() {
        return manifestId;
    }

    public KdfConfig getUserKdf() {
        return userKdf;
    }

    public EncryptedPayload getWrappedUserMasterKey() {
        return wrappedUserMasterKey;
    }

    public void setCredentials(KdfConfig userKdf, EncryptedPayload wrappedUserMasterKey) {
        this.userKdf = userKdf;
        this.wrappedUserMasterKey = wrappedUserMasterKey;
    }
}
