package com.fabianrodas.repositories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWriterTest {

    private final AtomicFileWriter writer = new AtomicFileWriter();

    @Test
    void atomicWriterReplacesExistingFile(@TempDir Path tempDir) throws IOException {
        Path destination = tempDir.resolve("users.enc");
        Files.writeString(destination, "old", UTF_8);

        writer.write(destination, "new".getBytes(UTF_8));

        assertEquals("new", Files.readString(destination, UTF_8));
    }

    @Test
    void successfulWriteLeavesNoTemporaryFile(@TempDir Path tempDir) throws IOException {
        writer.write(tempDir.resolve("users.enc"), "data".getBytes(UTF_8));

        assertEquals(List.of("users.enc"), fileNames(tempDir));
    }

    @Test
    void failedReplaceKeepsDestinationAndRemovesTemporaryFile(@TempDir Path tempDir)
            throws IOException {
        Path destination = tempDir.resolve("manifest.enc");
        Files.createDirectories(destination.resolve("occupied"));

        assertThrows(
                IOException.class,
                () -> writer.write(destination, "data".getBytes(UTF_8))
        );

        assertEquals(List.of("manifest.enc"), fileNames(tempDir));
        assertEquals(List.of("occupied"), fileNames(destination));
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
