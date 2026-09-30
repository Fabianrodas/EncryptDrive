package com.fabianrodas.security;

/**
 * Authenticated decryption failed. Deliberately carries no detail so that
 * wrong keys, wrong context, and tampering are indistinguishable.
 */
public final class CryptoException extends Exception {

    public CryptoException() {
        super("Authenticated decryption failed.");
    }
}
