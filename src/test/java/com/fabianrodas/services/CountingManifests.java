package com.fabianrodas.services;

import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts manifest decryptions and saves, to catch operations that re-read or rewrite metadata. */
final class CountingManifests extends ManifestRepository {

    final AtomicInteger loads = new AtomicInteger();
    final AtomicInteger saves = new AtomicInteger();

    CountingManifests(VaultContext vault) {
        super(vault);
    }

    @Override
    public UserManifest load(UUID userId, UUID manifestId, byte[] userMasterKey) throws VaultStorageException {
        loads.incrementAndGet();
        return super.load(userId, manifestId, userMasterKey);
    }

    @Override
    public void save(UserManifest manifest, UUID manifestId, byte[] userMasterKey) throws VaultStorageException {
        saves.incrementAndGet();
        super.save(manifest, manifestId, userMasterKey);
    }
}
