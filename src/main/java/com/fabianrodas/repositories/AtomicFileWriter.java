package com.fabianrodas.repositories;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Replaces a file via a same-directory temporary file named
 * {@code <name>.<random>.tmp}, so a crash never leaves a partial file at
 * the destination.
 */
public final class AtomicFileWriter {

    public static final String TEMP_SUFFIX = ".tmp";

    public void write(Path destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(
                destination.toAbsolutePath().getParent(),
                destination.getFileName() + ".",
                TEMP_SUFFIX
        );

        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);

                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }

                channel.force(true);
            }

            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );

            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }

        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
