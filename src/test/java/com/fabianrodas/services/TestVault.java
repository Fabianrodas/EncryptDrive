package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.BlobRepository;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import com.fabianrodas.security.SensitiveBytes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** A throwaway vault with registered accounts, for service tests. */
final class TestVault implements AutoCloseable {

    static final String VAULT_PASSWORD = "correct vault password";

    record Account(UserSessionIdentity identity, byte[] userMasterKey) {
        Supplier<SensitiveBytes> key() {
            return () -> SensitiveBytes.copyOf(userMasterKey);
        }
    }

    final VaultContext vault;
    private final Path dir;
    private final VaultService vaultService = new VaultService();

    TestVault(Path dir) throws VaultException {
        this.dir = dir;
        this.vault = vaultService.createVault(dir.resolve("vault"), VAULT_PASSWORD.toCharArray());
    }

    /** Registers {@code username} with the password "{@code username} password" and logs in. */
    Account register(String username) throws AuthException {
        AuthService auth = new AuthService(vault);
        auth.register(username + " Example", username, (username + " password").toCharArray());
        UserLoginResult login = auth.login(username, (username + " password").toCharArray());

        try (SensitiveBytes key = login.userMasterKey()) {
            return new Account(login.identity(), key.copy());
        }
    }

    FileService files(Account account) {
        return files(account, new ManifestRepository(vault));
    }

    FileService files(Account account, ManifestRepository manifests) {
        return files(account, manifests, new BlobRepository());
    }

    FileService files(Account account, ManifestRepository manifests, BlobRepository blobs) {
        return new FileService(vault, account.identity(), account.key(),
                manifests, blobs, new StreamingFileCryptoService());
    }

    Path source(String name, byte[] content) throws IOException {
        Path directory = Files.createDirectories(dir.resolve("sources").resolve(UUID.randomUUID().toString()));
        return Files.write(directory.resolve(name), content);
    }

    /** Imports a small file whose content is its own name. */
    ManifestEntry importText(FileService files, String name, UUID folderId) throws Exception {
        return files.importFile(source(name, name.getBytes(UTF_8)), folderId);
    }

    List<Path> blobFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(vault.root().resolve("storage"))) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    Path blob(ManifestEntry file) {
        return new BlobRepository().blobPath(vault.root(), file.getBlobId());
    }

    UserManifest manifest(Account account) throws VaultStorageException {
        return new ManifestRepository(vault).load(
                account.identity().userId(), account.identity().manifestId(), account.userMasterKey());
    }

    Path manifestFile(Account account) {
        return vault.root().resolve(".encryptdrive").resolve("manifests")
                .resolve(account.identity().manifestId() + ".enc");
    }

    Path manifestBackup(Account account, int generation) {
        return vault.root().resolve(".encryptdrive").resolve("backups").resolve("manifests")
                .resolve(account.identity().manifestId() + ".enc." + generation);
    }

    /** Decrypts one backup generation the way recovery would, then puts the current file back. */
    UserManifest manifestBackupContent(Account account, int generation) throws Exception {
        Path current = manifestFile(account);
        byte[] saved = Files.readAllBytes(current);

        try {
            Files.copy(manifestBackup(account, generation), current, StandardCopyOption.REPLACE_EXISTING);
            return manifest(account);
        } finally {
            Files.write(current, saved);
        }
    }

    @Override
    public void close() {
        vaultService.closeVault();
    }
}
