package com.fabianrodas.repositories;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Size limits for vault metadata. A file over its limit is treated as
 * corrupted before it is read, so a tampered vault cannot make EncryptDrive
 * allocate memory for it; a write over the limit is refused, so EncryptDrive
 * never produces metadata it would reject on the next read.
 */
final class BoundedFiles {

    private BoundedFiles() {
    }

    static String readUtf8(Path file, long maxBytes) throws IOException, VaultStorageException {
        if (Files.size(file) > maxBytes) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);
        }

        try (InputStream in = Files.newInputStream(file)) {
            // One byte more than allowed catches a file that grew after the size check.
            byte[] bytes = in.readNBytes(Math.toIntExact(maxBytes) + 1);

            if (bytes.length > maxBytes) {
                throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);
            }

            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static void requireWithin(byte[] bytes, long maxBytes) throws VaultStorageException {
        if (bytes.length > maxBytes) {
            throw new VaultStorageException(VaultStorageException.Reason.TOO_LARGE);
        }
    }
}
