package com.fabianrodas.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/*
 * The AAD strings are part of the on-disk format: changing one makes every
 * existing vault undecryptable, so they are pinned to the design spec.
 */
class AadTest {

    @Test
    void vaultKeyContextMatchesSpec() {
        assertEquals("EncryptDrive|vault-key|v1|V", text(Aad.vaultKey("V")));
    }

    @Test
    void userRegistryContextMatchesSpec() {
        assertEquals("EncryptDrive|users|v1|V", text(Aad.users("V")));
    }

    @Test
    void userKeyContextMatchesSpec() {
        assertEquals("EncryptDrive|user-key|v1|V|U", text(Aad.userKey("V", "U")));
    }

    @Test
    void manifestContextMatchesSpec() {
        assertEquals("EncryptDrive|manifest|v1|V|U", text(Aad.manifest("V", "U")));
    }

    @Test
    void fileKeyContextMatchesSpec() {
        assertEquals("EncryptDrive|file-key|v1|V|U|F", text(Aad.fileKey("V", "U", "F")));
    }

    @Test
    void fileContentContextMatchesSpec() {
        assertEquals("EncryptDrive|file|v1|V|U|F", text(Aad.fileContent("V", "U", "F")));
    }

    private static String text(byte[] aad) {
        return new String(aad, UTF_8);
    }
}
