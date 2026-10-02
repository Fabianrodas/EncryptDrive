package com.fabianrodas.repositories;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.KdfConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;

/**
 * Strict reading of metadata that is parsed before it is authenticated:
 * vault.json and the outer encrypted envelopes. Every member must exist with
 * its exact JSON type. Gson's reflective binding would accept "65536" for a
 * number and silently default missing members.
 */
final class MetadataJson {

    private MetadataJson() {
    }

    static JsonObject object(String json) throws VaultStorageException {
        try {
            return object(JsonParser.parseString(json));
        } catch (JsonParseException e) {
            throw corrupted(e);
        }
    }

    static EncryptedPayload envelope(String json) throws VaultStorageException {
        return envelope(object(json));
    }

    static EncryptedPayload envelope(JsonObject parent, String member) throws VaultStorageException {
        return envelope(object(parent.get(member)));
    }

    static KdfConfig kdf(JsonObject parent, String member) throws VaultStorageException {
        JsonObject kdf = object(parent.get(member));

        return new KdfConfig(
                string(kdf, "algorithm"),
                integer(kdf, "memoryKiB"),
                integer(kdf, "iterations"),
                integer(kdf, "parallelism"),
                string(kdf, "salt")
        );
    }

    static String string(JsonObject object, String member) throws VaultStorageException {
        JsonPrimitive value = primitive(object, member);

        if (!value.isString()) {
            throw corrupted(null);
        }

        return value.getAsString();
    }

    static int integer(JsonObject object, String member) throws VaultStorageException {
        JsonPrimitive value = primitive(object, member);

        if (!value.isNumber()) {
            throw corrupted(null);
        }

        try {
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw corrupted(e);
        }
    }

    private static EncryptedPayload envelope(JsonObject envelope) throws VaultStorageException {
        return new EncryptedPayload(
                integer(envelope, "version"),
                string(envelope, "algorithm"),
                string(envelope, "nonce"),
                string(envelope, "ciphertext")
        );
    }

    private static JsonObject object(JsonElement element) throws VaultStorageException {
        if (element == null || !element.isJsonObject()) {
            throw corrupted(null);
        }

        return element.getAsJsonObject();
    }

    private static JsonPrimitive primitive(JsonObject object, String member) throws VaultStorageException {
        JsonElement value = object.get(member);

        if (value == null || !value.isJsonPrimitive()) {
            throw corrupted(null);
        }

        return value.getAsJsonPrimitive();
    }

    private static VaultStorageException corrupted(Exception cause) {
        return new VaultStorageException(VaultStorageException.Reason.CORRUPTED, cause);
    }
}
