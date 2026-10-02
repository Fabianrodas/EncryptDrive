package com.fabianrodas.services;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.KdfConfig;
import com.fabianrodas.models.UserRegistry;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.models.VaultHeader;
import com.fabianrodas.repositories.UserRegistryRepository;
import com.fabianrodas.repositories.VaultRepository;
import com.fabianrodas.repositories.VaultStorageException;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.Argon2KeyDeriver;
import com.fabianrodas.security.CryptoConstants;
import com.fabianrodas.security.CryptoException;
import com.fabianrodas.security.SensitiveBytes;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Vault lifecycle around the key hierarchy: the vault password derives a
 * key that wraps a random registry master key (RMK).
 */
public class VaultService {

    public static final int MIN_PASSWORD_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VaultRepository vaultRepository = new VaultRepository();
    private final UserRegistryRepository registryRepository = new UserRegistryRepository();
    private final VaultLockService lockService = new VaultLockService();
    private final Argon2KeyDeriver keyDeriver = new Argon2KeyDeriver();
    private final AesGcmService aes = new AesGcmService();

    /**
     * Builds the whole vault in a staging folder next to the chosen one and
     * renames it into place, so a failure never leaves a half-made vault and
     * never touches anything the user already has.
     */
    public VaultContext createVault(Path root, char[] vaultPassword)
            throws VaultException {

        requireValidPassword(vaultPassword);
        Path vaultRoot = root.toAbsolutePath().normalize();
        Path parent = vaultRoot.getParent();

        if (parent == null) {
            // A drive root cannot be renamed into; vaults live in a folder.
            throw new VaultException(VaultException.Reason.STORAGE);
        }

        requireEmptyOrMissing(vaultRoot);

        byte[] registryKey = new byte[CryptoConstants.KEY_BYTES];
        RANDOM.nextBytes(registryKey);
        String vaultId = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        Path staging = parent.resolve(
                "." + vaultRoot.getFileName() + ".creating-" + UUID.randomUUID()
        );
        boolean staged = false;
        boolean published = false;
        VaultContext context = null;
        boolean opened = false;

        try {
            Files.createDirectories(parent);
            // Fails if the name exists, so everything under it was made by this call.
            Files.createDirectory(staging);
            staged = true;
            createStructure(staging);

            // Closed before the rename: Windows cannot move a folder that has open handles inside.
            try (VaultContext building = new VaultContext(
                    staging, vaultId, VaultRepository.FORMAT_VERSION, createdAt,
                    SensitiveBytes.copyOf(registryKey), () -> { })) {
                registryRepository.save(
                        building,
                        new UserRegistry(UserRegistryRepository.FORMAT_VERSION, new ArrayList<>())
                );
            }

            // The header is written last: without it the folder is not a vault.
            vaultRepository.writeHeader(staging, header(vaultId, createdAt, registryKey, vaultPassword));
            moveIntoPlace(staging, vaultRoot);
            published = true;

            // The vault is complete on disk from here on; a lock failure leaves a vault that opens normally.
            VaultLockService.VaultLock lock = lockService.acquire(vaultRoot);
            context = new VaultContext(
                    vaultRoot, vaultId, VaultRepository.FORMAT_VERSION, createdAt,
                    SensitiveBytes.copyOf(registryKey), lock
            );
            VaultSessionService.open(context);
            opened = true;
            return context;

        } catch (IOException | VaultStorageException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        } finally {
            Arrays.fill(registryKey, (byte) 0);

            if (context != null && !opened) {
                context.close();
            }

            if (staged && !published) {
                deleteStaging(staging);
            }
        }
    }

    /**
     * Publishes the finished vault with one rename onto a missing or empty
     * folder. Only an empty folder is ever replaced; it is given back if the
     * rename fails.
     */
    void moveIntoPlace(Path staging, Path target) throws IOException {
        boolean replacedEmptyFolder = false;

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // On Windows the rename below would silently replace a file.
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }

            // Files.delete refuses a folder that is not empty.
            Files.delete(target);
            replacedEmptyFolder = true;
        }

        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (replacedEmptyFolder) {
                try {
                    Files.createDirectory(target);
                } catch (IOException ignored) {
                    // Best effort: the folder the user chose was empty.
                }
            }

            throw e;
        }
    }

    /** Removes a staging folder this call created; best effort. */
    private static void deleteStaging(Path staging) {
        try (Stream<Path> paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A leftover ".<name>.creating-<id>" folder holds only unreadable ciphertext.
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
        VaultContext context = null;
        boolean opened = false;

        try {
            VaultHeader header = readHeader(vaultRoot);
            context = new VaultContext(
                    vaultRoot,
                    header.getVaultId(),
                    header.getFormatVersion(),
                    header.getCreatedAt(),
                    SensitiveBytes.wrap(unwrapRegistryKey(header, vaultPassword)),
                    lock
            );
            // Decrypting and parsing the registry proves the vault is intact;
            // a damaged registry is restored from an authentic backup here.
            registryRepository.load(context);

            VaultSessionService.open(context);
            opened = true;
            removeStalePartials(vaultRoot);
            return context;

        } catch (VaultStorageException e) {
            throw new VaultException(
                    e.getReason() == VaultStorageException.Reason.IO
                            ? VaultException.Reason.STORAGE
                            : VaultException.Reason.CORRUPTED,
                    e
            );
        } finally {
            if (!opened) {
                if (context != null) {
                    context.close();
                } else {
                    lock.close();
                }
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
                case IO, TOO_LARGE -> VaultException.Reason.STORAGE;
            }, e);
        }
    }

    private static void removeStalePartials(Path vaultRoot) {
        try {
            new RecoveryService().cleanStalePartials(vaultRoot, Instant.now());
        } catch (IOException ignored) {
            // Housekeeping only; stale partial files are retried at the next open.
        }
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

    private static void createStructure(Path folder) throws VaultException {
        Path metaDir = VaultRepository.metaDir(folder);

        try {
            Files.createDirectories(metaDir.resolve(VaultRepository.MANIFESTS_DIR));
            Files.createDirectories(metaDir
                    .resolve(VaultRepository.BACKUPS_DIR)
                    .resolve(VaultRepository.MANIFESTS_DIR));
            Files.createDirectories(folder.resolve(VaultRepository.BLOBS_DIR));

        } catch (IOException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        }
    }
}
