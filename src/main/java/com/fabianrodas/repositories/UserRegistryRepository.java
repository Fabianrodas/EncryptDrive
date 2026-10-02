package com.fabianrodas.repositories;

import com.fabianrodas.models.UserRecord;
import com.fabianrodas.models.UserRegistry;
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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * {@code users.enc}: the whole registry encrypted under the registry
 * master key, with three backup generations kept before each replacement.
 */
public final class UserRegistryRepository {

    public static final int FORMAT_VERSION = 1;

    /** Spec limit for users.enc and each backup; checked before reading and before writing. */
    public static final long MAX_REGISTRY_BYTES = 16L * 1024 * 1024;

    private static final int BACKUP_GENERATIONS = 3;

    private final AesGcmService aes = new AesGcmService();
    private final AtomicFileWriter writer;
    private final BackupRotator rotator;
    private final Gson gson = new Gson();

    public UserRegistryRepository() {
        this(new AtomicFileWriter());
    }

    /** For tests that make individual writes fail. */
    public UserRegistryRepository(AtomicFileWriter writer) {
        this.writer = writer;
        this.rotator = new BackupRotator(writer);
    }

    /**
     * Decrypts {@code users.enc}. If it fails authentication, the newest
     * authentic backup is restored and returned instead.
     */
    public UserRegistry load(VaultContext vault) throws VaultStorageException {
        Path usersFile = VaultRepository.usersFile(vault.root());

        try {
            return read(vault, usersFile);

        } catch (VaultStorageException damaged) {
            if (damaged.getReason() != VaultStorageException.Reason.CORRUPTED) {
                throw damaged;
            }

            UserRegistry recovered = rotator.recover(
                    usersFile,
                    VaultRepository.backupsDir(vault.root()),
                    BACKUP_GENERATIONS,
                    file -> read(vault, file)
            ).orElseThrow(() -> damaged);
            reseedBackupsFromRestored(vault);
            return recovered;
        }
    }

    /**
     * After a recovery, makes every backup the restored registry, so a
     * generation the recovery skipped cannot still hold an older credential
     * envelope (e.g. after an interrupted password change).
     */
    private void reseedBackupsFromRestored(VaultContext vault) {
        try {
            byte[] restored = BoundedFiles.readBytes(
                    VaultRepository.usersFile(vault.root()), MAX_REGISTRY_BYTES
            );
            rotator.reseed(VaultRepository.USERS_FILE, restored,
                    VaultRepository.backupsDir(vault.root()), BACKUP_GENERATIONS);
        } catch (IOException | VaultStorageException e) {
            // Best effort: the registry is already recovered, and a failure here must not stop the vault opening.
        }
    }

    private UserRegistry read(VaultContext vault, Path file) throws VaultStorageException {
        String json;

        try {
            json = BoundedFiles.readUtf8(file, MAX_REGISTRY_BYTES);
        } catch (NoSuchFileException e) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED, e);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }

        byte[] registryKey = vault.copyRegistryKey();
        byte[] plaintext = null;

        try {
            plaintext = aes.decrypt(
                    MetadataJson.envelope(json),
                    registryKey,
                    Aad.users(vault.vaultId())
            );
            UserRegistry registry = gson.fromJson(
                    new InputStreamReader(
                            new ByteArrayInputStream(plaintext),
                            StandardCharsets.UTF_8
                    ),
                    UserRegistry.class
            );

            if (!isWellFormed(registry)) {
                throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);
            }

            return registry;

        } catch (CryptoException | JsonParseException e) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED, e);
        } finally {
            Arrays.fill(registryKey, (byte) 0);

            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /** Authentic content must still be shaped the way EncryptDrive writes it. */
    private static boolean isWellFormed(UserRegistry registry) {
        if (registry == null
                || registry.getFormatVersion() != FORMAT_VERSION
                || registry.getUsers() == null) {
            return false;
        }

        Set<String> userIds = new HashSet<>();
        Set<String> usernames = new HashSet<>();
        Set<String> manifestIds = new HashSet<>();

        for (UserRecord user : registry.getUsers()) {
            if (user == null
                    || !VaultRepository.isCanonicalUuid(user.getUserId())
                    || !VaultRepository.isCanonicalUuid(user.getManifestId())
                    || user.getFullName() == null
                    || user.getUsername() == null
                    || user.getNormalizedUsername() == null
                    || user.getCreatedAt() == null
                    || user.getUserKdf() == null
                    || user.getWrappedUserMasterKey() == null
                    || !userIds.add(user.getUserId())
                    || !usernames.add(user.getNormalizedUsername())
                    || !manifestIds.add(user.getManifestId())) {
                return false;
            }
        }

        return true;
    }

    public void save(VaultContext vault, UserRegistry registry)
            throws VaultStorageException {

        byte[] envelope = seal(vault, registry);
        Path usersFile = VaultRepository.usersFile(vault.root());

        try {
            rotator.rotate(usersFile, VaultRepository.backupsDir(vault.root()), BACKUP_GENERATIONS);
            writer.write(usersFile, envelope);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }

    /**
     * Saves without keeping older states: users.enc and every backup become
     * this registry. Used after a password change so no backup still holds a
     * key envelope wrapped under the old password. The backups are replaced
     * first and users.enc last: until that last write the change has not
     * happened, and afterwards no older generation is left.
     */
    public void saveCheckpoint(VaultContext vault, UserRegistry registry) throws VaultStorageException {
        byte[] envelope = seal(vault, registry);
        Path usersFile = VaultRepository.usersFile(vault.root());

        try {
            rotator.reseed(VaultRepository.USERS_FILE, envelope,
                    VaultRepository.backupsDir(vault.root()), BACKUP_GENERATIONS);
            writer.write(usersFile, envelope);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }

    /** The registry encrypted under the RMK, as the JSON envelope written to disk. */
    private byte[] seal(VaultContext vault, UserRegistry registry) throws VaultStorageException {
        byte[] registryKey = vault.copyRegistryKey();
        byte[] plaintext = gson.toJson(registry).getBytes(StandardCharsets.UTF_8);

        try {
            byte[] envelope = gson.toJson(aes.encrypt(plaintext, registryKey, Aad.users(vault.vaultId())))
                    .getBytes(StandardCharsets.UTF_8);
            BoundedFiles.requireWithin(envelope, MAX_REGISTRY_BYTES);
            return envelope;
        } finally {
            Arrays.fill(registryKey, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
