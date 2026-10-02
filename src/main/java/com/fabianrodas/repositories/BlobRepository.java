package com.fabianrodas.repositories;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Immutable encrypted blobs at {@code storage/blobs/<shard>/<blobId>.edv},
 * where the shard is the first two hex characters of the blob id. Blobs are
 * written as {@code .part} files and only renamed once complete.
 */
public class BlobRepository {

    public static final String BLOB_EXTENSION = ".edv";
    public static final String PART_SUFFIX = ".part";

    public Path blobPath(Path vaultRoot, UUID blobId) {
        String name = blobId.toString();

        return vaultRoot.resolve(VaultRepository.BLOBS_DIR)
                .resolve(name.replace("-", "").substring(0, 2))
                .resolve(name + BLOB_EXTENSION);
    }

    /** Returns the part path for a new blob, creating its shard directory. */
    public Path newPart(Path vaultRoot, UUID blobId) throws IOException {
        Path part = partPath(vaultRoot, blobId);
        Files.createDirectories(part.getParent());
        return part;
    }

    /** Makes a complete part visible under its final blob name. */
    public void commit(Path vaultRoot, UUID blobId) throws IOException {
        Path part = partPath(vaultRoot, blobId);
        Path blob = blobPath(vaultRoot, blobId);

        try {
            Files.move(part, blob, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, blob);
        }
    }

    public void delete(Path vaultRoot, UUID blobId) throws IOException {
        Files.deleteIfExists(blobPath(vaultRoot, blobId));
    }

    /** Encrypted size on disk, or 0 when the blob is missing. */
    public long size(Path vaultRoot, UUID blobId) throws IOException {
        try {
            return Files.size(blobPath(vaultRoot, blobId));
        } catch (NoSuchFileException e) {
            return 0;
        }
    }

    private Path partPath(Path vaultRoot, UUID blobId) {
        Path blob = blobPath(vaultRoot, blobId);
        return blob.resolveSibling(blob.getFileName() + PART_SUFFIX);
    }
}
