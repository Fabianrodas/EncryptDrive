package com.fabianrodas.repositories;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.KdfConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/**
 * Strict reading of metadata that is parsed before it is authenticated:
 * vault.json and the outer encrypted envelopes. Every member must exist with
 * its exact JSON type. Gson's reflective binding would accept "65536" for a
 * number and silently default missing members.
 *
 * <p>The outer envelopes of users.enc and manifests can be tens of MiB, so
 * they are read as a stream and never as a tree: nothing is allocated for a
 * tampered member, which is rejected the moment it is seen.
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

    /**
     * Reads an envelope that must be exactly one object with the four members
     * version, algorithm, nonce and ciphertext, each once and of its exact
     * type. Any other member, nested value, null, duplicate or trailing data
     * is rejected without being read.
     */
    static EncryptedPayload envelope(String json) throws VaultStorageException {
        Integer version = null;
        String algorithm = null;
        String nonce = null;
        String ciphertext = null;

        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();

            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "version" -> {
                        requireAbsent(version);
                        requireNext(reader, JsonToken.NUMBER);
                        version = exactInt(reader.nextString());
                    }
                    case "algorithm" -> {
                        requireAbsent(algorithm);
                        requireNext(reader, JsonToken.STRING);
                        algorithm = reader.nextString();
                    }
                    case "nonce" -> {
                        requireAbsent(nonce);
                        requireNext(reader, JsonToken.STRING);
                        nonce = reader.nextString();
                    }
                    case "ciphertext" -> {
                        requireAbsent(ciphertext);
                        requireNext(reader, JsonToken.STRING);
                        ciphertext = reader.nextString();
                    }
                    default -> throw corrupted(null);
                }
            }

            reader.endObject();
            requireNext(reader, JsonToken.END_DOCUMENT);

        } catch (IOException | IllegalStateException | ArithmeticException | NumberFormatException e) {
            throw corrupted(e);
        }

        if (version == null || algorithm == null || nonce == null || ciphertext == null) {
            throw corrupted(null);
        }

        return new EncryptedPayload(version, algorithm, nonce, ciphertext);
    }

    private static void requireAbsent(Object seen) throws VaultStorageException {
        if (seen != null) {
            throw corrupted(null);
        }
    }

    private static void requireNext(JsonReader reader, JsonToken expected)
            throws IOException, VaultStorageException {

        if (reader.peek() != expected) {
            throw corrupted(null);
        }
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
            return exactInt(value.getAsString());
        } catch (ArithmeticException | NumberFormatException e) {
            throw corrupted(e);
        }
    }

    private static int exactInt(String number) {
        return new BigDecimal(number).intValueExact();
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
