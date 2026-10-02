package com.fabianrodas.repositories;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedFilesTest {

    @TempDir
    Path dir;

    @Test
    void fileAtTheLimitIsRead() throws Exception {
        Path file = Files.writeString(dir.resolve("meta"), "x".repeat(1024));

        assertEquals(1024, BoundedFiles.readUtf8(file, 1024).length());
    }

    @Test
    void fileOverTheLimitIsRejectedBeforeItIsRead() throws Exception {
        // Reading 3 GiB into a String would fail with OutOfMemoryError, not this.
        Path file = sparseFile(dir.resolve("huge"), 3L << 30);

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> BoundedFiles.readUtf8(file, 1024)
        );

        assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
    }

    @Test
    void writesOverTheLimitAreRefused() {
        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> BoundedFiles.requireWithin(new byte[1025], 1024)
        );

        assertEquals(VaultStorageException.Reason.TOO_LARGE, error.getReason());
        assertDoesNotThrow(() -> BoundedFiles.requireWithin(new byte[1024], 1024));
    }

    /** A file that reports {@code size} bytes without using that much disk. */
    static Path sparseFile(Path file, long size) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[1]));
        }

        return file;
    }
}
