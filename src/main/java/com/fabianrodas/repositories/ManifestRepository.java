package com.fabianrodas.repositories;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.PendingDeletion;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.CryptoConstants;
import com.fabianrodas.security.CryptoException;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code manifests/<manifestId>.enc}: one user's logical filesystem,
 * encrypted under that user's master key and bound to the user by AAD.
 */
public class ManifestRepository {

    public static final int FORMAT_VERSION = 1;

    /** Spec limit for one manifest and each backup; checked before reading and before writing. */
    public static final long MAX_MANIFEST_BYTES = 64L * 1024 * 1024;

    private static final int BACKUP_GENERATIONS = 3;

    private final VaultContext vault;
    private final AesGcmService aes = new AesGcmService();
    private final AtomicFileWriter writer = new AtomicFileWriter();
    private final BackupRotator rotator = new BackupRotator();
    private final Gson gson = new Gson();

    public ManifestRepository(VaultContext vault) {
        this.vault = vault;
    }

    /**
     * Decrypts the user's manifest. If it fails authentication, the newest
     * authentic backup is restored and returned instead.
     */
    public UserManifest load(UUID userId, UUID manifestId, byte[] userMasterKey)
            throws VaultStorageException {

        Path manifestFile = manifestFile(manifestId);

        try {
            return read(userId, manifestFile, userMasterKey);

        } catch (VaultStorageException damaged) {
            if (damaged.getReason() != VaultStorageException.Reason.CORRUPTED) {
                throw damaged;
            }

            return rotator.recover(
                    manifestFile,
                    manifestBackupsDir(),
                    BACKUP_GENERATIONS,
                    file -> read(userId, file, userMasterKey)
            ).orElseThrow(() -> damaged);
        }
    }

    private UserManifest read(UUID userId, Path file, byte[] userMasterKey)
            throws VaultStorageException {

        String json;

        try {
            json = BoundedFiles.readUtf8(file, MAX_MANIFEST_BYTES);
        } catch (NoSuchFileException e) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED, e);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }

        byte[] plaintext = null;

        try {
            plaintext = aes.decrypt(
                    MetadataJson.envelope(json),
                    userMasterKey,
                    Aad.manifest(vault.vaultId(), userId.toString())
            );
            UserManifest manifest = gson.fromJson(
                    new InputStreamReader(
                            new ByteArrayInputStream(plaintext),
                            StandardCharsets.UTF_8
                    ),
                    UserManifest.class
            );

            if (!isWellFormed(manifest, userId)) {
                throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);
            }

            return manifest;

        } catch (CryptoException | JsonParseException e) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED, e);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /**
     * Authentic content must still be a tree the way EncryptDrive writes it:
     * unique ids, one root, every other entry under an existing folder and
     * reachable from the root, and complete content fields on every file.
     */
    private static boolean isWellFormed(UserManifest manifest, UUID userId) {
        if (manifest == null
                || manifest.getFormatVersion() != FORMAT_VERSION
                || !userId.equals(manifest.getUserId())
                || manifest.getRootFolderId() == null
                || manifest.getEntries() == null) {
            return false;
        }

        for (PendingDeletion deletion : manifest.getPendingDeletions()) {
            if (deletion == null || deletion.blobId() == null) {
                return false;
            }
        }

        Map<UUID, ManifestEntry> byId = new HashMap<>();

        for (ManifestEntry entry : manifest.getEntries()) {
            if (entry == null
                    || entry.getEntryId() == null
                    || entry.getKind() == null
                    || entry.getName() == null
                    || entry.getCreatedAt() == null
                    || byId.put(entry.getEntryId(), entry) != null) {
                return false;
            }
        }

        ManifestEntry root = byId.get(manifest.getRootFolderId());

        if (root == null || root.getKind() != ManifestEntryKind.FOLDER || root.getParentId() != null) {
            return false;
        }

        Map<UUID, List<ManifestEntry>> children = new HashMap<>();

        for (ManifestEntry entry : manifest.getEntries()) {
            if (entry != root) {
                ManifestEntry parent = entry.getParentId() == null ? null : byId.get(entry.getParentId());

                if (parent == null || parent.getKind() != ManifestEntryKind.FOLDER) {
                    return false;
                }

                children.computeIfAbsent(parent.getEntryId(), id -> new ArrayList<>()).add(entry);
            }

            if (entry.getKind() == ManifestEntryKind.FILE && !hasContent(entry)) {
                return false;
            }
        }

        // Entries caught in a parent cycle are never reached from the root.
        int reached = 0;
        Deque<ManifestEntry> pending = new ArrayDeque<>(List.of(root));

        while (!pending.isEmpty()) {
            reached++;
            pending.addAll(children.getOrDefault(pending.pop().getEntryId(), List.of()));
        }

        return reached == manifest.getEntries().size();
    }

    private static boolean hasContent(ManifestEntry file) {
        return file.getPlainSize() != null
                && file.getPlainSize() >= 0
                && file.getBlobId() != null
                && file.getWrappedFileKey() != null
                && decodedLength(file.getContentNonce()) == CryptoConstants.GCM_NONCE_BYTES;
    }

    private static int decodedLength(String base64) {
        try {
            return base64 == null ? -1 : Base64.getDecoder().decode(base64).length;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    public void save(UserManifest manifest, UUID manifestId, byte[] userMasterKey)
            throws VaultStorageException {

        byte[] envelope = seal(manifest, userMasterKey);
        Path manifestFile = manifestFile(manifestId);

        try {
            rotator.rotate(manifestFile, manifestBackupsDir(), BACKUP_GENERATIONS);
            writer.write(manifestFile, envelope);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }

    /**
     * Saves without keeping older states: the manifest and every backup
     * generation become this manifest. Permanent deletion uses it so that no
     * backup can bring back entries whose blobs are about to be destroyed.
     */
    public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] userMasterKey)
            throws VaultStorageException {

        byte[] envelope = seal(manifest, userMasterKey);
        Path manifestFile = manifestFile(manifestId);

        try {
            // Spec 9.2 steps 4-5: the manifest first, then every backup.
            writer.write(manifestFile, envelope);
            rotator.reseed(manifestFile.getFileName().toString(), envelope,
                    manifestBackupsDir(), BACKUP_GENERATIONS);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }

    /** The manifest encrypted under the UMK, as the JSON envelope written to disk. */
    private byte[] seal(UserManifest manifest, byte[] userMasterKey) throws VaultStorageException {
        byte[] plaintext = gson.toJson(manifest).getBytes(StandardCharsets.UTF_8);

        try {
            byte[] envelope = gson.toJson(aes.encrypt(
                    plaintext,
                    userMasterKey,
                    Aad.manifest(vault.vaultId(), manifest.getUserId().toString())
            )).getBytes(StandardCharsets.UTF_8);
            BoundedFiles.requireWithin(envelope, MAX_MANIFEST_BYTES);
            return envelope;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Removes a manifest that nothing references, e.g. after a failed registration. */
    public void delete(UUID manifestId) throws VaultStorageException {
        try {
            Files.deleteIfExists(manifestFile(manifestId));
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }

    private Path manifestFile(UUID manifestId) {
        return VaultRepository.manifestsDir(vault.root()).resolve(manifestId + ".enc");
    }

    private Path manifestBackupsDir() {
        return VaultRepository.backupsDir(vault.root()).resolve(VaultRepository.MANIFESTS_DIR);
    }
}
