package com.fabianrodas.repositories;

import com.fabianrodas.models.EncryptedPayload;
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
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * {@code users.enc}: the whole registry encrypted under the registry
 * master key, with three backup generations kept before each replacement.
 */
public final class UserRegistryRepository {

    public static final int FORMAT_VERSION = 1;

    private static final int BACKUP_GENERATIONS = 3;

    private final AesGcmService aes = new AesGcmService();
    private final AtomicFileWriter writer = new AtomicFileWriter();
    private final BackupRotator rotator = new BackupRotator();
    private final Gson gson = new Gson();

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

            return rotator.recover(
                    usersFile,
                    VaultRepository.backupsDir(vault.root()),
                    BACKUP_GENERATIONS,
                    file -> read(vault, file)
            ).orElseThrow(() -> damaged);
        }
    }

    private UserRegistry read(VaultContext vault, Path file) throws VaultStorageException {
        String json;

        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED, e);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }

        byte[] registryKey = vault.copyRegistryKey();
        byte[] plaintext = null;

        try {
            plaintext = aes.decrypt(
                    gson.fromJson(json, EncryptedPayload.class),
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

            if (registry == null
                    || registry.getFormatVersion() != FORMAT_VERSION
                    || registry.getUsers() == null) {
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

    public void save(VaultContext vault, UserRegistry registry)
            throws VaultStorageException {

        byte[] registryKey = vault.copyRegistryKey();
        byte[] plaintext = gson.toJson(registry).getBytes(StandardCharsets.UTF_8);

        try {
            EncryptedPayload payload = aes.encrypt(
                    plaintext,
                    registryKey,
                    Aad.users(vault.vaultId())
            );
            Path usersFile = VaultRepository.usersFile(vault.root());

            rotator.rotate(
                    usersFile,
                    VaultRepository.backupsDir(vault.root()),
                    BACKUP_GENERATIONS
            );
            writer.write(usersFile, gson.toJson(payload).getBytes(StandardCharsets.UTF_8));

        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        } finally {
            Arrays.fill(registryKey, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
