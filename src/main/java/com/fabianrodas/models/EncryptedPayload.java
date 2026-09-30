package com.fabianrodas.models;

/**
 * AES-GCM envelope persisted as JSON. Binary fields are Base64; the
 * ciphertext carries the 128-bit authentication tag at its end.
 */
public final class EncryptedPayload {

    private int version;
    private String algorithm;
    private String nonce;
    private String ciphertext;

    public EncryptedPayload() {
    }

    public EncryptedPayload(
            int version,
            String algorithm,
            String nonce,
            String ciphertext
    ) {
        this.version = version;
        this.algorithm = algorithm;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
    }

    public int getVersion() {
        return version;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getNonce() {
        return nonce;
    }

    public String getCiphertext() {
        return ciphertext;
    }
}
