package com.fabianrodas.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class Argon2KeyDeriverTest {

    private static final byte[] SALT
            = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private final Argon2KeyDeriver deriver = new Argon2KeyDeriver();

    @Test
    void deriveReturns32Bytes() {
        assertEquals(32, derive("correct horse battery").length);
    }

    @Test
    void samePasswordAndSaltProduceSameKey() {
        assertArrayEquals(
                derive("correct horse battery"),
                derive("correct horse battery")
        );
    }

    @Test
    void differentPasswordProducesDifferentKey() {
        assertFalse(Arrays.equals(
                derive("correct horse battery"),
                derive("correct horse battery!")
        ));
    }

    @Test
    void callerPasswordArrayIsNotModified() {
        char[] password = "correct horse battery".toCharArray();

        deriver.derive(
                password,
                SALT,
                CryptoConstants.ARGON_MEMORY_KIB,
                CryptoConstants.ARGON_ITERATIONS,
                CryptoConstants.ARGON_PARALLELISM
        );

        assertArrayEquals("correct horse battery".toCharArray(), password);
    }

    /*
     * Argon2id v1.3 vector from the reference implementation test suite,
     * reproduced with OpenSSL 3.2 (ARGON2ID, t=2, m=65536 KiB, p=1).
     */
    @Test
    void matchesArgon2idReferenceVector() {
        byte[] key = deriver.derive(
                "password".toCharArray(),
                "somesalt".getBytes(StandardCharsets.US_ASCII),
                65_536,
                2,
                1
        );

        assertArrayEquals(
                HexFormat.of().parseHex(
                        "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7"
                ),
                key
        );
    }

    /*
     * Production parameters with a non-ASCII password. Expected value computed
     * independently with argon2-cffi 25.1.0 and OpenSSL 3.2 from the UTF-8
     * password bytes; a cp1252 encoding would produce a different key.
     */
    @Test
    void productionParametersHashPasswordAsUtf8() {
        assertArrayEquals(
                HexFormat.of().parseHex(
                        "d6a027c0416df4712879ba4123cec66d71efdf0c86ecc2a289031ba528169c91"
                ),
                derive("contraseña-ñ€")
        );
    }

    private byte[] derive(String password) {
        return deriver.derive(
                password.toCharArray(),
                SALT,
                CryptoConstants.ARGON_MEMORY_KIB,
                CryptoConstants.ARGON_ITERATIONS,
                CryptoConstants.ARGON_PARALLELISM
        );
    }
}
