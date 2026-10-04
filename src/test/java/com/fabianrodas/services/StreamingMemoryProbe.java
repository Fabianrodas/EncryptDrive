package com.fabianrodas.services;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.security.SensitiveBytes;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/**
 * Runs a file of the given size through FileService import and export in a
 * JVM started with a small heap (see StreamingMemoryTest). Avoids Argon2 so
 * the heap only has to hold streaming buffers. Prints MATCH and exits 0 when
 * the export equals the source; prints MISMATCH and exits 1 otherwise.
 * Runs on the classpath, so it must not touch JavaFX.
 */
public final class StreamingMemoryProbe {

    private StreamingMemoryProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        long size = Long.parseLong(args[1]);
        Path root = dir.resolve("vault");
        Files.createDirectories(root.resolve(".encryptdrive").resolve("manifests"));
        Files.createDirectories(root.resolve("storage").resolve("blobs"));

        byte[] userMasterKey = new byte[32];
        new SecureRandom().nextBytes(userMasterKey);
        byte[] registryKey = new byte[32];
        new SecureRandom().nextBytes(registryKey);
        // Never closed: FileService refuses a vault whose registry key is destroyed.
        VaultContext vault = new VaultContext(root, UUID.randomUUID().toString(), 1,
                Instant.now().toString(), SensitiveBytes.wrap(registryKey), () -> { });
        UUID userId = UUID.randomUUID();
        UUID manifestId = UUID.randomUUID();
        new ManifestRepository(vault).save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        FileService files = new FileService(vault, new UserSessionIdentity(userId, "Probe", "probe", manifestId),
                () -> SensitiveBytes.copyOf(userMasterKey));

        Path source = dir.resolve("large.bin");
        Random random = new Random(7);
        byte[] chunk = new byte[1 << 20];

        try (OutputStream out = Files.newOutputStream(source)) {
            for (long written = 0; written < size; written += chunk.length) {
                random.nextBytes(chunk);
                out.write(chunk);
            }
        }

        ManifestEntry entry = files.importFile(source, files.rootFolderId());
        Path exported = dir.resolve("large.out");
        files.exportEntry(entry.getEntryId(), exported);

        boolean match = Arrays.equals(sha256(source), sha256(exported));
        System.out.println(match ? "MATCH" : "MISMATCH");
        System.exit(match ? 0 : 1);
    }

    static byte[] sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }

        return digest.digest();
    }
}
