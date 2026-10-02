package com.fabianrodas.services;

import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts manifest decryptions, to catch operations that re-read metadata. */
final class CountingManifests extends ManifestRepository {

    final AtomicInteger loads = new AtomicInteger();

    CountingManifests(VaultContext vault) {
        super(vault);
    }

    @Override
    public UserManifest load(UUID userId, UUID manifestId, byte[] userMasterKey) throws VaultStorageException {
        loads.incrementAndGet();
        return super.load(userId, manifestId, userMasterKey);
    }
}
