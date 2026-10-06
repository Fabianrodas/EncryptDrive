package com.fabianrodas.repositories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.KdfConfig;
import com.fabianrodas.models.VaultHeader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultRepositoryTest {

    private static final String VAULT_ID = "3f0b8c55-0f7c-4a39-9d53-5a54e5d0a1b2";

    private final VaultRepository repository = new VaultRepository();

    @Test
    void writeThenReadHeaderRoundTrips(@TempDir Path tempDir) throws Exception {
        repository.writeHeader(tempDir, header(1));

        VaultHeader read = repository.readHeader(tempDir);

        assertEquals(1, read.getFormatVersion());
        assertEquals(VAULT_ID, read.getVaultId());
        assertEquals("2026-09-30T17:00:00Z", read.getCreatedAt());
        assertEquals("Argon2id", read.getKdf().getAlgorithm());
        assertEquals(65536, read.getKdf().getMemoryKiB());
        assertEquals(3, read.getKdf().getIterations());
        assertEquals(1, read.getKdf().getParallelism());
        assertEquals("c2FsdHNhbHRzYWx0c2FsdA==", read.getKdf().getSalt());
        assertEquals("bm9uY2Vub25jZW5v", read.getWrappedRegistryKey().getNonce());
        assertEquals("Y2lwaGVydGV4dA==", read.getWrappedRegistryKey().getCiphertext());
    }

    @Test
    void headerIsWrittenWithTheSpecifiedJsonFields(@TempDir Path tempDir) throws Exception {
        repository.writeHeader(tempDir, header(1));

        JsonObject json = JsonParser.parseString(Files.readString(
                tempDir.resolve(".encryptdrive").resolve("vault.json"), UTF_8
        )).getAsJsonObject();

        assertEquals(
                Set.of("formatVersion", "vaultId", "createdAt", "kdf", "wrappedRegistryKey"),
                json.keySet()
        );
        assertEquals(
                Set.of("algorithm", "memoryKiB", "iterations", "parallelism", "salt"),
                json.getAsJsonObject("kdf").keySet()
        );
        assertEquals(
                "AES/GCM/NoPadding",
                json.getAsJsonObject("wrappedRegistryKey").get("algorithm").getAsString()
        );
    }

    @Test
    void missingHeaderThrowsVaultStorageException(@TempDir Path tempDir) {
        VaultStorageException error = assertThrows(
                VaultStorageException.class,
                () -> repository.readHeader(tempDir)
        );

        assertEquals(VaultStorageException.Reason.NOT_FOUND, error.getReason());
    }

    @Test
    void malformedHeaderThrowsVaultStorageException(@TempDir Path tempDir) throws IOException {
        for (String content : new String[]{
            "{\"formatVersion\": 1, \"vaultId\":",
            "{}",
            "{\"formatVersion\": 1, \"vaultId\": \"not-a-uuid\"}",
            ""
        }) {
            writeRawHeader(tempDir, content);

            VaultStorageException error = assertThrows(
                    VaultStorageException.class,
                    () -> repository.readHeader(tempDir),
                    content
            );

            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason(), content);
        }
    }

    @Test
    void unsupportedFormatVersionIsRejected(@TempDir Path tempDir) throws Exception {
        repository.writeHeader(tempDir, header(2));

        VaultStorageException error = assertThrows(
                VaultStorageException.class,
                () -> repository.readHeader(tempDir)
        );

        assertEquals(VaultStorageException.Reason.UNSUPPORTED_VERSION, error.getReason());
    }

    @Test
    void typeConfusedHeadersAreCorrupted(@TempDir Path tempDir) throws Exception {
        repository.writeHeader(tempDir, header(1));
        String valid = Files.readString(tempDir.resolve(".encryptdrive").resolve("vault.json"), UTF_8);

        for (String content : new String[]{
            valid.replace("\"memoryKiB\": 65536", "\"memoryKiB\": \"65536\""),
            valid.replace("\"createdAt\": \"2026-09-30T17:00:00Z\"", "\"createdAt\": \"yesterday\""),
            valid.replace("\"formatVersion\": 1", "\"formatVersion\": \"1\""),
            valid.replaceFirst("\"kdf\": \\{", "\"kdf\": [], \"unused\": {")
        }) {
            writeRawHeader(tempDir, content);

            VaultStorageException error = assertThrows(
                    VaultStorageException.class, () -> repository.readHeader(tempDir), content
            );
            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason(), content);
        }
    }

    @Test
    void newerFormatVersionIsReportedEvenWhenTheRestIsUnknown(@TempDir Path tempDir) throws Exception {
        writeRawHeader(tempDir, "{\"formatVersion\": 2, \"somethingNew\": true}");

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.readHeader(tempDir)
        );

        assertEquals(VaultStorageException.Reason.UNSUPPORTED_VERSION, error.getReason());
    }

    @Test
    void oversizedHeaderIsRejectedBeforeParsing(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".encryptdrive"));
        BoundedFilesTest.sparseFile(tempDir.resolve(".encryptdrive").resolve("vault.json"), 3L << 30);

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.readHeader(tempDir)
        );

        assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
    }

    @Test
    void headerOfExactlyTheLimitStillReads(@TempDir Path tempDir) throws Exception {
        repository.writeHeader(tempDir, header(1));
        Path file = tempDir.resolve(".encryptdrive").resolve("vault.json");
        String json = Files.readString(file, UTF_8);
        Files.writeString(file, json + " ".repeat((int) VaultRepository.MAX_HEADER_BYTES - json.length()), UTF_8);

        assertEquals(VaultRepository.MAX_HEADER_BYTES, Files.size(file));
        assertEquals(VAULT_ID, repository.readHeader(tempDir).getVaultId());

        Files.writeString(file, " ", UTF_8, StandardOpenOption.APPEND);
        assertEquals(
                VaultStorageException.Reason.CORRUPTED,
                assertThrows(VaultStorageException.class, () -> repository.readHeader(tempDir)).getReason()
        );
    }

    private static VaultHeader header(int formatVersion) {
        return new VaultHeader(
                formatVersion,
                UUID.fromString(VAULT_ID).toString(),
                "2026-09-30T17:00:00Z",
                new KdfConfig("Argon2id", 65536, 3, 1, "c2FsdHNhbHRzYWx0c2FsdA=="),
                new EncryptedPayload(
                        1, "AES/GCM/NoPadding", "bm9uY2Vub25jZW5v", "Y2lwaGVydGV4dA=="
                )
        );
    }

    private static void writeRawHeader(Path vaultRoot, String content) throws IOException {
        Path metaDir = Files.createDirectories(vaultRoot.resolve(".encryptdrive"));
        Files.writeString(metaDir.resolve("vault.json"), content, UTF_8);
    }
}
