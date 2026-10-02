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
}
