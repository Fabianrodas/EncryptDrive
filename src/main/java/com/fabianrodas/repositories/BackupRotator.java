package com.fabianrodas.repositories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps {@code <name>.1 .. <name>.<generations>} copies of an encrypted
 * metadata file. The bytes are copied verbatim, so backups stay ciphertext.
 */
public final class BackupRotator {

    private final AtomicFileWriter writer = new AtomicFileWriter();

    public void rotate(Path encryptedFile, Path backupDirectory, int generations)
            throws IOException {

        if (!Files.isRegularFile(encryptedFile)) {
            return;
        }

        Files.createDirectories(backupDirectory);
        String name = encryptedFile.getFileName().toString();

        for (int generation = generations - 1; generation >= 1; generation--) {
            Path older = backupDirectory.resolve(name + "." + generation);

            if (Files.exists(older)) {
                Files.move(
                        older,
                        backupDirectory.resolve(name + "." + (generation + 1)),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        }

        writer.write(
                backupDirectory.resolve(name + ".1"),
                Files.readAllBytes(encryptedFile)
        );
    }
}
