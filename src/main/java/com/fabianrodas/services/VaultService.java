package com.fabianrodas.services;

import com.fabianrodas.models.Vault;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

public class VaultService {

    public static final int FORMAT_VERSION = 1;

    private static final String METADATA_DIRECTORY = ".encryptdrive";
    private static final String VAULT_FILE = "vault.json";
    private static final String USERS_FILE = "users.json";
    private static final String STORAGE_DIRECTORY = "storage";

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public Vault createVault(Path vaultPath, String vaultName)
            throws IOException {

        if (vaultPath == null) {
            throw new IllegalArgumentException("A vault location is required.");
        }

        if (vaultName == null || vaultName.isBlank()) {
            throw new IllegalArgumentException("A vault name is required.");
        }

        Path normalizedVaultPath = vaultPath.toAbsolutePath().normalize();

        if (Files.exists(normalizedVaultPath)) {
            throw new IOException(
                    "The selected vault folder already exists."
            );
        }

        Files.createDirectories(normalizedVaultPath);

        Path metadataPath = normalizedVaultPath.resolve(METADATA_DIRECTORY);
        Path usersPath = metadataPath.resolve(USERS_FILE);
        Path storagePath = normalizedVaultPath.resolve(STORAGE_DIRECTORY);

        Files.createDirectories(metadataPath);
        Files.createDirectories(storagePath);

        Files.writeString(
                usersPath,
                "[]",
                StandardCharsets.UTF_8
        );

        Vault vault = new Vault(
                FORMAT_VERSION,
                UUID.randomUUID().toString(),
                vaultName.trim(),
                Instant.now().toString()
        );

        writeVaultConfiguration(normalizedVaultPath, vault);

        return vault;
    }

    public Vault openExistingVault(Path vaultPath) throws IOException {
        if (vaultPath == null) {
            throw new IllegalArgumentException("A vault location is required.");
        }

        Path normalizedVaultPath = vaultPath.toAbsolutePath().normalize();

        Path metadataPath = normalizedVaultPath.resolve(METADATA_DIRECTORY);
        Path vaultFilePath = metadataPath.resolve(VAULT_FILE);
        Path usersFilePath = metadataPath.resolve(USERS_FILE);
        Path storagePath = normalizedVaultPath.resolve(STORAGE_DIRECTORY);

        if (!Files.isDirectory(normalizedVaultPath)
                || !Files.isRegularFile(vaultFilePath)
                || !Files.isRegularFile(usersFilePath)
                || !Files.isDirectory(storagePath)) {

            throw new IOException(
                    "The selected folder is not a valid EncryptDrive vault."
            );
        }

        try (Reader reader = Files.newBufferedReader(
                vaultFilePath,
                StandardCharsets.UTF_8
        )) {
            Vault vault = gson.fromJson(reader, Vault.class);

            if (vault == null || vault.getFormatVersion() != FORMAT_VERSION) {
                throw new IOException(
                        "This vault uses an unsupported format."
                );
            }

            return vault;
        }
    }

    public boolean isValidVault(Path vaultPath) {
        try {
            openExistingVault(vaultPath);
            return true;

        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private void writeVaultConfiguration(
            Path vaultPath,
            Vault vault
    ) throws IOException {

        Path vaultFilePath = vaultPath
                .resolve(METADATA_DIRECTORY)
                .resolve(VAULT_FILE);

        Path temporaryFile = Files.createTempFile(
                vaultFilePath.getParent(),
                "vault-",
                ".tmp"
        );

        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporaryFile,
                    StandardCharsets.UTF_8
            )) {
                gson.toJson(vault, writer);
            }

            try {
                Files.move(
                        temporaryFile,
                        vaultFilePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );

            } catch (AtomicMoveNotSupportedException e) {
                Files.move(
                        temporaryFile,
                        vaultFilePath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }
}