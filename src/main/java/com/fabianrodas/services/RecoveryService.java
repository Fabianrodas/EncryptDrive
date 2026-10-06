package com.fabianrodas.services;

import com.fabianrodas.repositories.AtomicFileWriter;
import com.fabianrodas.repositories.BackupRotator;
import com.fabianrodas.repositories.BlobRepository;
import com.fabianrodas.repositories.VaultRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Integrity housekeeping. Damaged metadata is recovered by the repositories
 * from backups that pass authenticated decryption; this service reports that
 * a recovery happened and removes stale partial files left by interrupted
 * writes. Damaged file content is only ever reported, never repaired.
 */
public final class RecoveryService {

    public static final Duration STALE_AFTER = Duration.ofHours(24);

    /** Same-directory temp files written by AtomicFileWriter: {@code <name>.<digits>.tmp}. */
    private static final Pattern METADATA_TEMP
            = Pattern.compile(".+\\.\\d+" + Pattern.quote(AtomicFileWriter.TEMP_SUFFIX));

    private static final String BLOB_PART
            = BlobRepository.BLOB_EXTENSION + BlobRepository.PART_SUFFIX;

    /** True once after vault metadata was restored from an automatic backup. */
    public static boolean takeRecoveryNotice() {
        return BackupRotator.takeRecoveryNotice();
    }

    /**
     * Deletes EncryptDrive's own partial files older than 24 hours: blob parts
     * under {@code storage/blobs} and metadata temp files under
     * {@code .encryptdrive}. Nothing else is touched.
     */
    public int cleanStalePartials(Path vaultRoot, Instant now) throws IOException {
        Instant cutoff = now.minus(STALE_AFTER);

        return deleteStale(vaultRoot.resolve(VaultRepository.BLOBS_DIR), name -> name.endsWith(BLOB_PART), cutoff)
                + deleteStale(VaultRepository.metaDir(vaultRoot),
                        name -> METADATA_TEMP.matcher(name).matches(), cutoff);
    }

    private static int deleteStale(Path directory, Predicate<String> ownName, Instant cutoff)
            throws IOException {

        if (!Files.isDirectory(directory)) {
            return 0;
        }

        List<Path> stale;

        try (Stream<Path> files = Files.walk(directory)) {
            stale = files.filter(Files::isRegularFile)
                    .filter(file -> ownName.test(file.getFileName().toString()))
                    .filter(file -> lastModified(file).isBefore(cutoff))
                    .toList();
        }

        for (Path file : stale) {
            Files.deleteIfExists(file);
        }

        return stale.size();
    }

    private static Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.MAX;
        }
    }
}
