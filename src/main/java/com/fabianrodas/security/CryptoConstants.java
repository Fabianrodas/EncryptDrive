package com.fabianrodas.security;

public final class CryptoConstants {

    public static final int KEY_BYTES = 32;
    public static final int SALT_BYTES = 16;
    public static final int GCM_NONCE_BYTES = 12;
    public static final int GCM_TAG_BITS = 128;
    public static final int ARGON_MEMORY_KIB = 65_536;
    public static final int ARGON_ITERATIONS = 3;
    public static final int ARGON_PARALLELISM = 1;

    private CryptoConstants() {
    }
}
