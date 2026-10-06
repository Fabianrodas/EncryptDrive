package com.fabianrodas.services;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.KdfConfig;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.UserRecord;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.UserRegistry;
import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.UserRegistryRepository;
import com.fabianrodas.repositories.VaultStorageException;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.Argon2KeyDeriver;
import com.fabianrodas.security.CryptoConstants;
import com.fabianrodas.security.CryptoException;
import com.fabianrodas.security.SensitiveBytes;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Local accounts inside an unlocked vault. Each user password derives a
 * key that wraps that user's random master key (UMK); a successful unwrap
 * is the password check, so no password hash is stored.
 */
public final class AuthService {

    public static final int MIN_USERNAME_LENGTH = 3;
    public static final int MIN_PASSWORD_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VaultContext vault;
    private final UserRegistryRepository registryRepository;
    private final ManifestRepository manifestRepository;
    private final Argon2KeyDeriver keyDeriver = new Argon2KeyDeriver();
    private final AesGcmService aes = new AesGcmService();

    public AuthService(VaultContext vault) {
        this(vault, new UserRegistryRepository());
    }

    /** For tests that make individual registry writes fail. */
    AuthService(VaultContext vault, UserRegistryRepository registryRepository) {
        this.vault = vault;
        this.registryRepository = registryRepository;
        this.manifestRepository = new ManifestRepository(vault);
    }

    public UserSessionIdentity register(String fullName, String username, char[] password)
            throws AuthException {

        if (fullName == null
                || fullName.isBlank()
                || username == null
                || username.trim().length() < MIN_USERNAME_LENGTH
                || password == null
                || password.length < MIN_PASSWORD_LENGTH) {
            throw new AuthException(AuthException.Reason.INVALID_INPUT);
        }

        String normalizedUsername = normalize(username);
        UserRegistry registry = loadRegistry();

        if (find(registry, normalizedUsername) != null) {
            throw new AuthException(AuthException.Reason.USERNAME_TAKEN);
        }

        UUID userId = UUID.randomUUID();
        UUID manifestId = UUID.randomUUID();
        byte[] userMasterKey = new byte[CryptoConstants.KEY_BYTES];
        RANDOM.nextBytes(userMasterKey);

        try {
            KdfConfig userKdf = keyDeriver.newConfig();
            UserRecord record = new UserRecord(
                    userId.toString(),
                    fullName.trim(),
                    username.trim(),
                    normalizedUsername,
                    Instant.now().toString(),
                    manifestId.toString(),
                    userKdf,
                    wrap(userMasterKey, password, userKdf, userId.toString())
            );

            // The manifest is written first so the registry never references
            // a missing one; it is removed again if the registry write fails.
            saveManifest(ManifestService.newManifest(userId), manifestId, userMasterKey);
            registry.getUsers().add(record);

            try {
                saveRegistry(registry);
            } catch (AuthException e) {
                deleteManifest(manifestId);
                throw e;
            }

            return identity(record);

        } finally {
            Arrays.fill(userMasterKey, (byte) 0);
        }
    }

