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

    @FunctionalInterface
    private interface Content {
        void writeTo(FileChannel channel) throws IOException;
    }

    public void write(Path destination, byte[] bytes) throws IOException {
        replace(destination, channel -> {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        });
    }

    /** Like {@link #write}, streaming {@code source} instead of holding it in memory. */
    public void copy(Path source, Path destination) throws IOException {
        replace(destination, channel -> {
            try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ)) {
                long size = in.size();
                long position = 0;

                while (position < size) {
                    long transferred = in.transferTo(position, size - position, channel);

                    if (transferred <= 0) {
                        throw new IOException("Source shrank while it was being copied: " + source);
                    }

                    position += transferred;
                }
            }
        });
    }

    private void replace(Path destination, Content content) throws IOException {
        Path temporary = Files.createTempFile(
                destination.toAbsolutePath().getParent(),
                destination.getFileName() + ".",
                TEMP_SUFFIX
        );

        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                content.writeTo(channel);
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
