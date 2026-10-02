package com.fabianrodas.repositories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps {@code <name>.1 .. <name>.<generations>} copies of an encrypted
 * metadata file. The bytes are copied verbatim, so backups stay ciphertext.
 */
public final class BackupRotator {

    private static final AtomicBoolean RECOVERED = new AtomicBoolean();

    private final AtomicFileWriter writer;

    public BackupRotator() {
        this(new AtomicFileWriter());
    }

    BackupRotator(AtomicFileWriter writer) {
        this.writer = writer;
    }

    /** Reads and authenticates one encrypted metadata file. */
    @FunctionalInterface
    public interface MetadataReader<T> {
        T read(Path file) throws VaultStorageException;
    }

    /** True once after {@link #recover} restored a backup, then false until the next one. */
    public static boolean takeRecoveryNotice() {
        return RECOVERED.getAndSet(false);
    }

    /**
     * Tries {@code <name>.1 .. <name>.<generations>} in order and restores the
     * first one the reader accepts (authenticated decryption plus parsing) as
     * the current file. Backups that fail authentication are skipped, never
     * used.
     */
    public <T> Optional<T> recover(
            Path currentFile,
            Path backupDirectory,
            int generations,
            MetadataReader<T> reader
    ) throws VaultStorageException {

        String name = currentFile.getFileName().toString();

        for (int generation = 1; generation <= generations; generation++) {
            Path backup = backupDirectory.resolve(name + "." + generation);

            if (!Files.isRegularFile(backup)) {
                continue;
            }

            T value;

            try {
                value = reader.read(backup);
            } catch (VaultStorageException e) {
                if (e.getReason() == VaultStorageException.Reason.IO) {
                    throw e;
                }

                continue;
            }

            try {
                writer.copy(backup, currentFile);
            } catch (IOException e) {
                throw new VaultStorageException(VaultStorageException.Reason.IO, e);
            }

            RECOVERED.set(true);
            return Optional.of(value);
        }

        return Optional.empty();
    }

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

        writer.copy(encryptedFile, backupDirectory.resolve(name + ".1"));
    }

    /**
     * Replaces every backup generation of the file called {@code name} with
     * {@code content}, so no older state stays recoverable after a
     * security-sensitive change.
     */
    public void reseed(String name, byte[] content, Path backupDirectory, int generations)
            throws IOException {

        Files.createDirectories(backupDirectory);

        for (int generation = 1; generation <= generations; generation++) {
            writer.write(backupDirectory.resolve(name + "." + generation), content);
        }
    }
}
