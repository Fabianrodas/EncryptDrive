package com.fabianrodas.repositories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlobRepositoryTest {

    private static final UUID BLOB_ID = UUID.fromString("3f0b8c55-0f7c-4a39-9d53-5a54e5d0a1b2");

    @TempDir
    Path vaultRoot;

    private final BlobRepository repository = new BlobRepository();

    @Test
    void blobsAreShardedByTheFirstTwoHexCharacters() {
        assertEquals(
                vaultRoot.resolve("storage/blobs/3f/3f0b8c55-0f7c-4a39-9d53-5a54e5d0a1b2.edv"),
                repository.blobPath(vaultRoot, BLOB_ID)
        );
    }

    @Test
    void newPartSitsNextToItsBlobInAnExistingShard() throws Exception {
        Path part = repository.newPart(vaultRoot, BLOB_ID);

        assertEquals(
                vaultRoot.resolve("storage/blobs/3f/3f0b8c55-0f7c-4a39-9d53-5a54e5d0a1b2.edv.part"),
                part
        );
        assertTrue(Files.isDirectory(part.getParent()));
    }

    @Test
    void commitRenamesThePartToTheBlob() throws Exception {
        Path part = repository.newPart(vaultRoot, BLOB_ID);
        Files.writeString(part, "ciphertext", UTF_8);

        repository.commit(vaultRoot, BLOB_ID);

        assertFalse(Files.exists(part));
        assertEquals("ciphertext", Files.readString(repository.blobPath(vaultRoot, BLOB_ID), UTF_8));
        assertEquals(10, repository.size(vaultRoot, BLOB_ID));
    }

    @Test
    void deleteRemovesTheBlob() throws Exception {
        Files.writeString(repository.newPart(vaultRoot, BLOB_ID), "ciphertext", UTF_8);
        repository.commit(vaultRoot, BLOB_ID);

        repository.delete(vaultRoot, BLOB_ID);

        assertFalse(Files.exists(repository.blobPath(vaultRoot, BLOB_ID)));
        assertEquals(0, repository.size(vaultRoot, BLOB_ID));
    }
}
