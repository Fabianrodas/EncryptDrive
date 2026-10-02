package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.VaultContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class VaultServiceTest {

    private static final String PASSWORD = "correct vault password";
    private static final String NEW_PASSWORD = "another vault password";
    private static final String WRONG_PASSWORD = "wrong vault password";

    @TempDir
    Path tempDir;

    private final VaultService vaultService = new VaultService();
    private Path root;

    @BeforeEach
    void chooseVaultFolder() {
        root = tempDir.resolve("My Vault");
    }

    @AfterEach
    void closeVault() {
        vaultService.closeVault();
    }

    @Test
    void createVaultCreatesOnlyExpectedStructure() throws Exception {
        vaultService.createVault(root, PASSWORD.toCharArray());

        assertEquals(Set.of(
                ".encryptdrive",
                ".encryptdrive/vault.json",
                ".encryptdrive/users.enc",
                ".encryptdrive/lock",
                ".encryptdrive/manifests",
                ".encryptdrive/backups",
                ".encryptdrive/backups/manifests",
                "storage",
                "storage/blobs"
        ), relativePaths(root));
    }

    @Test
    void createVaultDoesNotWritePasswordOrUserDataInPlaintext() throws Exception {
        createAndClose();

        for (Path file : regularFiles(root)) {
            byte[] bytes = Files.readAllBytes(file);

            assertFalse(new String(bytes, ISO_8859_1).contains(PASSWORD), file.toString());
            assertFalse(new String(bytes, UTF_16LE).contains(PASSWORD), file.toString());
        }

        String registry = Files.readString(usersFile(), UTF_8);
        assertFalse(registry.contains("\"users\""));
        assertFalse(registry.contains("formatVersion"));
    }

    @Test
    void correctPasswordUnlocksVault() throws Exception {
        String vaultId = vaultService.createVault(root, PASSWORD.toCharArray()).vaultId();
        vaultService.closeVault();

        VaultContext context = unlock(PASSWORD);

        assertEquals(vaultId, context.vaultId());
        assertFalse(context.isClosed());
        assertTrue(VaultSessionService.isOpen());
    }

    @Test
    void wrongPasswordCannotUnwrapRegistryKey() throws Exception {
        createAndClose();

        assertReason(VaultException.Reason.UNLOCK_FAILED, () -> unlock(WRONG_PASSWORD));
        assertFalse(VaultSessionService.isOpen());
    }

    @Test
    void tamperedWrappedRegistryKeyFailsUnlock() throws Exception {
        createAndClose();
        editHeader(header -> {
            JsonObject wrapped = header.getAsJsonObject("wrappedRegistryKey");
            byte[] ciphertext = Base64.getDecoder().decode(
                    wrapped.get("ciphertext").getAsString()
            );
            ciphertext[0] ^= 0x01;
            wrapped.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        });

        assertReason(VaultException.Reason.UNLOCK_FAILED, () -> unlock(PASSWORD));
    }

    @Test
    void failedUnlockReleasesTheLock() throws Exception {
        createAndClose();

        assertReason(VaultException.Reason.UNLOCK_FAILED, () -> unlock(WRONG_PASSWORD));

        assertDoesNotThrow(() -> unlock(PASSWORD));
    }

    @Test
    void unlockingAVaultThatIsAlreadyOpenIsBusy() throws Exception {
        vaultService.createVault(root, PASSWORD.toCharArray());

        assertReason(
                VaultException.Reason.BUSY,
                () -> new VaultService().unlockVault(root, PASSWORD.toCharArray())
        );
    }

    @Test
    void closeVaultDestroysRegistryKey() throws Exception {
        VaultContext context = vaultService.createVault(root, PASSWORD.toCharArray());

        vaultService.closeVault();

        assertTrue(context.isClosed());
        assertThrows(IllegalStateException.class, context::copyRegistryKey);
        assertFalse(VaultSessionService.isOpen());
    }

    @Test
    void changeVaultPasswordKeepsSameRegistryKey() throws Exception {
        byte[] keyBefore = vaultService.createVault(root, PASSWORD.toCharArray())
                .copyRegistryKey();
        byte[] registryBefore = Files.readAllBytes(usersFile());

        vaultService.changeVaultPassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray());
        vaultService.closeVault();

        assertArrayEquals(keyBefore, unlock(NEW_PASSWORD).copyRegistryKey());
        assertArrayEquals(registryBefore, Files.readAllBytes(usersFile()));
    }

    @Test
    void oldVaultPasswordFailsAfterChange() throws Exception {
        vaultService.createVault(root, PASSWORD.toCharArray());
        vaultService.changeVaultPassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray());
        vaultService.closeVault();

        assertReason(VaultException.Reason.UNLOCK_FAILED, () -> unlock(PASSWORD));
    }

    @Test
    void newVaultPasswordWorksAfterChange() throws Exception {
        String vaultId = vaultService.createVault(root, PASSWORD.toCharArray()).vaultId();
        vaultService.changeVaultPassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray());
        vaultService.closeVault();

        assertEquals(vaultId, unlock(NEW_PASSWORD).vaultId());
    }

    @Test
    void changeVaultPasswordRequiresTheCurrentPassword() throws Exception {
        vaultService.createVault(root, PASSWORD.toCharArray());
        byte[] headerBefore = Files.readAllBytes(headerFile());

        assertReason(
                VaultException.Reason.UNLOCK_FAILED,
                () -> vaultService.changeVaultPassword(
                        WRONG_PASSWORD.toCharArray(),
                        NEW_PASSWORD.toCharArray()
                )
        );

        assertArrayEquals(headerBefore, Files.readAllBytes(headerFile()));
    }

    @Test
    void vaultPasswordsShorterThanTwelveCharactersAreRejected() throws Exception {
        assertReason(
                VaultException.Reason.INVALID_PASSWORD,
                () -> vaultService.createVault(root, "elevenchars".toCharArray())
        );
        assertFalse(Files.exists(root));

        vaultService.createVault(root, PASSWORD.toCharArray());
        assertReason(
                VaultException.Reason.INVALID_PASSWORD,
                () -> vaultService.changeVaultPassword(
                        PASSWORD.toCharArray(),
                        "elevenchars".toCharArray()
                )
        );
    }

    @Test
    void createVaultRefusesAFolderThatIsNotEmpty() throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("notes.txt"), "keep me", UTF_8);

        assertReason(
                VaultException.Reason.ALREADY_EXISTS,
                () -> vaultService.createVault(root, PASSWORD.toCharArray())
        );

        assertEquals(List.of("notes.txt"), List.copyOf(relativePaths(root)));
    }

    @Test
    void aFailedCreationLeavesNothingBehindAndCanBeRetried() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("staged"));
        Path target = parent.resolve("retry");
        VaultService failing = new VaultService() {
            @Override
            void moveIntoPlace(Path from, Path to) throws IOException {
                throw new IOException("simulated failure before the vault is published");
            }
        };

        assertReason(VaultException.Reason.STORAGE, () -> failing.createVault(target, PASSWORD.toCharArray()));

        assertFalse(Files.exists(target));
        assertEquals(List.of(), names(parent));          // no staging folder left
        assertFalse(VaultSessionService.isOpen());

        vaultService.createVault(target, PASSWORD.toCharArray());
        vaultService.closeVault();
        vaultService.unlockVault(target, PASSWORD.toCharArray());
    }

    @Test
    void anIncompleteLookingFolderWithAUserFileIsRefusedAndUntouched() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("staged"));
        Path target = parent.resolve("looks-incomplete");
        Files.createDirectories(target.resolve(".encryptdrive").resolve("manifests"));
        Files.createDirectories(target.resolve("storage").resolve("blobs"));
        Path userFile = Files.writeString(target.resolve("my notes.txt"), "do not touch");
        FileTime modified = Files.getLastModifiedTime(userFile);
        List<String> before = tree(target);

        assertReason(VaultException.Reason.ALREADY_EXISTS, () -> vaultService.createVault(target, PASSWORD.toCharArray()));

        assertEquals("do not touch", Files.readString(userFile));
        assertEquals(modified, Files.getLastModifiedTime(userFile));
        assertEquals(before, tree(target));
        assertEquals(List.of("looks-incomplete"), names(parent));
    }

    @Test
    void aBareSkeletonIsNotReusedEither() throws Exception {
        Path target = tempDir.resolve("staged").resolve("skeleton");
        Files.createDirectories(target.resolve(".encryptdrive").resolve("manifests"));
        List<String> before = tree(target);

        assertReason(VaultException.Reason.ALREADY_EXISTS, () -> vaultService.createVault(target, PASSWORD.toCharArray()));

        assertEquals(before, tree(target));
    }

    @Test
    void anEmptyTargetFolderAndAStaleStagingFolderDoNotBlockCreation() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("staged"));
        Path target = Files.createDirectories(parent.resolve("fresh"));
        Path stale = Files.createDirectories(parent.resolve(".fresh.creating-00000000-0000-0000-0000-000000000000"));
        Files.writeString(stale.resolve("left by a crash"), "x");

        vaultService.createVault(target, PASSWORD.toCharArray());
        vaultService.closeVault();

        vaultService.unlockVault(target, PASSWORD.toCharArray());
        assertTrue(Files.exists(stale.resolve("left by a crash")));   // never ours to delete
    }

    @Test
    void aFolderWithoutAParentIsRefusedBeforeAnythingIsWritten() {
        assertReason(
                VaultException.Reason.STORAGE,
                () -> vaultService.createVault(tempDir.getRoot(), PASSWORD.toCharArray())
        );
        assertFalse(VaultSessionService.isOpen());
    }

    @Test
    void anEmptyTargetThatCannotBeReplacedIsGivenBack() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("staged").resolve("empty"));

        // The staging folder does not exist, so the rename fails after the empty target was removed.
        assertThrows(IOException.class, () -> vaultService.moveIntoPlace(tempDir.resolve("no-such-staging"), target));

        assertTrue(Files.isDirectory(target));
        assertEquals(List.of(), names(target));
    }

    @Test
    void anythingButAnEmptyFolderIsNeverReplacedByThePublishingRename() throws Exception {
        Path staging = Files.createDirectories(tempDir.resolve("finished"));
        Path occupied = Files.createDirectories(tempDir.resolve("staged").resolve("occupied"));
        Path userFile = Files.writeString(occupied.resolve("my notes.txt"), "do not touch");
        Path asFile = Files.writeString(tempDir.resolve("staged").resolve("a-file"), "do not touch");

        assertThrows(IOException.class, () -> vaultService.moveIntoPlace(staging, occupied));
        assertThrows(IOException.class, () -> vaultService.moveIntoPlace(staging, asFile));

        assertEquals("do not touch", Files.readString(userFile));
        assertEquals("do not touch", Files.readString(asFile));
        assertTrue(Files.isDirectory(staging));
    }

    @Test
    void unlockRejectsAFolderThatIsNotAVault() throws Exception {
        Files.createDirectories(root);

        assertReason(VaultException.Reason.NOT_A_VAULT, () -> unlock(PASSWORD));
    }

    @Test
    void corruptedHeaderIsReportedAsCorrupted() throws Exception {
        createAndClose();
        Files.writeString(headerFile(), "{\"formatVersion\": 1,", UTF_8);

        assertReason(VaultException.Reason.CORRUPTED, () -> unlock(PASSWORD));
    }

    @Test
    void absurdKdfParametersAreRejectedBeforeDerivation() throws Exception {
        createAndClose();
        editHeader(header -> header.getAsJsonObject("kdf").addProperty("memoryKiB", 4_194_304));

        assertReason(VaultException.Reason.CORRUPTED, () -> unlock(PASSWORD));
    }

    @Test
    void typeConfusedHeaderIsReportedAsCorrupted() throws Exception {
        createAndClose();
        editHeader(header -> header.getAsJsonObject("kdf").addProperty("iterations", "3"));

        assertReason(VaultException.Reason.CORRUPTED, () -> unlock(PASSWORD));
    }

    private VaultContext unlock(String password) throws VaultException {
        return vaultService.unlockVault(root, password.toCharArray());
    }

    private void createAndClose() throws VaultException {
        vaultService.createVault(root, PASSWORD.toCharArray());
        vaultService.closeVault();
    }

    private Path headerFile() {
        return root.resolve(".encryptdrive").resolve("vault.json");
    }

    private Path usersFile() {
        return root.resolve(".encryptdrive").resolve("users.enc");
    }

    private void editHeader(Consumer<JsonObject> edit) throws IOException {
        JsonObject header = JsonParser.parseString(
                Files.readString(headerFile(), UTF_8)
        ).getAsJsonObject();
        edit.accept(header);
        Files.writeString(headerFile(), header.toString(), UTF_8);
    }

    private static void assertReason(VaultException.Reason reason, Executable action) {
        VaultException error = assertThrows(VaultException.class, action);
        assertEquals(reason, error.getReason());
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> tree(Path dir) throws IOException {
        try (Stream<Path> entries = Files.walk(dir)) {
            return entries.map(entry -> dir.relativize(entry).toString()).sorted().toList();
        }
    }

    private static Set<String> relativePaths(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> !path.equals(root))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
        }
    }

    private static List<Path> regularFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }
}
