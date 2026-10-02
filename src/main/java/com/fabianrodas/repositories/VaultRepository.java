package com.fabianrodas.repositories;

import com.fabianrodas.models.VaultHeader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    /** Spec limit for vault.json; checked before reading and before writing. */
    public static final long MAX_HEADER_BYTES = 256L * 1024;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final AtomicFileWriter writer = new AtomicFileWriter();

    public static Path metaDir(Path vaultRoot) {
        return vaultRoot.resolve(META_DIR);
    }

    public static Path usersFile(Path vaultRoot) {
        return metaDir(vaultRoot).resolve(USERS_FILE);
    }

    public static Path backupsDir(Path vaultRoot) {
        return metaDir(vaultRoot).resolve(BACKUPS_DIR);
    }

    public static Path manifestsDir(Path vaultRoot) {
        return metaDir(vaultRoot).resolve(MANIFESTS_DIR);
    }

    public void writeHeader(Path vaultRoot, VaultHeader header)
            throws VaultStorageException {

        byte[] json = gson.toJson(header).getBytes(StandardCharsets.UTF_8);
        BoundedFiles.requireWithin(json, MAX_HEADER_BYTES);

        try {
            Files.createDirectories(metaDir(vaultRoot));
            writer.write(metaDir(vaultRoot).resolve(VAULT_HEADER), json);

        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }

    public VaultHeader readHeader(Path vaultRoot) throws VaultStorageException {
        String text;

        try {
            text = BoundedFiles.readUtf8(metaDir(vaultRoot).resolve(VAULT_HEADER), MAX_HEADER_BYTES);

        } catch (NoSuchFileException e) {
            throw new VaultStorageException(VaultStorageException.Reason.NOT_FOUND, e);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }

        JsonObject json = MetadataJson.object(text);
        int formatVersion = MetadataJson.integer(json, "formatVersion");

        // Checked first so a newer vault is reported as such, whatever else it contains.
        if (formatVersion > FORMAT_VERSION) {
            throw new VaultStorageException(VaultStorageException.Reason.UNSUPPORTED_VERSION);
        }

        VaultHeader header = new VaultHeader(
                formatVersion,
                MetadataJson.string(json, "vaultId"),
                MetadataJson.string(json, "createdAt"),
                MetadataJson.kdf(json, "kdf"),
                MetadataJson.envelope(json, "wrappedRegistryKey")
        );

        if (formatVersion != FORMAT_VERSION
                || !isCanonicalUuid(header.getVaultId())
                || !isInstant(header.getCreatedAt())) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);
        }

        return header;
    }

    private static boolean isInstant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static boolean isCanonicalUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
