package com.fabianrodas.services;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.KdfConfig;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.models.VaultHeader;
import com.fabianrodas.repositories.AtomicFileWriter;
import com.fabianrodas.repositories.VaultRepository;
import com.fabianrodas.repositories.VaultStorageException;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.Argon2KeyDeriver;
import com.fabianrodas.security.CryptoConstants;
import com.fabianrodas.security.CryptoException;
import com.fabianrodas.security.SensitiveBytes;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Vault lifecycle around the key hierarchy: the vault password derives a
 * key that wraps a random registry master key (RMK).
 */
public final class VaultService {

    public static final int MIN_PASSWORD_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VaultRepository vaultRepository = new VaultRepository();
    private final VaultLockService lockService = new VaultLockService();
    private final Argon2KeyDeriver keyDeriver = new Argon2KeyDeriver();
    private final AesGcmService aes = new AesGcmService();
    private final AtomicFileWriter writer = new AtomicFileWriter();
    private final Gson gson = new Gson();

    public VaultContext createVault(Path root, char[] vaultPassword)
            throws VaultException {

        requireValidPassword(vaultPassword);
        Path vaultRoot = root.toAbsolutePath().normalize();
        requireEmptyOrMissing(vaultRoot);
        createStructure(vaultRoot);

        VaultLockService.VaultLock lock = lockService.acquire(vaultRoot);
        byte[] registryKey = new byte[CryptoConstants.KEY_BYTES];
        RANDOM.nextBytes(registryKey);
        boolean opened = false;

        try {
            String vaultId = UUID.randomUUID().toString();
            String createdAt = Instant.now().toString();

            writeEmptyRegistry(vaultRoot, vaultId, registryKey);
            vaultRepository.writeHeader(
                    vaultRoot,
                    header(vaultId, createdAt, registryKey, vaultPassword)
            );

            VaultContext context = new VaultContext(
                    vaultRoot,
                    vaultId,
                    VaultRepository.FORMAT_VERSION,
                    createdAt,
                    SensitiveBytes.wrap(registryKey),
                    lock
            );
            VaultSessionService.open(context);
            opened = true;
            return context;

        } catch (IOException | VaultStorageException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        } finally {
            if (!opened) {
                Arrays.fill(registryKey, (byte) 0);
                lock.close();
            }
        }
    }

    public VaultContext unlockVault(Path root, char[] vaultPassword)
            throws VaultException {

        Path vaultRoot = root.toAbsolutePath().normalize();
        Path headerFile = VaultRepository.metaDir(vaultRoot)
                .resolve(VaultRepository.VAULT_HEADER);

        if (!Files.isRegularFile(headerFile)) {
            throw new VaultException(VaultException.Reason.NOT_A_VAULT);
        }

        VaultLockService.VaultLock lock = lockService.acquire(vaultRoot);
        byte[] registryKey = null;
        boolean opened = false;

        try {
            VaultHeader header = readHeader(vaultRoot);
            registryKey = unwrapRegistryKey(header, vaultPassword);
            requireReadableRegistry(vaultRoot, header.getVaultId(), registryKey);

            VaultContext context = new VaultContext(
                    vaultRoot,
                    header.getVaultId(),
                    header.getFormatVersion(),
                    header.getCreatedAt(),
                    SensitiveBytes.wrap(registryKey),
                    lock
            );
            VaultSessionService.open(context);
            opened = true;
            return context;

        } finally {
            if (!opened) {
                if (registryKey != null) {
                    Arrays.fill(registryKey, (byte) 0);
                }

                lock.close();
            }
        }
    }

    /**
     * Rewraps the same RMK under a key derived from the new password. Only
     * {@code vault.json} changes; the registry, manifests, and blobs do not.
     */
    public void changeVaultPassword(char[] currentPassword, char[] newPassword)
            throws VaultException {

        requireValidPassword(newPassword);

        if (!VaultSessionService.isOpen()) {
            throw new VaultException(VaultException.Reason.NOT_OPEN);
        }

        VaultContext context = VaultSessionService.current();
        VaultHeader header = readHeader(context.root());
        byte[] unwrapped = unwrapRegistryKey(header, currentPassword);
        byte[] registryKey = context.copyRegistryKey();

        try {
            if (!MessageDigest.isEqual(unwrapped, registryKey)) {
                throw new VaultException(VaultException.Reason.UNLOCK_FAILED);
            }

            vaultRepository.writeHeader(
                    context.root(),
                    header(header.getVaultId(), header.getCreatedAt(), registryKey, newPassword)
            );

        } catch (VaultStorageException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        } finally {
            Arrays.fill(unwrapped, (byte) 0);
            Arrays.fill(registryKey, (byte) 0);
        }
    }

    public void closeVault() {
        VaultSessionService.closeVault();
    }

