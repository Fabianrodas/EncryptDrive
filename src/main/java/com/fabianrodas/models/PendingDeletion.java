package com.fabianrodas.models;

import java.util.UUID;

/**
 * A blob whose manifest entry is already gone but which may still be on
 * disk. Stored only inside the encrypted manifest.
 */
public record PendingDeletion(UUID blobId, String queuedAt) {
}
