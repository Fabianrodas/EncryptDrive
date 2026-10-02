package com.fabianrodas.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.EncryptedPayload;
import org.junit.jupiter.api.Test;

class MetadataJsonTest {

    private static final String VALID
            = "{\"version\":1,\"algorithm\":\"AES/GCM/NoPadding\",\"nonce\":\"bm9uY2Vub25jZW5v\",\"ciphertext\":\"Y2lwaGVydGV4dA==\"}";

    @Test
    void validEnvelopeParses() throws Exception {
        EncryptedPayload payload = MetadataJson.envelope(VALID);

        assertEquals(1, payload.getVersion());
        assertEquals("AES/GCM/NoPadding", payload.getAlgorithm());
        assertEquals("bm9uY2Vub25jZW5v", payload.getNonce());
        assertEquals("Y2lwaGVydGV4dA==", payload.getCiphertext());
    }

    @Test
    void envelopesWithWrongShapesAreCorrupted() {
        for (String json : new String[]{
            VALID.replace("\"nonce\":\"bm9uY2Vub25jZW5v\"", "\"nonce\":5"),
            VALID.replace("\"version\":1", "\"version\":\"1\""),
            VALID.replace("\"version\":1", "\"version\":1.5"),
            VALID.replace("\"version\":1", "\"version\":99999999999"),
            VALID.replace(",\"ciphertext\":\"Y2lwaGVydGV4dA==\"", ""),
            VALID.replace("\"algorithm\":\"AES/GCM/NoPadding\"", "\"algorithm\":null"),
            "[" + VALID + "]",
            "\"text\"",
            "null",
            "",
            "{\"version\":1,"
        }) {
            VaultStorageException error = assertThrows(
                    VaultStorageException.class, () -> MetadataJson.envelope(json), json
            );
            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason(), json);
        }
    }

    @Test
    void extraMembersAreCorrupted() {
        for (String member : new String[]{
            "\"junk\":[1,2,3]",
            "\"junk\":{}",
            "\"junk\":{\"nested\":true}",
            "\"junk\":null",
            "\"junk\":\"text\"",
            "\"junk\":7"
        }) {
            assertCorrupted(VALID.substring(0, VALID.length() - 1) + "," + member + "}");
        }
    }

    @Test
    void duplicateMembersAreCorrupted() {
        assertCorrupted(VALID.replace("{", "{\"version\":1,"));
        assertCorrupted(VALID.replace("{", "{\"nonce\":\"bm9uY2Vub25jZW5v\","));
        assertCorrupted(VALID.substring(0, VALID.length() - 1) + ",\"ciphertext\":\"AA==\"}");
    }

    @Test
    void lenientSyntaxIsCorrupted() {
        for (String json : new String[]{
            "// comment\n" + VALID,
            "/* comment */" + VALID,
            VALID.replace("\"version\"", "version"),
            VALID.replace('"', '\''),
            VALID.replace("\"version\":1,", "\"version\":1;"),
            VALID + "{}",
            VALID + " x",
            VALID.substring(0, VALID.length() - 1) + ",}"
        }) {
            assertCorrupted(json);
        }
    }

    @Test
    void anEnvelopeWithAHugeExtraMemberIsRejected() {
        String junk = "1,".repeat(4 * 1024 * 1024);

        assertCorrupted(VALID.substring(0, VALID.length() - 1) + ",\"junk\":[" + junk + "1]}");
    }

    @Test
    void aLargeCiphertextStillParses() throws Exception {
        String ciphertext = "A".repeat(8 * 1024 * 1024);

        EncryptedPayload payload = MetadataJson.envelope(
                VALID.replace("Y2lwaGVydGV4dA==", ciphertext)
        );

        assertEquals(ciphertext.length(), payload.getCiphertext().length());
    }

    private static void assertCorrupted(String json) {
        VaultStorageException error = assertThrows(
                VaultStorageException.class,
                () -> MetadataJson.envelope(json),
                json.length() > 200 ? json.substring(0, 200) : json
        );
        assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
    }
}
