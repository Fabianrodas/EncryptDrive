package com.fabianrodas.repositories;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
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
import java.util.Arrays;
import java.util.UUID;

/**
 * {@code manifests/<manifestId>.enc}: one user's logical filesystem,
 * encrypted under that user's master key and bound to the user by AAD.
 */
public class ManifestRepository {

    public static final int FORMAT_VERSION = 1;

    private static final int BACKUP_GENERATIONS = 3;

    private final VaultContext vault;
    private final AesGcmService aes = new AesGcmService();
    private final AtomicFileWriter writer = new AtomicFileWriter();
    private final BackupRotator rotator = new BackupRotator();
    private final Gson gson = new Gson();

    public ManifestRepository(VaultContext vault) {
        this.vault = vault;
    }

    public UserManifest load(UUID userId, UUID manifestId, byte[] userMasterKey)
            throws VaultStorageException {

        String json;

        try {
            json = Files.readString(manifestFile(manifestId), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED, e);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }

        byte[] plaintext = null;

        try {
            plaintext = aes.decrypt(
                    gson.fromJson(json, EncryptedPayload.class),
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

            if (manifest == null
                    || manifest.getFormatVersion() != FORMAT_VERSION
                    || !userId.equals(manifest.getUserId())
                    || manifest.getRootFolderId() == null
                    || manifest.getEntries() == null) {
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

    public void save(UserManifest manifest, UUID manifestId, byte[] userMasterKey)
            throws VaultStorageException {

        byte[] plaintext = gson.toJson(manifest).getBytes(StandardCharsets.UTF_8);

        try {
            EncryptedPayload payload = aes.encrypt(
                    plaintext,
                    userMasterKey,
                    Aad.manifest(vault.vaultId(), manifest.getUserId().toString())
            );
            Path manifestFile = manifestFile(manifestId);

            rotator.rotate(
                    manifestFile,
                    VaultRepository.backupsDir(vault.root()).resolve(VaultRepository.MANIFESTS_DIR),
                    BACKUP_GENERATIONS
            );
            writer.write(manifestFile, gson.toJson(payload).getBytes(StandardCharsets.UTF_8));

        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
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
}