    public UserLoginResult login(String username, char[] password) throws AuthException {
        if (username == null || password == null) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS);
        }

        UserRecord record = find(loadRegistry(), normalize(username));

        if (record == null) {
            // Same Argon2 cost as a real attempt, so timing does not reveal
            // which usernames exist.
            Arrays.fill(keyDeriver.derive(password, keyDeriver.newConfig()), (byte) 0);
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS);
        }

        return new UserLoginResult(
                identity(record),
                SensitiveBytes.wrap(unwrap(record, password))
        );
    }

    /**
     * Rewraps the same UMK under a key derived from the new password, so
     * existing manifests and file blobs stay readable without re-encryption,
     * and replaces every registry backup, so the old password opens no
     * retained generation.
     */
    public void changePassword(UUID userId, char[] currentPassword, char[] newPassword)
            throws AuthException {

        if (newPassword == null || newPassword.length < MIN_PASSWORD_LENGTH) {
            throw new AuthException(AuthException.Reason.INVALID_INPUT);
        }

        UserRegistry registry = loadRegistry();
        UserRecord record = registry.getUsers().stream()
                .filter(user -> user.getUserId().equals(userId.toString()))
                .findFirst()
                .orElseThrow(() -> new AuthException(AuthException.Reason.USER_NOT_FOUND));

        byte[] userMasterKey = unwrap(record, currentPassword);

        try {
            KdfConfig userKdf = keyDeriver.newConfig();
            record.setCredentials(
                    userKdf,
                    wrap(userMasterKey, newPassword, userKdf, record.getUserId())
            );
            saveRegistryCheckpoint(registry);

        } finally {
            Arrays.fill(userMasterKey, (byte) 0);
        }
    }

    private EncryptedPayload wrap(
            byte[] userMasterKey,
            char[] password,
            KdfConfig userKdf,
            String userId
    ) {
        byte[] keyEncryptionKey = keyDeriver.derive(password, userKdf);

        try {
            return aes.wrapKey(
                    userMasterKey,
                    keyEncryptionKey,
                    Aad.userKey(vault.vaultId(), userId)
            );
        } finally {
            Arrays.fill(keyEncryptionKey, (byte) 0);
        }
    }

    private byte[] unwrap(UserRecord record, char[] password) throws AuthException {
        if (password == null) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS);
        }

        byte[] keyEncryptionKey;

        try {
            keyEncryptionKey = keyDeriver.derive(password, record.getUserKdf());
        } catch (IllegalArgumentException e) {
            throw new AuthException(AuthException.Reason.CORRUPTED, e);
        }

        try {
            return aes.unwrapKey(
                    record.getWrappedUserMasterKey(),
                    keyEncryptionKey,
                    Aad.userKey(vault.vaultId(), record.getUserId())
            );
        } catch (CryptoException e) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS);
        } finally {
            Arrays.fill(keyEncryptionKey, (byte) 0);
        }
    }

    private UserRegistry loadRegistry() throws AuthException {
        try {
            return registryRepository.load(vault);
        } catch (VaultStorageException e) {
            throw storageFailure(e);
        }
    }

    private void saveRegistry(UserRegistry registry) throws AuthException {
        try {
            registryRepository.save(vault, registry);
        } catch (VaultStorageException e) {
            throw storageFailure(e);
        }
    }

    private void saveRegistryCheckpoint(UserRegistry registry) throws AuthException {
        try {
            registryRepository.saveCheckpoint(vault, registry);
        } catch (VaultStorageException e) {
            throw storageFailure(e);
        }
    }

    private void saveManifest(UserManifest manifest, UUID manifestId, byte[] userMasterKey)
            throws AuthException {
        try {
            manifestRepository.save(manifest, manifestId, userMasterKey);
        } catch (VaultStorageException e) {
            throw storageFailure(e);
        }
    }

    private void deleteManifest(UUID manifestId) {
        try {
            manifestRepository.delete(manifestId);
        } catch (VaultStorageException ignored) {
            // An unreferenced manifest is unreadable ciphertext; leaving it is harmless.
        }
    }

    private static AuthException storageFailure(VaultStorageException e) {
        return new AuthException(
                e.getReason() == VaultStorageException.Reason.IO
                        || e.getReason() == VaultStorageException.Reason.TOO_LARGE
                        || e.getReason() == VaultStorageException.Reason.INVALID
                        ? AuthException.Reason.STORAGE
                        : AuthException.Reason.CORRUPTED,
                e
        );
    }

    private static UserRecord find(UserRegistry registry, String normalizedUsername) {
        for (UserRecord user : registry.getUsers()) {
            if (user.getNormalizedUsername().equals(normalizedUsername)) {
                return user;
            }
        }

        return null;
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static UserSessionIdentity identity(UserRecord record) {
        return new UserSessionIdentity(
                UUID.fromString(record.getUserId()),
                record.getFullName(),
                record.getUsername(),
                UUID.fromString(record.getManifestId())
        );
    }
}
