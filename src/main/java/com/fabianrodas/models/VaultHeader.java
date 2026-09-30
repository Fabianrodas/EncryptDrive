package com.fabianrodas.models;

/**
 * Plaintext {@code vault.json}: only what is needed to identify the vault
 * and unwrap its registry key.
 */
public final class VaultHeader {

    private int formatVersion;
    private String vaultId;
    private String createdAt;
    private KdfConfig kdf;
    private EncryptedPayload wrappedRegistryKey;

    public VaultHeader() {
    }

    public VaultHeader(
            int formatVersion,
            String vaultId,
            String createdAt,
            KdfConfig kdf,
            EncryptedPayload wrappedRegistryKey
    ) {
        this.formatVersion = formatVersion;
        this.vaultId = vaultId;
        this.createdAt = createdAt;
        this.kdf = kdf;
        this.wrappedRegistryKey = wrappedRegistryKey;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public String getVaultId() {
        return vaultId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public KdfConfig getKdf() {
        return kdf;
    }

    public EncryptedPayload getWrappedRegistryKey() {
        return wrappedRegistryKey;
    }
}
