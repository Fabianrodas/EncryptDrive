package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.EncryptedFileDescriptor;
import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.CryptoException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StreamingFileCryptoServiceTest {

    private static final byte[] AAD = "EncryptDrive|file|v1|vault|user|file".getBytes(UTF_8);
    private static final byte[] MARKER
            = "EncryptDrive-known-plaintext-marker-0123456789-abcdefghijklmnop!".getBytes(US_ASCII);

    @TempDir
    Path dir;

    private final StreamingFileCryptoService crypto = new StreamingFileCryptoService();
    private final byte[] fileKey = random(32);
    private final byte[] nonce = random(12);

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1024 * 1024, 32 * 1024 * 1024 + 7})
    void decryptingTheBlobRestoresTheExactFile(int size) throws Exception {
        Path source = deterministicFile("source.bin", size);

        EncryptedFileDescriptor descriptor = crypto.encrypt(source, dir.resolve("blob"), fileKey, nonce, AAD);
        crypto.decrypt(dir.resolve("blob"), dir.resolve("export.part"), fileKey, nonce, AAD);

        assertEquals(size, descriptor.plainSize());
        assertEquals(size + 16L, descriptor.encryptedSize());
        assertEquals(size + 16L, Files.size(dir.resolve("blob")));
        assertEquals(sha256(source), sha256(dir.resolve("export.part")));
    }

    @Test
    void encryptReportsProgressUpToTheFileSize() throws Exception {
        int size = 5 * 64 * 1024 + 123;
        Path source = deterministicFile("source.bin", size);
        java.util.List<Long> reported = new java.util.ArrayList<>();

        crypto.encrypt(source, dir.resolve("blob"), fileKey, nonce, AAD, reported::add);

        assertEquals((long) size, reported.get(reported.size() - 1));
        for (int i = 1; i < reported.size(); i++) {
            assertTrue(reported.get(i) > reported.get(i - 1), "progress must grow");
        }
    }

    @Test
    void blobHidesThePlaintext() throws Exception {
        Path source = dir.resolve("marker.bin");
        try (OutputStream out = Files.newOutputStream(source)) {
            for (int i = 0; i < 1000; i++) {
                out.write(MARKER);
            }
        }

        crypto.encrypt(source, dir.resolve("blob"), fileKey, nonce, AAD);

        byte[] blob = Files.readAllBytes(dir.resolve("blob"));
        assertFalse(new String(blob, US_ASCII).contains(new String(MARKER, US_ASCII)));
        assertFalse(sha256(source).equals(sha256(dir.resolve("blob"))));
    }

    @Test
    void blobIsStandardGcmReadableByTheJdkImplementation() throws Exception {
        Path source = dir.resolve("small.txt");
        Files.write(source, MARKER);

        crypto.encrypt(source, dir.resolve("blob"), fileKey, nonce, AAD);

        EncryptedPayload asPayload = new EncryptedPayload(
                1,
                "AES/GCM/NoPadding",
                Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(Files.readAllBytes(dir.resolve("blob")))
        );
        assertArrayEquals(MARKER, new AesGcmService().decrypt(asPayload, fileKey, AAD));
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "middle", "tag"})
    void tamperedBlobFailsAndLeavesNoPlaintext(String position) throws Exception {
        Path source = deterministicFile("source.bin", 3 * 64 * 1024 + 100);
        crypto.encrypt(source, dir.resolve("blob"), fileKey, nonce, AAD);
        Path tampered = Files.copy(dir.resolve("blob"), dir.resolve("tampered"),
                StandardCopyOption.REPLACE_EXISTING);
        long length = Files.size(tampered);
        flipByte(tampered, switch (position) {
            case "start" -> 0;
            case "middle" -> length / 2;
            default -> length - 1;
        });

        Path part = dir.resolve("export.part");
        assertThrows(CryptoException.class,
                () -> crypto.decrypt(tampered, part, fileKey, nonce, AAD));

        assertFalse(Files.exists(part));
    }

    @Test
    void blobIsBoundToItsContext() throws Exception {
        Path source = deterministicFile("source.bin", 1000);
        crypto.encrypt(source, dir.resolve("blob"), fileKey, nonce, AAD);

        byte[] otherFile = "EncryptDrive|file|v1|vault|user|other".getBytes(UTF_8);
        Path part = dir.resolve("export.part");

        assertThrows(CryptoException.class,
                () -> crypto.decrypt(dir.resolve("blob"), part, fileKey, nonce, otherFile));
        assertThrows(CryptoException.class,
                () -> crypto.decrypt(dir.resolve("blob"), part, random(32), nonce, AAD));
        assertFalse(Files.exists(part));
    }

    private Path deterministicFile(String name, int size) throws IOException {
        Path file = dir.resolve(name);
        Random random = new Random(size);
        byte[] chunk = new byte[64 * 1024];

        try (OutputStream out = Files.newOutputStream(file)) {
            for (int remaining = size; remaining > 0; remaining -= chunk.length) {
                random.nextBytes(chunk);
                out.write(chunk, 0, Math.min(chunk.length, remaining));
            }
        }

        return file;
    }

    private static void flipByte(Path file, long position) throws IOException {
        try (FileChannel channel = FileChannel.open(file,
                java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer one = java.nio.ByteBuffer.allocate(1);
            channel.read(one, position);
            one.put(0, (byte) (one.get(0) ^ 0x01)).rewind();
            channel.write(one, position);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
