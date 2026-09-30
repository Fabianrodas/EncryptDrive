package com.fabianrodas.repositories;

import com.fabianrodas.models.VaultHeader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;

public final class VaultRepository {

    public static final String META_DIR = ".encryptdrive";
    public static final String VAULT_HEADER = "vault.json";
    public static final String USERS_FILE = "users.enc";
    public static final String MANIFESTS_DIR = "manifests";
    public static final String BACKUPS_DIR = "backups";
    public static final String BLOBS_DIR = "storage/blobs";
    public static final String LOCK_FILE = "lock";

    public static final int FORMAT_VERSION = 1;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final AtomicFileWriter writer = new AtomicFileWriter();

    public static Path metaDir(Path vaultRoot) {
        return vaultRoot.resolve(META_DIR);
    }

    public void writeHeader(Path vaultRoot, VaultHeader header)
            throws VaultStorageException {

        try {
            Files.createDirectories(metaDir(vaultRoot));
            writer.write(
                    metaDir(vaultRoot).resolve(VAULT_HEADER),
                    gson.toJson(header).getBytes(StandardCharsets.UTF_8)
            );

        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }

    public VaultHeader readHeader(Path vaultRoot) throws VaultStorageException {
        String json;

        try {
            json = Files.readString(
                    metaDir(vaultRoot).resolve(VAULT_HEADER),
                    StandardCharsets.UTF_8
            );

        } catch (NoSuchFileException e) {
            throw new VaultStorageException(VaultStorageException.Reason.NOT_FOUND, e);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }

        VaultHeader header;

        try {
            header = gson.fromJson(json, VaultHeader.class);
        } catch (JsonParseException e) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED, e);
        }

        if (header != null && header.getFormatVersion() > FORMAT_VERSION) {
            throw new VaultStorageException(
                    VaultStorageException.Reason.UNSUPPORTED_VERSION
            );
        }

        if (header == null
                || header.getFormatVersion() != FORMAT_VERSION
                || !isCanonicalUuid(header.getVaultId())
                || header.getCreatedAt() == null
                || header.getKdf() == null
                || header.getWrappedRegistryKey() == null) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);
        }

        return header;
    }

    static boolean isCanonicalUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
