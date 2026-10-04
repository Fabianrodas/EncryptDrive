package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.ManifestEntry;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/*
 * Opt-in memory check (needs ~3 GiB of temporary disk space):
 *
 *   mvn -Dtest=LargeFileStreamingTest -Dencryptdrive.largeFileCheck=true -DargLine=-Xmx256m test
 *
 * A 1 GiB file must round-trip through a 256 MiB heap, through the crypto
 * service alone and through the whole vault (import, then export). Mandatory
 * before every release-candidate tag (spec 21.7), optional in routine CI.
 */
@EnabledIfSystemProperty(named = "encryptdrive.largeFileCheck", matches = "true")
class LargeFileStreamingTest {

    private static final long ONE_GIB = 1L << 30;

    @Test
    void oneGibibyteRoundTripsWithinASmallHeap(@TempDir Path dir) throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() <= 300L * 1024 * 1024,
                "run with -DargLine=-Xmx256m");

        byte[] fileKey = new byte[32];
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(fileKey);
        new SecureRandom().nextBytes(nonce);
        byte[] aad = "EncryptDrive|file|v1|vault|user|large".getBytes(UTF_8);

        Path source = randomFile(dir.resolve("large.bin"), ONE_GIB);

        StreamingFileCryptoService crypto = new StreamingFileCryptoService();
        crypto.encrypt(source, dir.resolve("large.edv"), fileKey, nonce, aad);
        crypto.decrypt(dir.resolve("large.edv"), dir.resolve("large.out"), fileKey, nonce, aad);

        assertArrayEquals(sha256(source), sha256(dir.resolve("large.out")));
    }

    @Test
    void oneGibibyteRoundTripsThroughTheVaultWithinASmallHeap(@TempDir Path dir) throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() <= 300L * 1024 * 1024, "run with -DargLine=-Xmx256m");
        Path source = randomFile(dir.resolve("large.bin"), ONE_GIB);

        try (TestVault vault = new TestVault(dir)) {
            FileService files = vault.files(vault.register("alice"));
            ManifestEntry entry = files.importFile(source, files.rootFolderId());
            Path out = dir.resolve("large.out");
            files.exportEntry(entry.getEntryId(), out);

            assertArrayEquals(sha256(source), sha256(out));
        }
    }

    private static Path randomFile(Path file, long size) throws Exception {
        Random random = new Random(42);
        byte[] chunk = new byte[1024 * 1024];

        try (OutputStream out = Files.newOutputStream(file)) {
            for (long written = 0; written < size; written += chunk.length) {
                random.nextBytes(chunk);
                out.write(chunk);
            }
        }

        return file;
    }

    private static byte[] sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }

        return digest.digest();
    }
}
