package com.fabianrodas.services;

import com.fabianrodas.models.Vault;
import java.nio.file.Path;

public final class VaultSessionService {

    private static Vault currentVault;
    private static Path vaultRoot;

    private VaultSessionService() {
    }

    public static void openVault(Vault vault, Path rootPath) {
        currentVault = vault;
        vaultRoot = rootPath.toAbsolutePath().normalize();
    }

    public static boolean hasOpenVault() {
        return currentVault != null && vaultRoot != null;
    }

    public static Vault getCurrentVault() {
        return currentVault;
    }

    public static Path getVaultRoot() {
        ensureVaultIsOpen();
        return vaultRoot;
    }

    public static Path getMetadataDirectory() {
        ensureVaultIsOpen();
        return vaultRoot.resolve(".encryptdrive");
    }

    public static Path getUsersFile() {
        return getMetadataDirectory().resolve("users.json");
    }

    public static Path getStorageDirectory() {
        ensureVaultIsOpen();
        return vaultRoot.resolve("storage");
    }

    public static void closeVault() {
        currentVault = null;
        vaultRoot = null;
    }

    private static void ensureVaultIsOpen() {
        if (!hasOpenVault()) {
            throw new IllegalStateException("No EncryptDrive vault is open.");
        }
    }
}