    private VaultHeader header(
            String vaultId,
            String createdAt,
            byte[] registryKey,
            char[] vaultPassword
    ) {
        KdfConfig kdf = keyDeriver.newConfig();
        byte[] vaultKeyEncryptionKey = keyDeriver.derive(vaultPassword, kdf);

        try {
            EncryptedPayload wrapped = aes.wrapKey(
                    registryKey,
                    vaultKeyEncryptionKey,
                    Aad.vaultKey(vaultId)
            );

            return new VaultHeader(
                    VaultRepository.FORMAT_VERSION,
                    vaultId,
                    createdAt,
                    kdf,
                    wrapped
            );

        } finally {
            Arrays.fill(vaultKeyEncryptionKey, (byte) 0);
        }
    }

    private byte[] unwrapRegistryKey(VaultHeader header, char[] vaultPassword)
            throws VaultException {

        byte[] vaultKeyEncryptionKey;

        try {
            vaultKeyEncryptionKey = keyDeriver.derive(vaultPassword, header.getKdf());
        } catch (IllegalArgumentException e) {
            throw new VaultException(VaultException.Reason.CORRUPTED, e);
        }

        try {
            return aes.unwrapKey(
                    header.getWrappedRegistryKey(),
                    vaultKeyEncryptionKey,
                    Aad.vaultKey(header.getVaultId())
            );

        } catch (CryptoException e) {
            throw new VaultException(VaultException.Reason.UNLOCK_FAILED);
        } finally {
            Arrays.fill(vaultKeyEncryptionKey, (byte) 0);
        }
    }

    private VaultHeader readHeader(Path vaultRoot) throws VaultException {
        try {
            return vaultRepository.readHeader(vaultRoot);

        } catch (VaultStorageException e) {
            throw new VaultException(switch (e.getReason()) {
                case NOT_FOUND -> VaultException.Reason.NOT_A_VAULT;
                case CORRUPTED -> VaultException.Reason.CORRUPTED;
                case UNSUPPORTED_VERSION -> VaultException.Reason.UNSUPPORTED_VERSION;
                case IO -> VaultException.Reason.STORAGE;
            }, e);
        }
    }

    private void writeEmptyRegistry(Path vaultRoot, String vaultId, byte[] registryKey)
            throws IOException {

        byte[] json = "{\"formatVersion\":1,\"users\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        EncryptedPayload payload = aes.encrypt(json, registryKey, Aad.users(vaultId));

        writer.write(
                usersFile(vaultRoot),
                gson.toJson(payload).getBytes(StandardCharsets.UTF_8)
        );
    }

    private void requireReadableRegistry(
            Path vaultRoot,
            String vaultId,
            byte[] registryKey
    ) throws VaultException {

        try {
            EncryptedPayload payload = gson.fromJson(
                    Files.readString(usersFile(vaultRoot), StandardCharsets.UTF_8),
                    EncryptedPayload.class
            );
            byte[] json = aes.decrypt(payload, registryKey, Aad.users(vaultId));

            try {
                if (gson.fromJson(new String(json, StandardCharsets.UTF_8), JsonObject.class) == null) {
                    throw new VaultException(VaultException.Reason.CORRUPTED);
                }
            } finally {
                Arrays.fill(json, (byte) 0);
            }

        } catch (IOException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        } catch (CryptoException | JsonParseException e) {
            throw new VaultException(VaultException.Reason.CORRUPTED, e);
        }
    }

    private static Path usersFile(Path vaultRoot) {
        return VaultRepository.metaDir(vaultRoot).resolve(VaultRepository.USERS_FILE);
    }

    private static void requireValidPassword(char[] password) throws VaultException {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new VaultException(VaultException.Reason.INVALID_PASSWORD);
        }
    }

    private static void requireEmptyOrMissing(Path vaultRoot) throws VaultException {
        if (!Files.exists(vaultRoot)) {
            return;
        }

        if (!Files.isDirectory(vaultRoot)) {
            throw new VaultException(VaultException.Reason.ALREADY_EXISTS);
        }

        try (Stream<Path> entries = Files.list(vaultRoot)) {
            if (entries.findAny().isPresent()) {
                throw new VaultException(VaultException.Reason.ALREADY_EXISTS);
            }
        } catch (IOException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        }
    }

    private static void createStructure(Path vaultRoot) throws VaultException {
        Path metaDir = VaultRepository.metaDir(vaultRoot);

        try {
            Files.createDirectories(metaDir.resolve(VaultRepository.MANIFESTS_DIR));
            Files.createDirectories(metaDir
                    .resolve(VaultRepository.BACKUPS_DIR)
                    .resolve(VaultRepository.MANIFESTS_DIR));
            Files.createDirectories(vaultRoot.resolve(VaultRepository.BLOBS_DIR));

        } catch (IOException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        }
    }
}
