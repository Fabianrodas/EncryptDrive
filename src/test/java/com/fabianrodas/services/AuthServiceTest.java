package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.KdfConfig;
import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.UserRecord;
import com.fabianrodas.models.UserRegistry;
import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.UserRegistryRepository;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.Argon2KeyDeriver;
import com.fabianrodas.security.SensitiveBytes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class AuthServiceTest {

    private static final String PASSWORD = "example password";
    private static final String NEW_PASSWORD = "changed password";

    @TempDir
    Path tempDir;

    private final VaultService vaultService = new VaultService();
    private VaultContext vault;
    private AuthService auth;

    @BeforeEach
    void createVault() throws Exception {
        vault = vaultService.createVault(
                tempDir.resolve("vault"),
                "correct vault password".toCharArray()
        );
        auth = new AuthService(vault);
    }

    @AfterEach
    void closeVault() {
        vaultService.closeVault();
    }

    @Test
    void registerCreatesRandomUmkAndEncryptedManifestReference() throws Exception {
        UserSessionIdentity alice = auth.register("Alice Example", "alice", "alice password".toCharArray());
        UserSessionIdentity bob = auth.register("Bob Example", "bob", "bob password".toCharArray());

        assertNotEquals(alice.userId(), bob.userId());
        assertNotEquals(alice.manifestId(), bob.manifestId());

        byte[] aliceKey = userMasterKey("alice", "alice password");
        byte[] bobKey = userMasterKey("bob", "bob password");

        assertEquals(32, aliceKey.length);
        assertFalse(Arrays.equals(aliceKey, bobKey));
    }

    @Test
    void registrationCreatesAnEncryptedManifestWithARootFolder() throws Exception {
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());

        UserManifest manifest = new ManifestRepository(vault).load(
                user.userId(), user.manifestId(), userMasterKey("ExampleUser", PASSWORD)
        );

        ManifestEntry root = new ManifestService(manifest).find(manifest.getRootFolderId());
        assertEquals(ManifestEntryKind.FOLDER, root.getKind());
        assertEquals("/", root.getName());
        assertEquals(null, root.getParentId());
        assertEquals(1, manifest.getEntries().size());
    }

    @Test
    void usernamesAreUniqueCaseInsensitively() throws Exception {
        auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());

        assertReason(
                AuthException.Reason.USERNAME_TAKEN,
                () -> auth.register("Other User", "exampleuser", "other password".toCharArray())
        );
        assertReason(
                AuthException.Reason.USERNAME_TAKEN,
                () -> auth.register("Other User", "  EXAMPLEUSER ", "other password".toCharArray())
        );
    }

    @Test
    void correctPasswordUnwrapsUmk() throws Exception {
        UserSessionIdentity registered = auth.register(
                "Example User", "ExampleUser", PASSWORD.toCharArray()
        );

        UserLoginResult result = auth.login(" exampleUSER ", PASSWORD.toCharArray());

        try (SensitiveBytes userMasterKey = result.userMasterKey()) {
            assertEquals(registered, result.identity());
            assertEquals("Example User", result.identity().fullName());
            assertEquals("ExampleUser", result.identity().username());
            assertEquals(32, userMasterKey.copy().length);
        }
    }

    @Test
    void wrongPasswordCannotUnwrapUmk() throws Exception {
        auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());

        assertReason(
                AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.login("ExampleUser", "wrong password".toCharArray())
        );
        assertReason(
                AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.login("SomebodyElse", PASSWORD.toCharArray())
        );
    }

    @Test
    void changePasswordKeepsSameUmk() throws Exception {
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());
        byte[] before = userMasterKey("ExampleUser", PASSWORD);

        auth.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray());

        assertArrayEquals(before, userMasterKey("ExampleUser", NEW_PASSWORD));
    }

    @Test
    void oldPasswordFailsAfterPasswordChange() throws Exception {
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());

        auth.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray());

        assertReason(
                AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.login("ExampleUser", PASSWORD.toCharArray())
        );
    }

    @Test
    void newPasswordWorksAfterPasswordChange() throws Exception {
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());

        auth.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray());

        assertEquals(user, auth.login("ExampleUser", NEW_PASSWORD.toCharArray()).identity());
    }

    @Test
    void changePasswordRequiresTheCurrentPassword() throws Exception {
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());
        Path usersFile = vault.root().resolve(".encryptdrive").resolve("users.enc");
        byte[] registryBefore = Files.readAllBytes(usersFile);

        assertReason(
                AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.changePassword(
                        user.userId(), "wrong password".toCharArray(), NEW_PASSWORD.toCharArray()
                )
        );

        assertArrayEquals(registryBefore, Files.readAllBytes(usersFile));
    }

    @Test
    void registrationRejectsInvalidInput() {
        assertReason(
                AuthException.Reason.INVALID_INPUT,
                () -> auth.register("   ", "ExampleUser", PASSWORD.toCharArray())
        );
        assertReason(
                AuthException.Reason.INVALID_INPUT,
                () -> auth.register("Example User", "ab", PASSWORD.toCharArray())
        );
        assertReason(
                AuthException.Reason.INVALID_INPUT,
                () -> auth.register("Example User", "ExampleUser", "seven77".toCharArray())
        );
    }

    @Test
    void registeredAccountsRevealNothingAtRest() throws Exception {
        UserSessionIdentity user = auth.register("Example Person", "ExampleUser", PASSWORD.toCharArray());
        vaultService.closeVault();

        List<String> secrets = List.of(
                "Example Person", "ExampleUser", "exampleuser", user.userId().toString(), PASSWORD
        );

        try (Stream<Path> files = Files.walk(vault.root())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String raw = new String(Files.readAllBytes(file), ISO_8859_1);

                for (String secret : secrets) {
                    assertFalse(raw.contains(secret), file + " contains " + secret);
                }
            }
        }
    }

    @Test
    void newAccountsNeedTwelveCharacterPasswords() throws Exception {
        assertReason(
                AuthException.Reason.INVALID_INPUT,
                () -> auth.register("Short Pass", "shortpass", "elevenchars".toCharArray())
        );

        UserSessionIdentity user = auth.register("Long Pass", "longpass", "twelve chars".toCharArray());

        assertEquals(user, auth.login("longpass", "twelve chars".toCharArray()).identity());
    }

    @Test
    void passwordsAreNotTrimmed() throws Exception {
        auth.register("Space User", "spaceuser", "elevenchars ".toCharArray());

        assertReason(
                AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.login("spaceuser", "elevenchars".toCharArray())
        );
        auth.login("spaceuser", "elevenchars ".toCharArray()).userMasterKey().close();
    }

    @Test
    void changedPasswordsNeedTwelveCharacters() throws Exception {
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());

        assertReason(
                AuthException.Reason.INVALID_INPUT,
                () -> auth.changePassword(user.userId(), PASSWORD.toCharArray(), "elevenchars".toCharArray())
        );
        auth.login("ExampleUser", PASSWORD.toCharArray()).userMasterKey().close();
    }

    @Test
    void preReleaseShortPasswordStillLogsIn() throws Exception {
        UserSessionIdentity legacy = addPreReleaseAccount("legacy", "short123");

        assertEquals(legacy, auth.login("legacy", "short123".toCharArray()).identity());
        assertReason(
                AuthException.Reason.INVALID_INPUT,
                () -> auth.changePassword(legacy.userId(), "short123".toCharArray(), "short456".toCharArray())
        );
        auth.changePassword(legacy.userId(), "short123".toCharArray(), "a much longer one".toCharArray());
        assertEquals(legacy, auth.login("legacy", "a much longer one".toCharArray()).identity());
    }

    /** An account as an 8-character-minimum development build created it. */
    private UserSessionIdentity addPreReleaseAccount(String username, String password) throws Exception {
        Argon2KeyDeriver deriver = new Argon2KeyDeriver();
        KdfConfig kdf = deriver.newConfig();
        UUID userId = UUID.randomUUID();
        UUID manifestId = UUID.randomUUID();
        byte[] userMasterKey = new byte[32];
        new SecureRandom().nextBytes(userMasterKey);
        byte[] keyEncryptionKey = deriver.derive(password.toCharArray(), kdf);
        EncryptedPayload wrapped = new AesGcmService().wrapKey(
                userMasterKey, keyEncryptionKey, Aad.userKey(vault.vaultId(), userId.toString())
        );
        new ManifestRepository(vault).save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        UserRegistryRepository registries = new UserRegistryRepository();
        UserRegistry registry = registries.load(vault);
        registry.getUsers().add(new UserRecord(
                userId.toString(), "Legacy User", username, username.toLowerCase(Locale.ROOT),
                Instant.now().toString(), manifestId.toString(), kdf, wrapped
        ));
        registries.save(vault, registry);
        return new UserSessionIdentity(userId, "Legacy User", username, manifestId);
    }

    private byte[] userMasterKey(String username, String password) throws AuthException {
        try (SensitiveBytes key = auth.login(username, password.toCharArray()).userMasterKey()) {
            return key.copy();
        }
    }

    private static void assertReason(AuthException.Reason reason, Executable action) {
        AuthException error = assertThrows(AuthException.class, action);
        assertEquals(reason, error.getReason());
    }
}
