package com.fabianrodas.security;

import java.nio.charset.StandardCharsets;

/**
 * AES-GCM associated data from the design spec. These strings are part of
 * the on-disk format: every ciphertext is bound to one of them.
 */
public final class Aad {

    private Aad() {
    }

    public static byte[] vaultKey(String vaultId) {
        return of("vault-key|v1|" + vaultId);
    }

    public static byte[] users(String vaultId) {
        return of("users|v1|" + vaultId);
    }

    private static byte[] of(String context) {
        return ("EncryptDrive|" + context).getBytes(StandardCharsets.UTF_8);
    }
}
