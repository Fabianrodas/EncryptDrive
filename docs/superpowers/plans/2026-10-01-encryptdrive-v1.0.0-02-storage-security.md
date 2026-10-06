# EncryptDrive v1.0.0 — Plan 02: Storage and Security Hardening

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every storage/security release blocker: bounded and strictly validated metadata, 12-character account passwords, no old-password envelopes in registry backups, crash-safe permanent deletion with an encrypted journal, and one close guard for every exit path.

**Architecture:** Changes stay inside the existing owners. Repositories own size limits, strict parsing and the new checkpoint saves (write current, then overwrite every backup generation). `FileService` owns the deletion engine (journal in `UserManifest.pendingDeletions`). `App` owns the close guard; controllers only call it.

**Tech Stack:** Java 21 NIO, Gson `JsonParser`, JUnit 6, JavaFX test harness (`FxTestSupport`).

**Spec:** `docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md` sections 7–12, 21.1–21.4.

## Global Constraints

See the master plan. In particular: limits 256 KiB / 16 MiB / 64 MiB checked **before** reading; no plaintext journal; no change to Argon2/AES-GCM/AAD; every commit ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

Run focused tests with `mvn -B -q test "-Dtest=Class#method"`; finish every task with `mvn -B clean verify`.

---

### Task 4: Bounded metadata reads and writes

**Purpose:** Spec 12 (limits before `readString`/parse/allocation), 21.4 (oversize fails safely). Also refuse writes above the same limits (dangerous failure mode #2) and stop copying metadata through the heap in `BackupRotator`.

**Files:**
- Create: `src/main/java/com/fabianrodas/repositories/BoundedFiles.java`
- Modify: `src/main/java/com/fabianrodas/repositories/VaultStorageException.java` (add `TOO_LARGE`)
- Modify: `src/main/java/com/fabianrodas/repositories/AtomicFileWriter.java` (add `copy`, shared `replace`)
- Modify: `src/main/java/com/fabianrodas/repositories/BackupRotator.java` (stream copies)
- Modify: `src/main/java/com/fabianrodas/repositories/VaultRepository.java`, `UserRegistryRepository.java`, `ManifestRepository.java`
- Modify: `src/main/java/com/fabianrodas/services/FileServiceException.java` (add `LIMIT`)
- Modify: `src/main/java/com/fabianrodas/services/FileService.java` (map `TOO_LARGE` → `LIMIT`)
- Modify: `src/main/java/com/fabianrodas/services/AuthService.java`, `VaultService.java` (exhaustive reason mapping)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/FilesController.java` (`describe` case for `LIMIT`)
- Test: `src/test/java/com/fabianrodas/repositories/BoundedFilesTest.java` (new), `VaultRepositoryTest.java`, `UserRegistryRepositoryTest.java`, `ManifestRepositoryTest.java`, `AtomicFileWriterTest.java`

**Interfaces:**
- Produces: `BoundedFiles.readUtf8(Path, long)`, `BoundedFiles.requireWithin(byte[], long)`, `VaultStorageException.Reason.TOO_LARGE`, `VaultRepository.MAX_HEADER_BYTES`, `UserRegistryRepository.MAX_REGISTRY_BYTES`, `ManifestRepository.MAX_MANIFEST_BYTES`, `AtomicFileWriter.copy(Path, Path)`, `FileServiceException.Reason.LIMIT`, test helper `BoundedFilesTest.sparseFile(Path, long)`.

**Security:** an oversize metadata file is corruption/tampering; it must never be read into memory. Recovery from backups still applies (an oversize current file falls back to an authentic backup).

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/fabianrodas/repositories/BoundedFilesTest.java`:

```java
package com.fabianrodas.repositories;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedFilesTest {

    @TempDir
    Path dir;

    @Test
    void fileAtTheLimitIsRead() throws Exception {
        Path file = Files.writeString(dir.resolve("meta"), "x".repeat(1024));

        assertEquals(1024, BoundedFiles.readUtf8(file, 1024).length());
    }

    @Test
    void fileOverTheLimitIsRejectedBeforeItIsRead() throws Exception {
        // Reading 3 GiB into a String would fail with OutOfMemoryError, not this.
        Path file = sparseFile(dir.resolve("huge"), 3L << 30);

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> BoundedFiles.readUtf8(file, 1024)
        );

        assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
    }

    @Test
    void writesOverTheLimitAreRefused() {
        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> BoundedFiles.requireWithin(new byte[1025], 1024)
        );

        assertEquals(VaultStorageException.Reason.TOO_LARGE, error.getReason());
        assertDoesNotThrow(() -> BoundedFiles.requireWithin(new byte[1024], 1024));
    }

    /** A file that reports {@code size} bytes without using that much disk. */
    static Path sparseFile(Path file, long size) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[1]));
        }

        return file;
    }
}
```

Add to `VaultRepositoryTest`:

```java
    @Test
    void oversizedHeaderIsRejectedBeforeParsing(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".encryptdrive"));
        BoundedFilesTest.sparseFile(tempDir.resolve(".encryptdrive").resolve("vault.json"), 3L << 30);

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.readHeader(tempDir)
        );

        assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
    }

    @Test
    void headerOfExactlyTheLimitStillReads(@TempDir Path tempDir) throws Exception {
        repository.writeHeader(tempDir, header(1));
        Path file = tempDir.resolve(".encryptdrive").resolve("vault.json");
        String json = Files.readString(file, UTF_8);
        Files.writeString(file, json + " ".repeat((int) VaultRepository.MAX_HEADER_BYTES - json.length()), UTF_8);

        assertEquals(VaultRepository.MAX_HEADER_BYTES, Files.size(file));
        assertEquals(VAULT_ID, repository.readHeader(tempDir).getVaultId());

        Files.writeString(file, " ", UTF_8, StandardOpenOption.APPEND);
        assertEquals(
                VaultStorageException.Reason.CORRUPTED,
                assertThrows(VaultStorageException.class, () -> repository.readHeader(tempDir)).getReason()
        );
    }
```
(import `java.nio.file.StandardOpenOption`).

Add to `UserRegistryRepositoryTest`:

```java
    @Test
    void oversizedRegistryFallsBackToAnAuthenticBackup() throws Exception {
        repository.save(vault, registryWith(record()));
        repository.save(vault, new UserRegistry(1, new ArrayList<>(List.of(record()))));
        Files.delete(usersFile());
        BoundedFilesTest.sparseFile(usersFile(), 3L << 30);

        assertEquals(USER_ID, repository.load(vault).getUsers().get(0).getUserId());
        assertTrue(Files.size(usersFile()) < UserRegistryRepository.MAX_REGISTRY_BYTES);
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void oversizedRegistryBackupIsSkipped() throws Exception {
        repository.save(vault, registryWith(record()));
        repository.save(vault, registryWith(record()));
        repository.save(vault, registryWith(record()));
        Path backups = root.resolve(".encryptdrive").resolve("backups");
        Files.delete(backups.resolve("users.enc.1"));
        BoundedFilesTest.sparseFile(backups.resolve("users.enc.1"), 3L << 30);
        Files.writeString(usersFile(), "{}", UTF_8);

        assertEquals(USER_ID, repository.load(vault).getUsers().get(0).getUserId());
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void saveRefusesRegistryOverTheLimit() throws Exception {
        repository.save(vault, registryWith(record()));
        byte[] before = Files.readAllBytes(usersFile());
        UserRecord huge = new UserRecord(
                USER_ID, "x".repeat(17 * 1024 * 1024), "ExampleUser", "exampleuser",
                "2026-09-30T17:05:00Z", "7c8d9e0f-1a2b-4c3d-8e4f-5a6b7c8d9e0f",
                record().getUserKdf(), record().getWrappedUserMasterKey()
        );

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.save(vault, registryWith(huge))
        );

        assertEquals(VaultStorageException.Reason.TOO_LARGE, error.getReason());
        assertArrayEquals(before, Files.readAllBytes(usersFile()));
    }
```
(imports `assertTrue`; `java.util.ArrayList` and `java.util.List` already imported).

Add to `ManifestRepositoryTest`:

```java
    @Test
    void oversizedManifestFallsBackToAnAuthenticBackup() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        repository.save(manifestWithNames(), manifestId, userMasterKey);
        Files.delete(manifestFile());
        BoundedFilesTest.sparseFile(manifestFile(), 3L << 30);

        assertEquals(1, repository.load(userId, manifestId, userMasterKey).getEntries().size());
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void oversizedManifestBackupIsSkipped() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        Path backup1 = root.resolve(".encryptdrive/backups/manifests/" + manifestId + ".enc.1");
        Files.delete(backup1);
        BoundedFilesTest.sparseFile(backup1, 3L << 30);
        Files.writeString(manifestFile(), "{}", UTF_8);

        assertEquals(1, repository.load(userId, manifestId, userMasterKey).getEntries().size());
        BackupRotator.takeRecoveryNotice();
    }

    @Test
    void saveRefusesManifestOverTheLimit() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        byte[] before = Files.readAllBytes(manifestFile());
        UserManifest huge = ManifestService.newManifest(userId);
        huge.getEntries().get(0).setName("x".repeat(65 * 1024 * 1024));

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.save(huge, manifestId, userMasterKey)
        );

        assertEquals(VaultStorageException.Reason.TOO_LARGE, error.getReason());
        assertArrayEquals(before, Files.readAllBytes(manifestFile()));
    }
```

Add to `AtomicFileWriterTest`:

```java
    @Test
    void copyStreamsTheSourceIntoPlace(@TempDir Path tempDir) throws IOException {
        Path source = Files.write(tempDir.resolve("source"), new byte[300_000]);
        Path destination = Files.writeString(tempDir.resolve("destination"), "old");

        new AtomicFileWriter().copy(source, destination);

        assertEquals(300_000, Files.size(destination));
        assertEquals(List.of("destination", "source"), names(tempDir));
    }
```
(if `AtomicFileWriterTest` has no `names` helper, add `private static List<String> names(Path dir)` that lists and sorts file names).

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=BoundedFilesTest,VaultRepositoryTest,UserRegistryRepositoryTest,ManifestRepositoryTest,AtomicFileWriterTest"`
Expected: compilation errors — `BoundedFiles`, `TOO_LARGE`, `MAX_HEADER_BYTES`, `AtomicFileWriter.copy` do not exist.

- [ ] **Step 3: Add `TOO_LARGE` and `BoundedFiles`**

`VaultStorageException.Reason` becomes `NOT_FOUND, CORRUPTED, UNSUPPORTED_VERSION, IO, TOO_LARGE`.

`src/main/java/com/fabianrodas/repositories/BoundedFiles.java`:

```java
package com.fabianrodas.repositories;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Size limits for vault metadata. A file over its limit is treated as
 * corrupted before it is read, so a tampered vault cannot make EncryptDrive
 * allocate memory for it; a write over the limit is refused, so EncryptDrive
 * never produces metadata it would reject on the next read.
 */
final class BoundedFiles {

    private BoundedFiles() {
    }

    static String readUtf8(Path file, long maxBytes) throws IOException, VaultStorageException {
        if (Files.size(file) > maxBytes) {
            throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);
        }

        try (InputStream in = Files.newInputStream(file)) {
            // One byte more than allowed catches a file that grew after the size check.
            byte[] bytes = in.readNBytes(Math.toIntExact(maxBytes) + 1);

            if (bytes.length > maxBytes) {
                throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);
            }

            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static void requireWithin(byte[] bytes, long maxBytes) throws VaultStorageException {
        if (bytes.length > maxBytes) {
            throw new VaultStorageException(VaultStorageException.Reason.TOO_LARGE);
        }
    }
}
```

- [ ] **Step 4: Streaming copy in `AtomicFileWriter`**

Replace the body of `AtomicFileWriter` with:

```java
public final class AtomicFileWriter {

    public static final String TEMP_SUFFIX = ".tmp";

    @FunctionalInterface
    private interface Content {
        void writeTo(FileChannel channel) throws IOException;
    }

    public void write(Path destination, byte[] bytes) throws IOException {
        replace(destination, channel -> {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        });
    }

    /** Like {@link #write}, streaming {@code source} instead of holding it in memory. */
    public void copy(Path source, Path destination) throws IOException {
        replace(destination, channel -> {
            try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ)) {
                long position = 0;
                long transferred;

                while ((transferred = in.transferTo(position, Long.MAX_VALUE, channel)) > 0) {
                    position += transferred;
                }
            }
        });
    }

    private void replace(Path destination, Content content) throws IOException {
        Path temporary = Files.createTempFile(
                destination.toAbsolutePath().getParent(),
                destination.getFileName() + ".",
                TEMP_SUFFIX
        );

        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                content.writeTo(channel);
                channel.force(true);
            }

            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );

            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }

        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
```
(keep the class Javadoc).

In `BackupRotator`: in `recover` replace `writer.write(currentFile, Files.readAllBytes(backup));` with `writer.copy(backup, currentFile);`; in `rotate` replace the final `writer.write(backupDirectory.resolve(name + ".1"), Files.readAllBytes(encryptedFile));` with `writer.copy(encryptedFile, backupDirectory.resolve(name + ".1"));`.

- [ ] **Step 5: Apply the limits in the three repositories**

`VaultRepository`:

```java
    /** Spec limit for vault.json; checked before reading and before writing. */
    public static final long MAX_HEADER_BYTES = 256L * 1024;
```
In `writeHeader`, serialize first and check:
```java
            byte[] json = gson.toJson(header).getBytes(StandardCharsets.UTF_8);
            BoundedFiles.requireWithin(json, MAX_HEADER_BYTES);
            Files.createDirectories(metaDir(vaultRoot));
            writer.write(metaDir(vaultRoot).resolve(VAULT_HEADER), json);
```
In `readHeader` replace the `Files.readString(...)` call with
```java
            json = BoundedFiles.readUtf8(metaDir(vaultRoot).resolve(VAULT_HEADER), MAX_HEADER_BYTES);
```

`UserRegistryRepository`:

```java
    /** Spec limit for users.enc and each backup; checked before reading and before writing. */
    public static final long MAX_REGISTRY_BYTES = 16L * 1024 * 1024;
```
In `read` replace `Files.readString(file, StandardCharsets.UTF_8)` with `BoundedFiles.readUtf8(file, MAX_REGISTRY_BYTES)`. Replace `save` with a version that seals first:

```java
    public void save(VaultContext vault, UserRegistry registry) throws VaultStorageException {
        byte[] envelope = seal(vault, registry);
        Path usersFile = VaultRepository.usersFile(vault.root());

        try {
            rotator.rotate(usersFile, VaultRepository.backupsDir(vault.root()), BACKUP_GENERATIONS);
            writer.write(usersFile, envelope);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }

    /** The registry encrypted under the RMK, as the JSON envelope written to disk. */
    private byte[] seal(VaultContext vault, UserRegistry registry) throws VaultStorageException {
        byte[] registryKey = vault.copyRegistryKey();
        byte[] plaintext = gson.toJson(registry).getBytes(StandardCharsets.UTF_8);

        try {
            byte[] envelope = gson.toJson(aes.encrypt(plaintext, registryKey, Aad.users(vault.vaultId())))
                    .getBytes(StandardCharsets.UTF_8);
            BoundedFiles.requireWithin(envelope, MAX_REGISTRY_BYTES);
            return envelope;
        } finally {
            Arrays.fill(registryKey, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }
```

`ManifestRepository`:

```java
    /** Spec limit for one manifest and each backup; checked before reading and before writing. */
    public static final long MAX_MANIFEST_BYTES = 64L * 1024 * 1024;
```
In `read` use `BoundedFiles.readUtf8(file, MAX_MANIFEST_BYTES)`. Replace `save`:

```java
    public void save(UserManifest manifest, UUID manifestId, byte[] userMasterKey)
            throws VaultStorageException {

        byte[] envelope = seal(manifest, userMasterKey);
        Path manifestFile = manifestFile(manifestId);

        try {
            rotator.rotate(manifestFile, manifestBackupsDir(), BACKUP_GENERATIONS);
            writer.write(manifestFile, envelope);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }

    /** The manifest encrypted under the UMK, as the JSON envelope written to disk. */
    private byte[] seal(UserManifest manifest, byte[] userMasterKey) throws VaultStorageException {
        byte[] plaintext = gson.toJson(manifest).getBytes(StandardCharsets.UTF_8);

        try {
            byte[] envelope = gson.toJson(aes.encrypt(
                    plaintext,
                    userMasterKey,
                    Aad.manifest(vault.vaultId(), manifest.getUserId().toString())
            )).getBytes(StandardCharsets.UTF_8);
            BoundedFiles.requireWithin(envelope, MAX_MANIFEST_BYTES);
            return envelope;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
```

- [ ] **Step 6: Map the new reason everywhere it is switched on**

- `VaultService.readHeader` switch: add `case TOO_LARGE -> VaultException.Reason.STORAGE;`.
- `AuthService.storageFailure`: keep `IO → STORAGE`, add `TOO_LARGE → STORAGE`, everything else `CORRUPTED`:
  ```java
        return new AuthException(
                e.getReason() == VaultStorageException.Reason.IO
                        || e.getReason() == VaultStorageException.Reason.TOO_LARGE
                        ? AuthException.Reason.STORAGE
                        : AuthException.Reason.CORRUPTED,
                e
        );
  ```
- `FileServiceException.Reason`: add `LIMIT` after `STORAGE`.
- `FileService.save` becomes:
  ```java
    private void save(UserManifest manifest, byte[] key) throws FileServiceException {
        try {
            manifestRepository.save(manifest, identity.manifestId(), key);
        } catch (VaultStorageException e) {
            throw storageFailure(e);
        }
    }

    private static FileServiceException storageFailure(VaultStorageException e) {
        return new FileServiceException(
                e.getReason() == VaultStorageException.Reason.TOO_LARGE
                        ? FileServiceException.Reason.LIMIT
                        : FileServiceException.Reason.STORAGE,
                e
        );
    }
  ```
- `FilesController.describe`: add `case LIMIT -> "Your encrypted file list has reached EncryptDrive's size limit. Empty the trash or remove files first.";`

- [ ] **Step 7: Run the focused tests**

Run: `mvn -B -q test "-Dtest=BoundedFilesTest,VaultRepositoryTest,UserRegistryRepositoryTest,ManifestRepositoryTest,AtomicFileWriterTest,BackupRotatorTest"`
Expected: PASS.

- [ ] **Step 8: Related + full**

Run: `mvn -B -q test "-Dtest=CorruptionIntegrationTest,VaultServiceTest,AuthServiceTest,FileServiceTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 9: Diff review** — `git grep -n "readAllBytes\|readString" src/main` must show no metadata read outside `BoundedFiles` (file-content code never used them).

- [ ] **Step 10: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/repositories src/main/java/com/fabianrodas/services/FileServiceException.java src/main/java/com/fabianrodas/services/FileService.java src/main/java/com/fabianrodas/services/AuthService.java src/main/java/com/fabianrodas/services/VaultService.java src/main/java/com/fabianrodas/encryptdrive/FilesController.java src/test/java/com/fabianrodas/repositories docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "fix: bound vault metadata reads and writes to the spec limits" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** oversize current/backup files of each kind fail as CORRUPTED without reading them (3 GiB sparse files); oversize writes fail with TOO_LARGE and leave the old file intact; build green.

---

### Task 5: Strict parsing of `vault.json` and envelopes; wrapped-key size check

**Purpose:** Spec 12: mandatory fields with valid JSON types, wrapped 256-bit keys of the expected key+tag size, UUIDs that parse, unsupported versions fail closed, malformed Base64/nonce/envelopes fail closed (21.4).

**Files:**
- Create: `src/main/java/com/fabianrodas/repositories/MetadataJson.java`
- Modify: `src/main/java/com/fabianrodas/repositories/VaultRepository.java` (`readHeader`)
- Modify: `src/main/java/com/fabianrodas/repositories/UserRegistryRepository.java`, `ManifestRepository.java` (envelope parsing)
- Modify: `src/main/java/com/fabianrodas/security/AesGcmService.java` (`WRAPPED_KEY_BYTES`, `unwrapKey`)
- Test: `src/test/java/com/fabianrodas/repositories/MetadataJsonTest.java` (new), `VaultRepositoryTest.java`, `src/test/java/com/fabianrodas/security/AesGcmServiceTest.java`, `src/test/java/com/fabianrodas/services/VaultServiceTest.java`

**Interfaces:**
- Produces: `MetadataJson.object(String)`, `MetadataJson.integer(JsonObject, String)`, `MetadataJson.string(JsonObject, String)`, `MetadataJson.kdf(JsonObject, String)`, `MetadataJson.envelope(JsonObject, String)`, `MetadataJson.envelope(String)`; `AesGcmService.WRAPPED_KEY_BYTES`.

**Security:** `vault.json` and outer envelopes are read before authentication; type confusion must fail closed. Argon2 bounds remain owned by `Argon2KeyDeriver.derive` (checked before allocation; existing test `absurdKdfParametersAreRejectedBeforeDerivation`).

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/fabianrodas/repositories/MetadataJsonTest.java`:

```java
package com.fabianrodas.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.EncryptedPayload;
import org.junit.jupiter.api.Test;

class MetadataJsonTest {

    private static final String VALID
            = "{\"version\":1,\"algorithm\":\"AES/GCM/NoPadding\",\"nonce\":\"bm9uY2Vub25jZW5v\",\"ciphertext\":\"Y2lwaGVydGV4dA==\"}";

    @Test
    void validEnvelopeParses() throws Exception {
        EncryptedPayload payload = MetadataJson.envelope(VALID);

        assertEquals(1, payload.getVersion());
        assertEquals("AES/GCM/NoPadding", payload.getAlgorithm());
        assertEquals("bm9uY2Vub25jZW5v", payload.getNonce());
        assertEquals("Y2lwaGVydGV4dA==", payload.getCiphertext());
    }

    @Test
    void envelopesWithWrongShapesAreCorrupted() {
        for (String json : new String[]{
            VALID.replace("\"nonce\":\"bm9uY2Vub25jZW5v\"", "\"nonce\":5"),
            VALID.replace("\"version\":1", "\"version\":\"1\""),
            VALID.replace("\"version\":1", "\"version\":1.5"),
            VALID.replace("\"version\":1", "\"version\":99999999999"),
            VALID.replace(",\"ciphertext\":\"Y2lwaGVydGV4dA==\"", ""),
            VALID.replace("\"algorithm\":\"AES/GCM/NoPadding\"", "\"algorithm\":null"),
            "[" + VALID + "]",
            "\"text\"",
            "null",
            "",
            "{\"version\":1,"
        }) {
            VaultStorageException error = assertThrows(
                    VaultStorageException.class, () -> MetadataJson.envelope(json), json
            );
            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason(), json);
        }
    }
}
```

Add to `VaultRepositoryTest`:

```java
    @Test
    void typeConfusedHeadersAreCorrupted(@TempDir Path tempDir) throws Exception {
        repository.writeHeader(tempDir, header(1));
        String valid = Files.readString(tempDir.resolve(".encryptdrive").resolve("vault.json"), UTF_8);

        for (String content : new String[]{
            valid.replace("\"memoryKiB\": 65536", "\"memoryKiB\": \"65536\""),
            valid.replace("\"createdAt\": \"2026-09-30T17:00:00Z\"", "\"createdAt\": \"yesterday\""),
            valid.replace("\"formatVersion\": 1", "\"formatVersion\": \"1\""),
            valid.replaceFirst("\"kdf\": \\{", "\"kdf\": [], \"unused\": {")
        }) {
            writeRawHeader(tempDir, content);

            VaultStorageException error = assertThrows(
                    VaultStorageException.class, () -> repository.readHeader(tempDir), content
            );
            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason(), content);
        }
    }

    @Test
    void newerFormatVersionIsReportedEvenWhenTheRestIsUnknown(@TempDir Path tempDir) throws Exception {
        writeRawHeader(tempDir, "{\"formatVersion\": 2, \"somethingNew\": true}");

        VaultStorageException error = assertThrows(
                VaultStorageException.class, () -> repository.readHeader(tempDir)
        );

        assertEquals(VaultStorageException.Reason.UNSUPPORTED_VERSION, error.getReason());
    }
```
(The header is written pretty-printed by `VaultRepository`'s Gson, so `"memoryKiB": 65536` has one space after the colon; confirm by printing `valid` once while writing the test and adjust the literals if Gson's spacing differs.)

Add to `AesGcmServiceTest`:

```java
    @Test
    void unwrapRejectsWrappedKeysOfTheWrongSize() {
        EncryptedPayload wrapped = aes.wrapKey(randomKey(), key, AAD);
        byte[] ciphertext = decode(wrapped.getCiphertext());

        for (int size : new int[]{0, 47, 49, 64}) {
            EncryptedPayload resized = new EncryptedPayload(
                    1, wrapped.getAlgorithm(), wrapped.getNonce(), encode(Arrays.copyOf(ciphertext, size))
            );
            assertThrows(CryptoException.class, () -> aes.unwrapKey(resized, key, AAD), "size " + size);
        }

        assertThrows(CryptoException.class, () -> aes.unwrapKey(null, key, AAD));
        assertThrows(CryptoException.class, () -> aes.unwrapKey(
                new EncryptedPayload(1, wrapped.getAlgorithm(), wrapped.getNonce(), "not base64!"), key, AAD
        ));
    }
```
(import `java.util.Arrays`). Note for the reviewer: this test already passes before Step 3 because GCM authentication rejects these payloads too; it pins the contract while Step 3 adds the spec-required size check before decryption.

Add to `VaultServiceTest`:

```java
    @Test
    void typeConfusedHeaderIsReportedAsCorrupted() throws Exception {
        createAndClose();
        editHeader(header -> header.getAsJsonObject("kdf").addProperty("iterations", "3"));

        assertReason(VaultException.Reason.CORRUPTED, () -> unlock(PASSWORD));
    }
```

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=MetadataJsonTest,VaultRepositoryTest,VaultServiceTest#typeConfusedHeaderIsReportedAsCorrupted"`
Expected: `MetadataJsonTest` does not compile (`MetadataJson` missing); after stubbing nothing, `typeConfusedHeadersAreCorrupted` and `typeConfusedHeaderIsReportedAsCorrupted` fail because Gson accepts `"65536"`/`"3"`; `newerFormatVersionIsReportedEvenWhenTheRestIsUnknown` fails with CORRUPTED... (current code reports UNSUPPORTED_VERSION only after Gson parse — confirm the actual failure and record it).

- [ ] **Step 3: Implement `MetadataJson`**

```java
package com.fabianrodas.repositories;

import com.fabianrodas.models.EncryptedPayload;
import com.fabianrodas.models.KdfConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;

/**
 * Strict reading of metadata that is parsed before it is authenticated:
 * vault.json and the outer encrypted envelopes. Every member must exist with
 * its exact JSON type. Gson's reflective binding would accept "65536" for a
 * number and silently default missing members.
 */
final class MetadataJson {

    private MetadataJson() {
    }

    static JsonObject object(String json) throws VaultStorageException {
        try {
            return object(JsonParser.parseString(json));
        } catch (JsonParseException e) {
            throw corrupted(e);
        }
    }

    static EncryptedPayload envelope(String json) throws VaultStorageException {
        return envelope(object(json));
    }

    static EncryptedPayload envelope(JsonObject parent, String member) throws VaultStorageException {
        return envelope(object(parent.get(member)));
    }

    static KdfConfig kdf(JsonObject parent, String member) throws VaultStorageException {
        JsonObject kdf = object(parent.get(member));

        return new KdfConfig(
                string(kdf, "algorithm"),
                integer(kdf, "memoryKiB"),
                integer(kdf, "iterations"),
                integer(kdf, "parallelism"),
                string(kdf, "salt")
        );
    }

    static String string(JsonObject object, String member) throws VaultStorageException {
        JsonPrimitive value = primitive(object, member);

        if (!value.isString()) {
            throw corrupted(null);
        }

        return value.getAsString();
    }

    static int integer(JsonObject object, String member) throws VaultStorageException {
        JsonPrimitive value = primitive(object, member);

        if (!value.isNumber()) {
            throw corrupted(null);
        }

        try {
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw corrupted(e);
        }
    }

    private static EncryptedPayload envelope(JsonObject envelope) throws VaultStorageException {
        return new EncryptedPayload(
                integer(envelope, "version"),
                string(envelope, "algorithm"),
                string(envelope, "nonce"),
                string(envelope, "ciphertext")
        );
    }

    private static JsonObject object(JsonElement element) throws VaultStorageException {
        if (element == null || !element.isJsonObject()) {
            throw corrupted(null);
        }

        return element.getAsJsonObject();
    }

    private static JsonPrimitive primitive(JsonObject object, String member) throws VaultStorageException {
        JsonElement value = object.get(member);

        if (value == null || !value.isJsonPrimitive()) {
            throw corrupted(null);
        }

        return value.getAsJsonPrimitive();
    }

    private static VaultStorageException corrupted(Exception cause) {
        return new VaultStorageException(VaultStorageException.Reason.CORRUPTED, cause);
    }
}
```

- [ ] **Step 4: Use it in `VaultRepository.readHeader`**

After the bounded read, replace the Gson parse and checks with:

```java
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
```
(rename the read string variable to `text`; add)

```java
    private static boolean isInstant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
```
Remove the now-unused `JsonParseException` import.

In `UserRegistryRepository.read` and `ManifestRepository.read` replace `gson.fromJson(json, EncryptedPayload.class)` with `MetadataJson.envelope(json)` (the surrounding `catch (CryptoException | JsonParseException e)` stays for the decrypted-content Gson parse).

- [ ] **Step 5: Wrapped-key size check in `AesGcmService`**

```java
    /** Ciphertext size of a wrapped 256-bit key: the key followed by the 16-byte tag. */
    public static final int WRAPPED_KEY_BYTES = CryptoConstants.KEY_BYTES + CryptoConstants.GCM_TAG_BITS / 8;
```
Replace `unwrapKey`:
```java
    public byte[] unwrapKey(
            EncryptedPayload wrappedKey,
            byte[] wrappingKey,
            byte[] aad
    ) throws CryptoException {

        // Checked before decrypting, so a malformed envelope never reaches the cipher.
        if (wrappedKey == null || decode(wrappedKey.getCiphertext()).length != WRAPPED_KEY_BYTES) {
            throw new CryptoException();
        }

        return decrypt(wrappedKey, wrappingKey, aad);
    }
```
Remove the unused `java.util.Arrays` import.

- [ ] **Step 6: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=MetadataJsonTest,VaultRepositoryTest,AesGcmServiceTest,VaultServiceTest,UserRegistryRepositoryTest,ManifestRepositoryTest,CorruptionIntegrationTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS (existing `malformedHeaderThrowsVaultStorageException` and `unsupportedFormatVersionIsRejected` still pass).

- [ ] **Step 7: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/repositories src/main/java/com/fabianrodas/security/AesGcmService.java src/test/java/com/fabianrodas/repositories src/test/java/com/fabianrodas/security/AesGcmServiceTest.java src/test/java/com/fabianrodas/services/VaultServiceTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "fix: parse unauthenticated vault metadata strictly" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** wrong JSON types, missing members, non-instant `createdAt`, wrong-size wrapped keys and malformed envelopes all fail closed; newer `formatVersion` still reports UNSUPPORTED_VERSION; build green.

---

### Task 6: Semantic validation of decrypted registries and manifests

**Purpose:** Spec 12 (UUID fields parse, mandatory fields present, negative sizes rejected) for the authenticated layer, so a malformed-but-authentic file fails as CORRUPTED (and triggers backup recovery) instead of crashing later (e.g. `UUID.fromString` in `AuthService.identity`).

**Files:**
- Modify: `src/main/java/com/fabianrodas/repositories/UserRegistryRepository.java` (`isWellFormed`)
- Modify: `src/main/java/com/fabianrodas/repositories/ManifestRepository.java` (`isWellFormed`)
- Test: `src/test/java/com/fabianrodas/repositories/UserRegistryRepositoryTest.java`, `ManifestRepositoryTest.java` (also fix `manifestWithNames()` — its FILE entry has no content and would now be rejected)

**Interfaces:** none new (private validation).

**Security:** validation only rejects; no data is changed. A blob referenced by a live entry is never touched here.

- [ ] **Step 1: Fix the test fixture that builds an invalid file entry**

In `ManifestRepositoryTest.manifestWithNames()` replace the anonymous FILE entry with one that has content:

```java
        ManifestEntry file = new ManifestEntry(
                UUID.randomUUID(), ManifestEntryKind.FILE, folder.getEntryId(),
                "budget.xlsx", "2026-09-30T00:00:00Z"
        );
        file.setContent(12_345, UUID.randomUUID(), WRAPPED, NONCE);
        manifest.getEntries().add(file);
```
with constants
```java
    private static final EncryptedPayload WRAPPED
            = new EncryptedPayload(1, "AES/GCM/NoPadding", "bm9uY2Vub25jZW5v", "d3JhcHBlZA==");
    private static final String NONCE = Base64.getEncoder().encodeToString(new byte[12]);
```

- [ ] **Step 2: Write the failing tests**

Add to `ManifestRepositoryTest`:

```java
    @Test
    void structurallyInvalidManifestsAreRejected() throws Exception {
        List<Consumer<UserManifest>> damages = List.of(
                manifest -> file(manifest).setContent(-1, UUID.randomUUID(), WRAPPED, NONCE),
                manifest -> file(manifest).setContent(1, null, WRAPPED, NONCE),
                manifest -> file(manifest).setContent(1, UUID.randomUUID(), null, NONCE),
                manifest -> file(manifest).setContent(1, UUID.randomUUID(), WRAPPED,
                        Base64.getEncoder().encodeToString(new byte[11])),
                manifest -> manifest.getEntries().add(file(manifest)),
                manifest -> folder(manifest).setParentId(UUID.randomUUID()),
                manifest -> manifest.getEntries().add(new ManifestEntry(
                        UUID.randomUUID(), ManifestEntryKind.FOLDER, null, "/", "2026-09-30T00:00:00Z")),
                manifest -> folder(manifest).setParentId(file(manifest).getEntryId()),
                manifest -> {
                    ManifestEntry a = new ManifestEntry(UUID.randomUUID(), ManifestEntryKind.FOLDER,
                            null, "a", "2026-09-30T00:00:00Z");
                    ManifestEntry b = new ManifestEntry(UUID.randomUUID(), ManifestEntryKind.FOLDER,
                            a.getEntryId(), "b", "2026-09-30T00:00:00Z");
                    a.setParentId(b.getEntryId());
                    manifest.getEntries().addAll(List.of(a, b));
                }
        );

        for (int i = 0; i < damages.size(); i++) {
            UUID freshId = UUID.randomUUID();   // no backups exist for this id, so nothing can be recovered
            UserManifest manifest = manifestWithNames();
            damages.get(i).accept(manifest);
            repository.save(manifest, freshId, userMasterKey);
            int damage = i;

            VaultStorageException error = assertThrows(
                    VaultStorageException.class,
                    () -> repository.load(userId, freshId, userMasterKey),
                    "damage " + damage
            );
            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason(), "damage " + damage);
        }
    }

    private static ManifestEntry file(UserManifest manifest) {
        return manifest.getEntries().stream()
                .filter(entry -> entry.getKind() == ManifestEntryKind.FILE).findFirst().orElseThrow();
    }

    private static ManifestEntry folder(UserManifest manifest) {
        return manifest.getEntries().stream()
                .filter(entry -> entry.getKind() == ManifestEntryKind.FOLDER && entry.getParentId() != null)
                .findFirst().orElseThrow();
    }
```
(imports `java.util.function.Consumer`, `com.fabianrodas.models.EncryptedPayload`).

Add to `UserRegistryRepositoryTest`:

```java
    @Test
    void structurallyInvalidRegistriesAreRejected() throws Exception {
        UserRecord valid = record();
        List<List<UserRecord>> registries = List.of(
                List.of(withIds("not-a-uuid", valid.getManifestId(), "exampleuser")),
                List.of(withIds(USER_ID, "not-a-uuid", "exampleuser")),
                List.of(withIds(USER_ID, valid.getManifestId(), null)),
                List.of(new UserRecord(USER_ID, "Example Person", "ExampleUser", "exampleuser",
                        "2026-09-30T17:05:00Z", valid.getManifestId(), valid.getUserKdf(), null)),
                List.of(valid, withIds("9a1b2c3d-4e5f-4061-8273-a4b5c6d7e8f9",
                        "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f0", "exampleuser"))
        );
        Path backups = root.resolve(".encryptdrive").resolve("backups");

        for (List<UserRecord> users : registries) {
            repository.save(vault, new UserRegistry(1, new ArrayList<>(users)));
            deleteBackups(backups);

            VaultStorageException error = assertThrows(
                    VaultStorageException.class, () -> repository.load(vault), users.toString()
            );
            assertEquals(VaultStorageException.Reason.CORRUPTED, error.getReason());
        }
    }

    private static UserRecord withIds(String userId, String manifestId, String normalizedUsername) {
        UserRecord valid = record();
        return new UserRecord(userId, valid.getFullName(), valid.getUsername(), normalizedUsername,
                valid.getCreatedAt(), manifestId, valid.getUserKdf(), valid.getWrappedUserMasterKey());
    }

    private static void deleteBackups(Path backups) throws IOException {
        if (Files.isDirectory(backups)) {
            try (Stream<Path> files = Files.list(backups)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
```
(imports `java.io.IOException`, `java.util.stream.Stream`).

- [ ] **Step 3: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=ManifestRepositoryTest#structurallyInvalidManifestsAreRejected,UserRegistryRepositoryTest#structurallyInvalidRegistriesAreRejected"`
Expected: FAIL — `load` returns the damaged content instead of throwing (first damage index 0 / first registry).

- [ ] **Step 4: Implement registry validation**

In `UserRegistryRepository.read` replace the existing null/format check with `if (!isWellFormed(registry)) throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);` and add:

```java
    /** Authentic content must still be shaped the way EncryptDrive writes it. */
    private static boolean isWellFormed(UserRegistry registry) {
        if (registry == null
                || registry.getFormatVersion() != FORMAT_VERSION
                || registry.getUsers() == null) {
            return false;
        }

        Set<String> userIds = new HashSet<>();
        Set<String> usernames = new HashSet<>();
        Set<String> manifestIds = new HashSet<>();

        for (UserRecord user : registry.getUsers()) {
            if (user == null
                    || !VaultRepository.isCanonicalUuid(user.getUserId())
                    || !VaultRepository.isCanonicalUuid(user.getManifestId())
                    || user.getFullName() == null
                    || user.getUsername() == null
                    || user.getNormalizedUsername() == null
                    || user.getCreatedAt() == null
                    || user.getUserKdf() == null
                    || user.getWrappedUserMasterKey() == null
                    || !userIds.add(user.getUserId())
                    || !usernames.add(user.getNormalizedUsername())
                    || !manifestIds.add(user.getManifestId())) {
                return false;
            }
        }

        return true;
    }
```

- [ ] **Step 5: Implement manifest validation**

In `ManifestRepository.read` replace the existing check with `if (!isWellFormed(manifest, userId)) throw new VaultStorageException(VaultStorageException.Reason.CORRUPTED);` and add:

```java
    /**
     * Authentic content must still be a tree the way EncryptDrive writes it:
     * unique ids, one root, every other entry under an existing folder and
     * reachable from the root, and complete content fields on every file.
     */
    private static boolean isWellFormed(UserManifest manifest, UUID userId) {
        if (manifest == null
                || manifest.getFormatVersion() != FORMAT_VERSION
                || !userId.equals(manifest.getUserId())
                || manifest.getRootFolderId() == null
                || manifest.getEntries() == null) {
            return false;
        }

        Map<UUID, ManifestEntry> byId = new HashMap<>();

        for (ManifestEntry entry : manifest.getEntries()) {
            if (entry == null
                    || entry.getEntryId() == null
                    || entry.getKind() == null
                    || entry.getName() == null
                    || entry.getCreatedAt() == null
                    || byId.put(entry.getEntryId(), entry) != null) {
                return false;
            }
        }

        ManifestEntry root = byId.get(manifest.getRootFolderId());

        if (root == null || root.getKind() != ManifestEntryKind.FOLDER || root.getParentId() != null) {
            return false;
        }

        Map<UUID, List<ManifestEntry>> children = new HashMap<>();

        for (ManifestEntry entry : manifest.getEntries()) {
            if (entry != root) {
                ManifestEntry parent = entry.getParentId() == null ? null : byId.get(entry.getParentId());

                if (parent == null || parent.getKind() != ManifestEntryKind.FOLDER) {
                    return false;
                }

                children.computeIfAbsent(parent.getEntryId(), id -> new ArrayList<>()).add(entry);
            }

            if (entry.getKind() == ManifestEntryKind.FILE && !hasContent(entry)) {
                return false;
            }
        }

        // Entries caught in a parent cycle are never reached from the root.
        int reached = 0;
        Deque<ManifestEntry> pending = new ArrayDeque<>(List.of(root));

        while (!pending.isEmpty()) {
            reached++;
            pending.addAll(children.getOrDefault(pending.pop().getEntryId(), List.of()));
        }

        return reached == manifest.getEntries().size();
    }

    private static boolean hasContent(ManifestEntry file) {
        return file.getPlainSize() != null
                && file.getPlainSize() >= 0
                && file.getBlobId() != null
                && file.getWrappedFileKey() != null
                && decodedLength(file.getContentNonce()) == CryptoConstants.GCM_NONCE_BYTES;
    }

    private static int decodedLength(String base64) {
        try {
            return base64 == null ? -1 : Base64.getDecoder().decode(base64).length;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }
```
(imports `com.fabianrodas.models.ManifestEntry`, `com.fabianrodas.models.ManifestEntryKind`, `com.fabianrodas.security.CryptoConstants`, `java.util.*` types used).

- [ ] **Step 6: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=ManifestRepositoryTest,UserRegistryRepositoryTest,FileServiceTest,AuthServiceTest,CorruptionIntegrationTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 7: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/repositories src/test/java/com/fabianrodas/repositories docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "fix: reject structurally invalid registries and manifests" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** each listed defect loads as CORRUPTED; valid manifests/registries from every existing test still load; build green.

---

### Task 6A: Staged vault creation — a failed attempt leaves nothing behind

**Purpose:** Defect found by the audit (spec 1 "crash-safe encrypted storage semantics"). `VaultService.createVault` builds `.encryptdrive/` and `storage/` directly in the chosen folder before the registry and header exist (`VaultService.java:50`). A failure in between leaves a folder that can be neither created into (`ALREADY_EXISTS`) nor opened (`NOT_A_VAULT`). The fix is transactional creation: the vault is built completely in a staging folder that this call itself creates, then moved into place in one rename. The chosen folder is never left half-made, so **no "incomplete vault" detection or reuse exists at all**: any non-empty target is refused exactly as today and is never modified.

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/VaultService.java` (class becomes non-final for the test seam; `createVault`, new `moveIntoPlace`, `deleteStaging`)
- Test: `src/test/java/com/fabianrodas/services/VaultServiceTest.java`

**Interfaces:** produces package-private `void VaultService.moveIntoPlace(Path staging, Path target) throws IOException` (test seam only).

**Security:** the only folder ever deleted on failure is the staging folder whose random name this call generated and created (`createDirectory` fails if it already exists, so it is provably ours). Nothing inside the user's chosen folder is read, changed or deleted before the final rename; a non-empty target is refused before staging starts. The vault lock is taken after the rename, on the finished vault.

- [ ] **Step 1: Write the failing tests** (add to `VaultServiceTest`; adapt `service`, `tempDir`, `PASSWORD`, `assertReason` to the names the class already uses)

```java
    @Test
    void aFailedCreationLeavesNothingBehindAndCanBeRetried() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("staged"));
        Path root = parent.resolve("retry");
        VaultService failing = new VaultService() {
            @Override
            void moveIntoPlace(Path staging, Path target) throws IOException {
                throw new IOException("simulated failure before the vault is published");
            }
        };

        assertReason(VaultException.Reason.STORAGE, () -> failing.createVault(root, PASSWORD.toCharArray()));

        assertFalse(Files.exists(root));
        assertEquals(List.of(), names(parent));          // no staging folder left
        assertFalse(VaultSessionService.isOpen());

        service.createVault(root, PASSWORD.toCharArray());
        service.closeVault();
        service.unlockVault(root, PASSWORD.toCharArray());
    }

    @Test
    void anIncompleteLookingFolderWithAUserFileIsRefusedAndUntouched() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("staged"));
        Path root = parent.resolve("looks-incomplete");
        Files.createDirectories(root.resolve(".encryptdrive").resolve("manifests"));
        Files.createDirectories(root.resolve("storage").resolve("blobs"));
        Path userFile = Files.writeString(root.resolve("my notes.txt"), "do not touch");
        FileTime modified = Files.getLastModifiedTime(userFile);
        List<String> before = tree(root);

        assertReason(VaultException.Reason.ALREADY_EXISTS, () -> service.createVault(root, PASSWORD.toCharArray()));

        assertEquals("do not touch", Files.readString(userFile));
        assertEquals(modified, Files.getLastModifiedTime(userFile));
        assertEquals(before, tree(root));
        assertEquals(List.of("looks-incomplete"), names(parent));
    }

    @Test
    void aBareSkeletonIsNotReusedEither() throws Exception {
        Path root = tempDir.resolve("staged").resolve("skeleton");
        Files.createDirectories(root.resolve(".encryptdrive").resolve("manifests"));
        List<String> before = tree(root);

        assertReason(VaultException.Reason.ALREADY_EXISTS, () -> service.createVault(root, PASSWORD.toCharArray()));

        assertEquals(before, tree(root));
    }

    @Test
    void anEmptyTargetFolderAndAStaleStagingFolderDoNotBlockCreation() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("staged"));
        Path root = Files.createDirectories(parent.resolve("fresh"));
        Path stale = Files.createDirectories(parent.resolve(".fresh.creating-00000000-0000-0000-0000-000000000000"));
        Files.writeString(stale.resolve("left by a crash"), "x");

        service.createVault(root, PASSWORD.toCharArray());
        service.closeVault();

        service.unlockVault(root, PASSWORD.toCharArray());
        assertTrue(Files.exists(stale.resolve("left by a crash")));   // never ours to delete
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
```
(imports as needed: `java.io.IOException`, `java.nio.file.attribute.FileTime`, `java.util.List`, `java.util.stream.Stream`, `assertFalse`, `assertTrue`.)

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=VaultServiceTest"`
Expected: compile error — `VaultService` is final and has no `moveIntoPlace`. After making the class non-final with an empty `moveIntoPlace` stub that nothing calls: `aFailedCreationLeavesNothingBehindAndCanBeRetried` FAILS (creation succeeds because the stub is never used). The three refusal/empty-target tests pass already; they pin behaviour that must survive the change.

- [ ] **Step 3: Implement** — `public class VaultService` (drop `final`), and replace `createVault`:

```java
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

        try {
            Files.createDirectories(parent);
            // Fails if the name exists, so everything under it was made by this call.
            Files.createDirectory(staging);
            staged = true;
            createStructure(staging);

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
            VaultContext context = new VaultContext(
                    vaultRoot, vaultId, VaultRepository.FORMAT_VERSION, createdAt,
                    SensitiveBytes.copyOf(registryKey), lockService.acquire(vaultRoot)
            );
            VaultSessionService.open(context);
            return context;

        } catch (IOException | VaultStorageException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        } finally {
            Arrays.fill(registryKey, (byte) 0);

            if (staged && !published) {
                deleteStaging(staging);
            }
        }
    }

    /** Publishes the finished vault: one rename onto a missing or empty folder. */
    void moveIntoPlace(Path staging, Path target) throws IOException {
        // Only ever an empty folder (requireEmptyOrMissing); delete refuses anything else.
        Files.deleteIfExists(target);
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
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
```
(imports `java.nio.file.StandardCopyOption`, `java.util.Comparator`). `requireEmptyOrMissing` and `createStructure` are unchanged. The `finally` wipes the local key copy on every path (the contexts hold their own copies) and removes the staging folder unless the vault was published.

- [ ] **Step 4: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=VaultServiceTest,VaultSelectionControllerTest,CorruptionIntegrationTest,UiFlowTest,UiLayoutTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS (every existing test creates its vault through this path).

- [ ] **Step 5: Diff review** — confirm the only recursive delete in `VaultService` targets `staging`, and that no code path writes inside `vaultRoot` before `moveIntoPlace`.

- [ ] **Step 6: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/services/VaultService.java src/test/java/com/fabianrodas/services/VaultServiceTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "fix: create vaults in a staging folder so a failed attempt leaves nothing behind" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** a failed creation leaves the target missing (or still empty), no staging folder, no open session, and can be retried; any non-empty target — including one that looks like an incomplete vault, with or without user files — is refused and byte-for-byte untouched; a stale staging folder from a crash is neither reused nor deleted; build green.

---

### Task 7: 12-character account passwords; pre-release accounts still log in

**Purpose:** Spec 7.1, 21.2 (new registration and Change Password ≥ 12; not trimmed; old shorter passwords still log in — dangerous failure mode #4). UI texts derive from the constants.

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/AuthService.java` (`MIN_PASSWORD_LENGTH = 12`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/RegisterController.java`, `ProfileController.java`, `VaultSelectionController.java`, `VaultSettingsController.java` (messages/prompts from constants)
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/register.fxml`, `profile.fxml`, `vault-selection.fxml`, `vault-settings.fxml` (remove literal "At least N characters" prompts)
- Test: `src/test/java/com/fabianrodas/services/AuthServiceTest.java`

**Interfaces:** consumes `AuthService.MIN_PASSWORD_LENGTH`, `VaultService.MIN_PASSWORD_LENGTH`.

**Security:** login performs no length check (unchanged); only new secrets are constrained.

- [ ] **Step 1: Write the failing tests** (add to `AuthServiceTest`)

```java
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
```
(imports: `Argon2KeyDeriver`, `AesGcmService`, `Aad`, `KdfConfig`, `EncryptedPayload`, `UserRecord`, `UserRegistry`, `UserRegistryRepository`, `java.security.SecureRandom`, `java.time.Instant`, `java.util.Locale`, `java.util.UUID`).

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=AuthServiceTest"`
Expected: `newAccountsNeedTwelveCharacterPasswords`, `changedPasswordsNeedTwelveCharacters` and the short-change assertion in `preReleaseShortPasswordStillLogsIn` FAIL (11 characters accepted). `passwordsAreNotTrimmed` passes already (it pins behaviour).

- [ ] **Step 3: Raise the minimum**

`AuthService`: `public static final int MIN_PASSWORD_LENGTH = 12;`

- [ ] **Step 4: Derive UI texts from constants**

`RegisterController`:
- `initialize`: after the bindings add
  ```java
        String hint = "At least " + AuthService.MIN_PASSWORD_LENGTH + " characters";
        passwordField.setPromptText(hint);
        visiblePasswordField.setPromptText(hint);
  ```
- `register()`: replace the literal checks with
  ```java
        if (username.length() < AuthService.MIN_USERNAME_LENGTH) {
            showError("Username must contain at least " + AuthService.MIN_USERNAME_LENGTH + " characters.");
            return;
        }

        if (password.length() < AuthService.MIN_PASSWORD_LENGTH) {
            showError("Password must contain at least " + AuthService.MIN_PASSWORD_LENGTH + " characters.");
            return;
        }
  ```
- Delete the unused private `showSuccess` method (dead code; proven by `grep -n "showSuccess" RegisterController.java` showing only its declaration).

`ProfileController`:
- `initialize`: first line `newPasswordField.setPromptText("At least " + AuthService.MIN_PASSWORD_LENGTH + " characters");`
- replace the length message with `"Your new password must contain at least " + AuthService.MIN_PASSWORD_LENGTH + " characters."`

`VaultSelectionController`: `createPasswordField.setPromptText("At least " + VaultService.MIN_PASSWORD_LENGTH + " characters");` in `initialize`; both "12 characters" messages become `"The vault password must contain at least " + VaultService.MIN_PASSWORD_LENGTH + " characters."`.

`VaultSettingsController`: `newPasswordField.setPromptText(...)` with `VaultService.MIN_PASSWORD_LENGTH` in `initialize` (before the open-vault check); message likewise.

FXML: delete the `promptText="At least 8 characters"` attributes in `register.fxml` (2) and `profile.fxml` (1), and `promptText="At least 12 characters"` in `vault-selection.fxml` and `vault-settings.fxml`.

- [ ] **Step 5: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=AuthServiceTest,UiLayoutTest,UiFlowTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 6: Check no stale literal remains**

Run: `git grep -nE "at least (8|12) characters|At least (8|12) characters" -- src`
Expected: no output.

- [ ] **Step 7: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/services/AuthService.java src/main/java/com/fabianrodas/encryptdrive/RegisterController.java src/main/java/com/fabianrodas/encryptdrive/ProfileController.java src/main/java/com/fabianrodas/encryptdrive/VaultSelectionController.java src/main/java/com/fabianrodas/encryptdrive/VaultSettingsController.java src/main/resources/com/fabianrodas/encryptdrive/register.fxml src/main/resources/com/fabianrodas/encryptdrive/profile.fxml src/main/resources/com/fabianrodas/encryptdrive/vault-selection.fxml src/main/resources/com/fabianrodas/encryptdrive/vault-settings.fxml src/test/java/com/fabianrodas/services/AuthServiceTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "feat: require 12-character account passwords" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** 11 characters rejected for registration and change; 12 accepted; trailing spaces significant; a pre-release 8-character account logs in and must choose ≥ 12 on change; no hard-coded length texts; build green.

---

### Task 8: Registry backups reseeded on account password change

**Purpose:** Spec 8.1, 10.1, 21.2: after a password change, the active registry and every retained backup accept only the new password; files still export.

**Files:**
- Modify: `src/main/java/com/fabianrodas/repositories/AtomicFileWriter.java` (class becomes non-final: failure-injection seam)
- Modify: `src/main/java/com/fabianrodas/repositories/BackupRotator.java` (`reseed`; constructor taking the writer)
- Modify: `src/main/java/com/fabianrodas/repositories/UserRegistryRepository.java` (`saveCheckpoint`; public constructor taking the writer)
- Modify: `src/main/java/com/fabianrodas/services/AuthService.java` (`changePassword` uses the checkpoint; package-private constructor taking the repository)
- Create: `src/test/java/com/fabianrodas/repositories/FailingWriter.java` (public test helper)
- Test: `src/test/java/com/fabianrodas/repositories/BackupRotatorTest.java`, `UserRegistryRepositoryTest.java`, `src/test/java/com/fabianrodas/services/AuthServiceTest.java`, `FileServiceTest.java`

**Interfaces:**
- Produces: `BackupRotator.reseed(String name, byte[] content, Path backupDirectory, int generations)`; `UserRegistryRepository.saveCheckpoint(VaultContext, UserRegistry)`; `public UserRegistryRepository(AtomicFileWriter)`; package-private `BackupRotator(AtomicFileWriter)` and `AuthService(VaultContext, UserRegistryRepository)`; test helper `FailingWriter`.

**Security:** order is **backups first, `users.enc` last** — writing `users.enc` is the commit point. If the reseed fails or the process dies before the last write, the live registry still holds the old password and the user saw no success, so no retained generation holds a credential the live file does not also hold. The opposite order would leave old-password envelopes in the backups indefinitely after a crash (nothing would ever retry the reseed) and would report failure for a change that had already taken effect. (The manifest checkpoint in T9 keeps the order spec 9.2 prescribes; its journal makes it self-healing.) Copies kept outside EncryptDrive — sync-provider version history, external backups — cannot be purged; documented in T24.

- [ ] **Step 1: Write the failing tests**

`BackupRotatorTest`:

```java
    @Test
    void reseedReplacesEveryGenerationWithTheGivenContent() throws IOException {
        Path backups = dir.resolve("backups");
        Files.createDirectories(backups);
        Files.writeString(backups.resolve("users.enc.1"), "old 1");

        new BackupRotator().reseed("users.enc", "new state".getBytes(UTF_8), backups, 3);

        for (int generation = 1; generation <= 3; generation++) {
            assertEquals("new state", Files.readString(backups.resolve("users.enc." + generation)));
        }
    }
```
(use the test class's existing temp-dir field name; static-import `java.nio.charset.StandardCharsets.UTF_8` if the class does not already).

`UserRegistryRepositoryTest`:

```java
    @Test
    void checkpointLeavesNoOlderRegistryInTheBackups() throws Exception {
        repository.save(vault, new UserRegistry(1, new ArrayList<>()));
        repository.save(vault, registryWith(record()));
        repository.save(vault, new UserRegistry(1, new ArrayList<>()));

        repository.saveCheckpoint(vault, registryWith(record()));

        byte[] current = Files.readAllBytes(usersFile());
        for (int generation = 1; generation <= 3; generation++) {
            assertArrayEquals(current, Files.readAllBytes(
                    root.resolve(".encryptdrive/backups/users.enc." + generation)));
        }
    }
```

`AuthServiceTest`:

```java
    @Test
    void noRetainedRegistryGenerationAcceptsTheOldPassword() throws Exception {
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());
        auth.register("Other User", "otheruser", "other password".toCharArray());
        auth.register("Third User", "thirduser", "third password".toCharArray());

        auth.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray());

        vaultService.closeVault();
        Path meta = tempDir.resolve("vault").resolve(".encryptdrive");
        byte[] current = Files.readAllBytes(meta.resolve("users.enc"));

        for (int generation = 1; generation <= 3; generation++) {
            Path backup = meta.resolve("backups").resolve("users.enc." + generation);
            Files.copy(backup, meta.resolve("users.enc"), StandardCopyOption.REPLACE_EXISTING);

            VaultContext reopened = vaultService.unlockVault(
                    tempDir.resolve("vault"), "correct vault password".toCharArray()
            );
            AuthService restored = new AuthService(reopened);
            int g = generation;

            assertReason(AuthException.Reason.INVALID_CREDENTIALS,
                    () -> restored.login("ExampleUser", PASSWORD.toCharArray()));
            assertEquals(user, restored.login("ExampleUser", NEW_PASSWORD.toCharArray()).identity(), "generation " + g);
            restored.login("otheruser", "other password".toCharArray()).userMasterKey().close();
            vaultService.closeVault();
        }

        Files.write(meta.resolve("users.enc"), current);
        vault = vaultService.unlockVault(tempDir.resolve("vault"), "correct vault password".toCharArray());
    }
```
(imports `java.nio.file.StandardCopyOption`; `vault` is reassigned so `@AfterEach closeVault` still works).

```java
    @Test
    void failedBackupReseedLeavesTheLivePasswordUnchanged() throws Exception {
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());
        Path backup2 = tempDir.resolve("vault").resolve(".encryptdrive").resolve("backups").resolve("users.enc.2");
        // A non-empty directory in place of generation 2 makes replacing it fail.
        Files.deleteIfExists(backup2);
        Files.createDirectories(backup2.resolve("blocker"));

        assertReason(AuthException.Reason.STORAGE,
                () -> auth.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()));

        // users.enc is written last, so the reported failure means nothing changed for the user.
        assertEquals(user, auth.login("ExampleUser", PASSWORD.toCharArray()).identity());
        assertReason(AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.login("ExampleUser", NEW_PASSWORD.toCharArray()));
    }
```

**Failure injection (every write of the checkpoint, independently).** `src/test/java/com/fabianrodas/repositories/FailingWriter.java`:

```java
package com.fabianrodas.repositories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An AtomicFileWriter whose n-th write from now fails. The registry checkpoint
 * performs four writes in order: backup 1, backup 2, backup 3, users.enc.
 */
public final class FailingWriter extends AtomicFileWriter {

    private int countdown;
    private boolean leavePartialTemp;

    /** The n-th write from now throws before anything is written. */
    public void failBeforeWrite(int nth) {
        countdown = nth;
        leavePartialTemp = false;
    }

    /** The n-th write from now fails part-way, leaving a stray temp file like an interrupted write. */
    public void failDuringWrite(int nth) {
        countdown = nth;
        leavePartialTemp = true;
    }

    @Override
    public void write(Path destination, byte[] bytes) throws IOException {
        if (countdown > 0 && --countdown == 0) {
            if (leavePartialTemp) {
                Files.write(destination.resolveSibling(destination.getFileName() + ".123.tmp"), new byte[]{1});
            }

            throw new IOException("simulated write failure for " + destination.getFileName());
        }

        super.write(destination, bytes);
    }
}
```

Add to `AuthServiceTest` (the class's `vault`, `tempDir`, `PASSWORD`, `NEW_PASSWORD`, `assertReason` as already used above):

```java
    /** Writes 1-3 are the backup generations, write 4 is the live users.enc. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void failureWhileReseedingAnyBackupGenerationChangesNothingForTheUser(int failingWrite) throws Exception {
        FailingWriter writer = new FailingWriter();
        AuthService failing = new AuthService(vault, new UserRegistryRepository(writer));
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());
        byte[] liveBefore = Files.readAllBytes(usersFile());
        writer.failBeforeWrite(failingWrite);

        assertReason(AuthException.Reason.STORAGE,
                () -> failing.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()));

        assertArrayEquals(liveBefore, Files.readAllBytes(usersFile()));
        assertEquals(user, auth.login("ExampleUser", PASSWORD.toCharArray()).identity());
        assertReason(AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.login("ExampleUser", NEW_PASSWORD.toCharArray()));
    }

    @Test
    void failureImmediatelyBeforeTheLiveCheckpointKeepsTheOldPassword() throws Exception {
        FailingWriter writer = new FailingWriter();
        AuthService failing = new AuthService(vault, new UserRegistryRepository(writer));
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());
        byte[] liveBefore = Files.readAllBytes(usersFile());
        writer.failBeforeWrite(4);          // all three backups are already the new state

        assertReason(AuthException.Reason.STORAGE,
                () -> failing.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()));

        assertArrayEquals(liveBefore, Files.readAllBytes(usersFile()));
        assertEquals(user, auth.login("ExampleUser", PASSWORD.toCharArray()).identity());
        assertReason(AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.login("ExampleUser", NEW_PASSWORD.toCharArray()));
    }

    @Test
    void failureOfTheFinalLiveWriteIsReportedAndKeepsTheOldPassword() throws Exception {
        FailingWriter writer = new FailingWriter();
        AuthService failing = new AuthService(vault, new UserRegistryRepository(writer));
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());
        byte[] liveBefore = Files.readAllBytes(usersFile());
        writer.failDuringWrite(4);          // the users.enc write starts and fails

        assertReason(AuthException.Reason.STORAGE,
                () -> failing.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()));

        assertArrayEquals(liveBefore, Files.readAllBytes(usersFile()));
        assertEquals(user, auth.login("ExampleUser", PASSWORD.toCharArray()).identity());

        // The interrupted change can simply be repeated.
        auth.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray());
        assertEquals(user, auth.login("ExampleUser", NEW_PASSWORD.toCharArray()).identity());
        assertReason(AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.login("ExampleUser", PASSWORD.toCharArray()));
    }

    /*
     * The partially reseeded state, proven rather than assumed: backup 1 holds
     * the new password, backups 2-3 and users.enc the old one. While users.enc
     * is intact the old password is the valid one. If users.enc is then
     * damaged, recovery restores the newest authentic generation (backup 1),
     * so from that moment only the new password works - the password the user
     * typed twice - and the recovery notice is raised. No generation is ever
     * restored that the user did not create.
     */
    @Test
    void recoveryFromAPartiallyReseededStateRestoresTheNewestGeneration() throws Exception {
        FailingWriter writer = new FailingWriter();
        AuthService failing = new AuthService(vault, new UserRegistryRepository(writer));
        UserSessionIdentity user = auth.register("Example User", "ExampleUser", PASSWORD.toCharArray());
        auth.register("Other User", "otheruser", "other password".toCharArray());
        writer.failBeforeWrite(2);          // backup 1 reseeded, backups 2 and 3 not

        assertReason(AuthException.Reason.STORAGE,
                () -> failing.changePassword(user.userId(), PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()));
        assertEquals(user, auth.login("ExampleUser", PASSWORD.toCharArray()).identity());

        RecoveryService.takeRecoveryNotice();
        Files.writeString(usersFile(), "{}");                 // users.enc damaged afterwards

        assertEquals(user, auth.login("ExampleUser", NEW_PASSWORD.toCharArray()).identity());
        assertTrue(RecoveryService.takeRecoveryNotice());
        assertReason(AuthException.Reason.INVALID_CREDENTIALS,
                () -> auth.login("ExampleUser", PASSWORD.toCharArray()));
        auth.login("otheruser", "other password".toCharArray()).userMasterKey().close();
    }

    private Path usersFile() {
        return tempDir.resolve("vault").resolve(".encryptdrive").resolve("users.enc");
    }
```
(imports `com.fabianrodas.repositories.FailingWriter`, `com.fabianrodas.repositories.UserRegistryRepository`, `org.junit.jupiter.params.ParameterizedTest`, `org.junit.jupiter.params.provider.ValueSource`, `assertArrayEquals`, `assertTrue`. In every one of these tests the failed call must throw: `assertReason` fails the test if `changePassword` returns normally, so success is never reported for a failed change.)

`FileServiceTest`:

```java
    @Test
    void filesStillExportAfterAPasswordChange() throws Exception {
        byte[] content = random(50_000);
        FileService files = files(alice);
        ManifestEntry entry = files.importFile(source("keep.bin", content), files.rootFolderId());
        AuthService auth = new AuthService(vault);

        auth.changePassword(alice.identity().userId(), "alice password".toCharArray(), "alice new password".toCharArray());

        UserLoginResult login = auth.login("alice", "alice new password".toCharArray());
        try (SensitiveBytes key = login.userMasterKey()) {
            FileService again = new FileService(vault, login.identity(), () -> SensitiveBytes.copyOf(key.copy()));
            Path out = tempDir.resolve("keep-out.bin");
            again.exportEntry(entry.getEntryId(), out);
            assertArrayEquals(content, Files.readAllBytes(out));
        }
    }
```

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=BackupRotatorTest,UserRegistryRepositoryTest#checkpointLeavesNoOlderRegistryInTheBackups,AuthServiceTest#noRetainedRegistryGenerationAcceptsTheOldPassword"`
Expected: compile errors for `reseed`/`saveCheckpoint`; after adding empty stubs, the AuthService test fails at generation 1 because the old password still unwraps.

- [ ] **Step 3: Implement**

`BackupRotator`:

```java
    /**
     * Replaces every backup generation of the file called {@code name} with
     * {@code content}, so no older state stays recoverable after a
     * security-sensitive change.
     */
    public void reseed(String name, byte[] content, Path backupDirectory, int generations)
            throws IOException {

        Files.createDirectories(backupDirectory);

        for (int generation = 1; generation <= generations; generation++) {
            writer.write(backupDirectory.resolve(name + "." + generation), content);
        }
    }
```

`UserRegistryRepository`:

```java
    /**
     * Saves without keeping older states: users.enc and every backup become
     * this registry. Used after a password change so no backup still holds a
     * key envelope wrapped under the old password. The backups are replaced
     * first and users.enc last: until that last write the change has not
     * happened, and afterwards no older generation is left.
     */
    public void saveCheckpoint(VaultContext vault, UserRegistry registry) throws VaultStorageException {
        byte[] envelope = seal(vault, registry);
        Path usersFile = VaultRepository.usersFile(vault.root());

        try {
            rotator.reseed(VaultRepository.USERS_FILE, envelope,
                    VaultRepository.backupsDir(vault.root()), BACKUP_GENERATIONS);
            writer.write(usersFile, envelope);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }
```

Seams for the failure tests (no behaviour change):
- `AtomicFileWriter`: `public class AtomicFileWriter` (drop `final`).
- `BackupRotator`: replace the field initializer with `private final AtomicFileWriter writer;`, add `public BackupRotator() { this(new AtomicFileWriter()); }` and `BackupRotator(AtomicFileWriter writer) { this.writer = writer; }`.
- `UserRegistryRepository`: `private final AtomicFileWriter writer; private final BackupRotator rotator;`, `public UserRegistryRepository() { this(new AtomicFileWriter()); }` and
  ```java
    /** For tests that make individual writes fail. */
    public UserRegistryRepository(AtomicFileWriter writer) {
        this.writer = writer;
        this.rotator = new BackupRotator(writer);
    }
  ```
- `AuthService`: `private final UserRegistryRepository registryRepository;`, the public constructor becomes `this(vault, new UserRegistryRepository());` and
  ```java
    AuthService(VaultContext vault, UserRegistryRepository registryRepository) {
        this.vault = vault;
        this.registryRepository = registryRepository;
        this.manifestRepository = new ManifestRepository(vault);
    }
  ```

`AuthService.changePassword`: replace `saveRegistry(registry);` with `saveRegistryCheckpoint(registry);` and add

```java
    private void saveRegistryCheckpoint(UserRegistry registry) throws AuthException {
        try {
            registryRepository.saveCheckpoint(vault, registry);
        } catch (VaultStorageException e) {
            throw storageFailure(e);
        }
    }
```
Update the Javadoc of `changePassword`: "…and replaces every registry backup, so the old password opens no retained generation."

- [ ] **Step 4: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=BackupRotatorTest,UserRegistryRepositoryTest,AuthServiceTest,FileServiceTest,CorruptionIntegrationTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS (`changePasswordRequiresTheCurrentPassword` still proves a failed change writes nothing).

- [ ] **Step 5: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/repositories/AtomicFileWriter.java src/main/java/com/fabianrodas/repositories/BackupRotator.java src/main/java/com/fabianrodas/repositories/UserRegistryRepository.java src/main/java/com/fabianrodas/services/AuthService.java src/test/java/com/fabianrodas/repositories/FailingWriter.java src/test/java/com/fabianrodas/repositories/BackupRotatorTest.java src/test/java/com/fabianrodas/repositories/UserRegistryRepositoryTest.java src/test/java/com/fabianrodas/services/AuthServiceTest.java src/test/java/com/fabianrodas/services/FileServiceTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "fix: purge old-password key envelopes from registry backups" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** every retained registry generation rejects the old password and accepts the new one; a failure at each of the four checkpoint writes (each backup generation, immediately before the live write, the live write itself) is reported as failure, never as success, and leaves `users.enc` byte-identical with the old password valid; recovery from the partially reseeded state is proven by test; other accounts unaffected; files export after the change; build green.

---

### Task 9: Encrypted pending-deletion journal and manifest checkpoint save

**Purpose:** Spec 9.1 (journal inside the encrypted manifest) and the storage primitive for 9.2 steps 4–5 / 8–9.

**Files:**
- Create: `src/main/java/com/fabianrodas/models/PendingDeletion.java`
- Modify: `src/main/java/com/fabianrodas/models/UserManifest.java`
- Modify: `src/main/java/com/fabianrodas/repositories/ManifestRepository.java` (`saveCheckpoint`; validate journal entries)
- Test: `src/test/java/com/fabianrodas/repositories/ManifestRepositoryTest.java`

**Interfaces:**
- Produces: `record PendingDeletion(UUID blobId, String queuedAt)`; `UserManifest.getPendingDeletions()`; `ManifestRepository.saveCheckpoint(UserManifest, UUID, byte[])`.

**Security:** the journal lives only inside the AES-GCM manifest. Format version stays 1: older manifests simply lack the member (Gson leaves it null → empty list).

- [ ] **Step 1: Write the failing tests** (add to `ManifestRepositoryTest`)

```java
    @Test
    void pendingDeletionsRoundTripInsideTheEncryptedManifest() throws Exception {
        UserManifest manifest = ManifestService.newManifest(userId);
        UUID blobId = UUID.randomUUID();
        manifest.getPendingDeletions().add(new PendingDeletion(blobId, "2026-10-01T10:00:00Z"));

        repository.save(manifest, manifestId, userMasterKey);

        assertEquals(
                List.of(new PendingDeletion(blobId, "2026-10-01T10:00:00Z")),
                repository.load(userId, manifestId, userMasterKey).getPendingDeletions()
        );
        assertFalse(Files.readString(manifestFile(), UTF_8).contains(blobId.toString()));
    }

    @Test
    void manifestWithoutAJournalLoadsWithAnEmptyOne() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);

        assertEquals(List.of(), repository.load(userId, manifestId, userMasterKey).getPendingDeletions());
    }

    @Test
    void journalEntryWithoutABlobIdIsCorrupted() throws Exception {
        UserManifest manifest = ManifestService.newManifest(userId);
        manifest.getPendingDeletions().add(new PendingDeletion(null, "2026-10-01T10:00:00Z"));
        repository.save(manifest, manifestId, userMasterKey);

        assertCorrupted(() -> repository.load(userId, manifestId, userMasterKey));
    }

    @Test
    void checkpointLeavesNoOlderManifestInTheBackups() throws Exception {
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        repository.save(manifestWithNames(), manifestId, userMasterKey);
        repository.save(ManifestService.newManifest(userId), manifestId, userMasterKey);

        repository.saveCheckpoint(manifestWithNames(), manifestId, userMasterKey);

        byte[] current = Files.readAllBytes(manifestFile());
        for (int generation = 1; generation <= 3; generation++) {
            assertArrayEquals(current, Files.readAllBytes(root.resolve(
                    ".encryptdrive/backups/manifests/" + manifestId + ".enc." + generation)));
        }
    }
```
(import `com.fabianrodas.models.PendingDeletion`).

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=ManifestRepositoryTest"`
Expected: compile errors (`PendingDeletion`, `getPendingDeletions`, `saveCheckpoint`).

- [ ] **Step 3: Implement the model**

`src/main/java/com/fabianrodas/models/PendingDeletion.java`:

```java
package com.fabianrodas.models;

import java.util.UUID;

/**
 * A blob whose manifest entry is already gone but which may still be on
 * disk. Stored only inside the encrypted manifest.
 */
public record PendingDeletion(UUID blobId, String queuedAt) {
}
```

`UserManifest`: add `private List<PendingDeletion> pendingDeletions;` and

```java
    /** Blobs queued for physical deletion; empty, never null. */
    public List<PendingDeletion> getPendingDeletions() {
        if (pendingDeletions == null) {
            pendingDeletions = new ArrayList<>();
        }

        return pendingDeletions;
    }
```
(import `java.util.ArrayList`).

- [ ] **Step 4: Repository changes**

`ManifestRepository.isWellFormed`: before the reachability check add

```java
        for (PendingDeletion deletion : manifest.getPendingDeletions()) {
            if (deletion == null || deletion.blobId() == null) {
                return false;
            }
        }
```

Add:

```java
    /**
     * Saves without keeping older states: the manifest and every backup
     * generation become this manifest. Permanent deletion uses it so that no
     * backup can bring back entries whose blobs are about to be destroyed.
     */
    public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] userMasterKey)
            throws VaultStorageException {

        byte[] envelope = seal(manifest, userMasterKey);
        Path manifestFile = manifestFile(manifestId);

        try {
            // Spec 9.2 steps 4-5: the manifest first, then every backup.
            writer.write(manifestFile, envelope);
            rotator.reseed(manifestFile.getFileName().toString(), envelope,
                    manifestBackupsDir(), BACKUP_GENERATIONS);
        } catch (IOException e) {
            throw new VaultStorageException(VaultStorageException.Reason.IO, e);
        }
    }
```

- [ ] **Step 5: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=ManifestRepositoryTest,FileServiceTest,CorruptionIntegrationTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 6: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/models/PendingDeletion.java src/main/java/com/fabianrodas/models/UserManifest.java src/main/java/com/fabianrodas/repositories/ManifestRepository.java src/test/java/com/fabianrodas/repositories/ManifestRepositoryTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "feat: add an encrypted pending-deletion journal to manifests" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** journal round-trips encrypted; absent journal = empty; malformed journal entry = CORRUPTED; checkpoint makes all three backups equal to the current file; build green.

---

### Task 10: Crash-safe permanent deletion

**Purpose:** Spec 9.2–9.4, 10.2, 21.1 — the release blocker. Entries leave the manifest and every backup before any blob is deleted; blobs are journaled and purged; missing blobs count as deleted; failures leave retryable encrypted state; nothing recoverable references deleted ciphertext.

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/FileService.java` (replace `permanentlyDelete(UUID)`; add `permanentlyDelete(List<UUID>)`, `resumePendingDeletions()`, private `deletePermanently`, `purge`, `queueBlobs`, `checkpoint`, `deleteBlob`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/TrashController.java` (list API, cleanup notice)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/Formats.java` (`CLEANUP_PENDING`)
- Create: `src/test/java/com/fabianrodas/services/TestVault.java`
- Create: `src/test/java/com/fabianrodas/services/PermanentDeleteTest.java`
- Modify: `src/test/java/com/fabianrodas/services/FileServiceTest.java` (two call sites → `List.of(...)`)

**Interfaces:**
- Consumes: `ManifestRepository.saveCheckpoint` (T9), `UserManifest.getPendingDeletions()` (T9).
- Produces: `int FileService.permanentlyDelete(List<UUID>)`, `int FileService.resumePendingDeletions()`, private `int deletePermanently(UserManifest, Collection<UUID>, byte[])` (reused by T18), `Formats.CLEANUP_PENDING`, test helper `TestVault`.

**Security:** may orphan ciphertext; must never leave live or recoverable metadata pointing at deleted ciphertext; never deletes a blob a live entry still references.

- [ ] **Step 1: Create the test fixture `TestVault`**

```java
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
        return new FileService(vault, account.identity(), account.key(),
                manifests, new BlobRepository(), new StreamingFileCryptoService());
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
```

- [ ] **Step 2: Write the failing boundary tests**

`src/test/java/com/fabianrodas/services/PermanentDeleteTest.java`:

```java
package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.PendingDeletion;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/*
 * Spec 9: entries leave the manifest and every backup before a blob is
 * deleted. Each test stops the sequence at one boundary.
 */
class PermanentDeleteTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account alice;
    private FileService files;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.register("alice");
        files = vault.files(alice);
        RecoveryService.takeRecoveryNotice();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void entriesLeaveManifestAndBackupsBeforeAnyBlobIsRemoved() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        Path blob = vault.blob(doomed);
        List<Boolean> blobPresentAtCheckpoint = new ArrayList<>();
        ManifestRepository watching = new ManifestRepository(vault.vault) {
            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                blobPresentAtCheckpoint.add(Files.exists(blob));
                super.saveCheckpoint(manifest, manifestId, key);
            }
        };

        assertEquals(0, vault.files(alice, watching).permanentlyDelete(List.of(doomed.getEntryId())));

        assertEquals(List.of(true, false), blobPresentAtCheckpoint);
        assertFalse(Files.exists(blob));
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
        assertBackupsForget(doomed, true);
    }

    @Test
    void failedManifestCommitDeletesNothing() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        byte[] before = Files.readAllBytes(vault.manifestFile(alice));
        ManifestRepository failing = new ManifestRepository(vault.vault) {
            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                throw new VaultStorageException(VaultStorageException.Reason.IO);
            }
        };

        assertReason(FileServiceException.Reason.STORAGE,
                () -> vault.files(alice, failing).permanentlyDelete(List.of(doomed.getEntryId())));

        assertTrue(Files.exists(vault.blob(doomed)));
        assertArrayEquals(before, Files.readAllBytes(vault.manifestFile(alice)));
        assertEquals(List.of(doomed.getEntryId()), ids(files.listTrash()));
    }

    @Test
    void blobsSurviveUntilEveryBackupIsReseeded() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        Path obstacle = blockReplacing(vault.manifestBackup(alice, 2));

        assertReason(FileServiceException.Reason.STORAGE,
                () -> files.permanentlyDelete(List.of(doomed.getEntryId())));

        assertTrue(Files.exists(vault.blob(doomed)), "a backup may still list it");
        UserManifest current = vault.manifest(alice);
        assertNull(new ManifestService(current).find(doomed.getEntryId()));
        assertEquals(List.of(doomed.getBlobId()), blobIds(current.getPendingDeletions()));

        deleteRecursively(obstacle);
        assertEquals(0, files.resumePendingDeletions());

        assertFalse(Files.exists(vault.blob(doomed)));
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
        assertBackupsForget(doomed, true);
    }

    @Test
    void crashAfterBackupsAreReseededResumesOnTheNextRetry() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        ManifestRepository crashing = new ManifestRepository(vault.vault) {
            private boolean crashed;

            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                super.saveCheckpoint(manifest, manifestId, key);

                if (!crashed) {
                    crashed = true;
                    throw new IllegalStateException("simulated crash after step 5");
                }
            }
        };

        assertThrows(IllegalStateException.class,
                () -> vault.files(alice, crashing).permanentlyDelete(List.of(doomed.getEntryId())));

        assertTrue(Files.exists(vault.blob(doomed)));
        assertBackupsForget(doomed, false);

        assertEquals(0, vault.files(alice).resumePendingDeletions());
        assertFalse(Files.exists(vault.blob(doomed)));
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void blobThatCannotBeDeletedStaysQueuedForLater() throws Exception {
        ManifestEntry first = trashed("first.txt");
        ManifestEntry second = trashed("second.txt");
        Path obstacle = blockReplacing(vault.blob(second));

        assertEquals(1, files.permanentlyDelete(List.of(first.getEntryId(), second.getEntryId())));

        assertFalse(Files.exists(vault.blob(first)));
        assertEquals(List.of(second.getBlobId()), blobIds(vault.manifest(alice).getPendingDeletions()));
        assertEquals(List.of(), files.listTrash());
        assertBackupsForget(first, false);
        assertBackupsForget(second, false);

        deleteRecursively(obstacle);
        assertEquals(0, files.resumePendingDeletions());
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void alreadyMissingBlobCountsAsDeleted() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        Files.delete(vault.blob(doomed));

        assertEquals(0, files.permanentlyDelete(List.of(doomed.getEntryId())));

        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void failedCleanupSaveLeavesARetryThatTreatsGoneBlobsAsDone() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        ManifestRepository cleanupFails = new ManifestRepository(vault.vault) {
            private int checkpoints;

            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                if (++checkpoints == 2) {
                    throw new VaultStorageException(VaultStorageException.Reason.IO);
                }

                super.saveCheckpoint(manifest, manifestId, key);
            }
        };

        assertEquals(1, vault.files(alice, cleanupFails).permanentlyDelete(List.of(doomed.getEntryId())));

        assertFalse(Files.exists(vault.blob(doomed)));
        assertEquals(List.of(doomed.getBlobId()), blobIds(vault.manifest(alice).getPendingDeletions()));
        assertEquals(0, files.resumePendingDeletions());
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void recoveredBackupNeverBringsBackDeletedEntries() throws Exception {
        ManifestEntry kept = vault.importText(files, "kept.txt", files.rootFolderId());
        ManifestEntry doomed = trashed("doomed.txt");
        files.permanentlyDelete(List.of(doomed.getEntryId()));
        flipCiphertext(vault.manifestFile(alice));

        List<ManifestEntry> root = files.listChildren(files.rootFolderId());

        assertTrue(RecoveryService.takeRecoveryNotice());
        assertEquals(List.of(kept.getEntryId()), ids(root));
        assertEquals(List.of(), files.listTrash());
        Path out = tempDir.resolve("kept-out.txt");
        files.exportEntry(kept.getEntryId(), out);
        assertEquals("kept.txt", Files.readString(out));
    }

    @Test
    void folderSubtreeQueuesEachBlobExactlyOnce() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        ManifestEntry year = files.createFolder("2025", docs.getEntryId());
        Set<UUID> blobs = Set.of(
                vault.importText(files, "a.txt", docs.getEntryId()).getBlobId(),
                vault.importText(files, "b.txt", year.getEntryId()).getBlobId(),
                vault.importText(files, "c.txt", year.getEntryId()).getBlobId()
        );
        files.moveToTrash(docs.getEntryId());
        List<List<UUID>> queued = new ArrayList<>();
        ManifestRepository watching = new ManifestRepository(vault.vault) {
            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                queued.add(blobIds(manifest.getPendingDeletions()));
                super.saveCheckpoint(manifest, manifestId, key);
            }
        };

        vault.files(alice, watching).permanentlyDelete(List.of(docs.getEntryId(), docs.getEntryId()));

        assertEquals(3, queued.get(0).size());
        assertEquals(blobs, Set.copyOf(queued.get(0)));
        assertEquals(List.of(), vault.blobFiles());
    }

    @Test
    void queuedBlobStillInUseIsNeverDeleted() throws Exception {
        ManifestEntry live = vault.importText(files, "live.txt", files.rootFolderId());
        UserManifest manifest = vault.manifest(alice);
        manifest.getPendingDeletions().add(new PendingDeletion(live.getBlobId(), Instant.now().toString()));
        new ManifestRepository(vault.vault).save(manifest, alice.identity().manifestId(), alice.userMasterKey());

        assertEquals(0, files.resumePendingDeletions());

        assertTrue(Files.exists(vault.blob(live)));
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }

    @Test
    void oneInvalidIdDeletesNothing() throws Exception {
        ManifestEntry doomed = trashed("doomed.txt");
        ManifestEntry active = vault.importText(files, "active.txt", files.rootFolderId());

        assertReason(FileServiceException.Reason.NOT_IN_TRASH,
                () -> files.permanentlyDelete(List.of(doomed.getEntryId(), active.getEntryId())));
        assertReason(FileServiceException.Reason.NOT_FOUND,
                () -> files.permanentlyDelete(List.of(doomed.getEntryId(), UUID.randomUUID())));

        assertTrue(Files.exists(vault.blob(doomed)));
        assertEquals(List.of(doomed.getEntryId()), ids(files.listTrash()));
    }

    // ------------------------------------------------------------ helpers

    private ManifestEntry trashed(String name) throws Exception {
        ManifestEntry entry = vault.importText(files, name, files.rootFolderId());
        files.moveToTrash(entry.getEntryId());
        return entry;
    }

    /** Every backup generation lacks the entry; with {@code queueEmpty} its journal is empty too. */
    private void assertBackupsForget(ManifestEntry entry, boolean queueEmpty) throws Exception {
        for (int generation = 1; generation <= 3; generation++) {
            UserManifest backup = vault.manifestBackupContent(alice, generation);
            assertNull(new ManifestService(backup).find(entry.getEntryId()), "backup " + generation);

            if (queueEmpty) {
                assertTrue(backup.getPendingDeletions().isEmpty(), "backup " + generation);
            }
        }
    }

    /** Replaces a file with a non-empty directory, so replacing or deleting it fails. */
    private static Path blockReplacing(Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.createDirectories(file.resolve("blocker"));
        return file;
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void flipCiphertext(Path envelopeFile) throws IOException {
        JsonObject payload = JsonParser.parseString(Files.readString(envelopeFile)).getAsJsonObject();
        byte[] ciphertext = Base64.getDecoder().decode(payload.get("ciphertext").getAsString());
        ciphertext[ciphertext.length / 2] ^= 0x01;
        payload.addProperty("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Files.writeString(envelopeFile, payload.toString());
    }

    private static List<UUID> ids(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getEntryId).toList();
    }

    private static List<UUID> blobIds(List<PendingDeletion> deletions) {
        return deletions.stream().map(PendingDeletion::blobId).toList();
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }
}
```

In `FileServiceTest` change `files.permanentlyDelete(docs.getEntryId())` to `files.permanentlyDelete(List.of(docs.getEntryId()))` and the call in `onlyTrashedEntriesCanBeDeletedPermanently` likewise.

- [ ] **Step 3: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=PermanentDeleteTest"`
Expected: compile error — `permanentlyDelete(List)` and `resumePendingDeletions()` do not exist.

- [ ] **Step 4: Implement the engine in `FileService`**

Delete the old `permanentlyDelete(UUID)` and add:

```java
    /**
     * Permanently deletes trashed entries and everything below them. The
     * entries leave the manifest and every backup generation before any blob
     * is touched; their blobs are queued in the encrypted manifest and removed
     * afterwards. A crash can orphan ciphertext but never leaves metadata that
     * references deleted ciphertext. Returns how many blobs are still queued
     * because they could not be removed yet.
     */
    public int permanentlyDelete(List<UUID> entryIds) throws FileServiceException {
        synchronized (MANIFEST_LOCK) {
            return withUserMasterKey(key -> deletePermanently(load(key), entryIds, key));
        }
    }

    /**
     * Removes blobs left queued by an interrupted or partly failed permanent
     * delete. Blobs that still cannot be removed stay queued; returns how many.
     */
    public int resumePendingDeletions() throws FileServiceException {
        synchronized (MANIFEST_LOCK) {
            return withUserMasterKey(key -> {
                UserManifest manifest = load(key);

                if (manifest.getPendingDeletions().isEmpty()) {
                    return 0;
                }

                // The delete may have stopped before every backup was reseeded.
                checkpoint(manifest, key);
                return purge(manifest, key);
            });
        }
    }

    /** Spec 9.2: steps 1-5 here, steps 6-9 in {@link #purge}. */
    private int deletePermanently(UserManifest manifest, Collection<UUID> entryIds, byte[] key)
            throws FileServiceException {

        ManifestService rules = new ManifestService(manifest);
        Set<ManifestEntry> doomed = new LinkedHashSet<>();

        for (UUID entryId : entryIds) {
            ManifestEntry entry = rules.find(entryId);

            if (entry == null) {
                throw new FileServiceException(FileServiceException.Reason.NOT_FOUND);
            }

            if (entry.getDeletedAt() == null) {
                throw new FileServiceException(FileServiceException.Reason.NOT_IN_TRASH);
            }

            doomed.addAll(subtree(rules, entry));
        }

        queueBlobs(manifest, doomed);
        manifest.getEntries().removeAll(doomed);
        checkpoint(manifest, key);
        return purge(manifest, key);
    }

    /** Deletes queued blobs one by one, then checkpoints the shorter queue. Never throws. */
    private int purge(UserManifest manifest, byte[] key) {
        List<PendingDeletion> pending = manifest.getPendingDeletions();
        Set<UUID> inUse = manifest.getEntries().stream()
                .map(ManifestEntry::getBlobId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<PendingDeletion> remaining = new ArrayList<>();

        for (PendingDeletion deletion : pending) {
            // A blob a live entry still uses is dropped from the queue, never deleted.
            if (!inUse.contains(deletion.blobId()) && !deleteBlob(deletion.blobId())) {
                remaining.add(deletion);
            }
        }

        if (remaining.size() == pending.size()) {
            return remaining.size();
        }

        int recorded = pending.size();
        pending.retainAll(remaining);

        try {
            checkpoint(manifest, key);
            return remaining.size();
        } catch (FileServiceException e) {
            // Deleted blobs stay listed on disk; the next retry finds them missing.
            return recorded;
        }
    }

    private static void queueBlobs(UserManifest manifest, Collection<ManifestEntry> doomed) {
        Set<UUID> queued = new HashSet<>();

        for (PendingDeletion deletion : manifest.getPendingDeletions()) {
            queued.add(deletion.blobId());
        }

        String queuedAt = Instant.now().toString();

        for (ManifestEntry entry : doomed) {
            if (entry.getKind() == ManifestEntryKind.FILE && queued.add(entry.getBlobId())) {
                manifest.getPendingDeletions().add(new PendingDeletion(entry.getBlobId(), queuedAt));
            }
        }
    }

    /** Missing blobs count as deleted (BlobRepository.delete is deleteIfExists). */
    private boolean deleteBlob(UUID blobId) {
        try {
            blobRepository.delete(vault.root(), blobId);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void checkpoint(UserManifest manifest, byte[] key) throws FileServiceException {
        try {
            manifestRepository.saveCheckpoint(manifest, identity.manifestId(), key);
        } catch (VaultStorageException e) {
            throw storageFailure(e);
        }
    }
```
(imports `com.fabianrodas.models.PendingDeletion`, `java.util.Collection`, `java.util.LinkedHashSet`, `java.util.Objects`). Update the class Javadoc sentence about deletion accordingly.

- [ ] **Step 5: Adapt `TrashController` and `Formats`**

`Formats`:
```java
    /** Shown when some deleted files' encrypted data is still queued for removal. */
    static final String CLEANUP_PENDING = "Some encrypted data could not be removed yet; "
            + "EncryptDrive will retry automatically.";
```

`TrashController.permanentlyDelete()` — replace the `try` block:
```java
        try {
            int pending = files.permanentlyDelete(
                    selected.stream().map(ManifestEntry::getEntryId).toList()
            );

            refresh();
            showSuccess((selected.size() == 1
                    ? "\"" + selected.get(0).getName() + "\" was deleted permanently."
                    : selected.size() + " items were deleted permanently.")
                    + (pending > 0 ? " " + Formats.CLEANUP_PENDING : ""));

        } catch (FileServiceException e) {
            refresh();
            showError(FilesController.describe(e));
        }
```
(T12 moves this into the background.)

- [ ] **Step 6: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=PermanentDeleteTest,FileServiceTest,UiFlowTest,CorruptionIntegrationTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 7: Diff review** — confirm no code path deletes a blob outside `purge`, `deleteBlobQuietly` (import rollback of a never-committed blob) and `writeBlob` cleanup: `git grep -n "blobRepository.delete" src/main`.

- [ ] **Step 8: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/services/FileService.java src/main/java/com/fabianrodas/encryptdrive/TrashController.java src/main/java/com/fabianrodas/encryptdrive/Formats.java src/test/java/com/fabianrodas/services/TestVault.java src/test/java/com/fabianrodas/services/PermanentDeleteTest.java src/test/java/com/fabianrodas/services/FileServiceTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "fix: make permanent deletion crash-safe" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** all 11 `PermanentDeleteTest` cases pass, each proving one boundary of spec 9.2/9.3; build green.

---

### Task 11: One busy-aware close guard for every exit path

**Purpose:** Spec 11, 21.3. X (title bar), Alt+F4/OS close, Logout and Close Vault (dashboard, login, register, vault settings) are all refused while a counted background operation runs; idle close wipes UMK and RMK, releases the lock, and leaves no worker thread (dangerous failure mode #3).

**Files:**
- Modify: `src/main/java/com/fabianrodas/encryptdrive/Background.java` (`isBusy`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/App.java` (`BUSY_CLOSE_MESSAGE`, `installCloseGuard`, `requestClose`, `logout`, `closeVault`; `start`/`stop`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/DialogFactory.java` (`inform`, shared `stage`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/ConfirmationPopupController.java` (`cancelButton`, `setInformational`)
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/confirmation-popup.fxml` (`fx:id="cancelButton"`)
- Modify: `DashboardController.java`, `LoginController.java`, `RegisterController.java`, `VaultSelectionController.java`, `VaultSettingsController.java`
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/vault-settings.fxml` (`fx:id="settingsCloseVaultButton"`)
- Modify: `src/test/java/com/fabianrodas/encryptdrive/FxTestSupport.java` (`showInStage`, `holdBusy`, `fireAndAnswer`, `clickButton`)
- Create: `src/test/java/com/fabianrodas/encryptdrive/CloseGuardTest.java`
- Modify: `src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java` (use `fireAndAnswer`)

**Interfaces:**
- Produces: `Background.isBusy()`, `App.BUSY_CLOSE_MESSAGE`, `App.installCloseGuard(Stage)`, `App.requestClose(Stage)`, `boolean App.logout()`, `boolean App.closeVault()`, `DialogFactory.inform(Window, String, String)`, `ConfirmationPopupController.setInformational()`, FXML ids `cancelButton`, `settingsCloseVaultButton`, test helpers `FxTestSupport.showInStage`, `holdBusy`, `fireAndAnswer`, `clickButton`.

**Security:** keys are wiped in the close handler itself (not only in `App.stop()`), so every guarded close wipes before the window disappears. `Background` has no executor: workers are per-task daemon threads, and the guard guarantees none is running at an idle close (asserted).

- [ ] **Step 1: Test support**

Add to `FxTestSupport`:

```java
    /** Shows a screen in its own stage with the application's close guard installed. */
    static Stage showInStage(String fxml) throws Exception {
        Scene scene = showScreen(fxml);

        return onFxThread(() -> {
            Stage stage = new Stage();
            App.installCloseGuard(stage);
            stage.setScene(scene);
            stage.show();
            return stage;
        });
    }

    /** Starts a counted background task that runs until the returned latch is released. */
    static CountDownLatch holdBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);

        onFxThread(() -> {
            Background.run(() -> {
                release.await();
                return null;
            }, done -> { }, failure -> { });
            return null;
        });

        return release;
    }

    /**
     * Fires a button that opens a modal popup (possibly after background
     * work), waits for the popup, then lets {@code answer} act on its stage:
     * type text, pick a folder, click a button. The button is fired without
     * waiting, because a modal popup blocks its handler until it closes.
     */
    static void fireAndAnswer(Scene scene, String selector, Consumer<Stage> answer) throws Exception {
        Platform.runLater(() -> ((ButtonBase) scene.getRoot().lookup(selector)).fire());
        waitUntil(() -> modalPopup() != null);
        onFxThread(() -> {
            answer.accept(modalPopup());
            return null;
        });
    }

    /** The showing application-modal popup, if any. */
    private static Stage modalPopup() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage
                    && stage.isShowing()
                    && stage.getModality() == Modality.APPLICATION_MODAL) {
                return stage;
            }
        }

        return null;
    }

    /** Clicks the button with the given text in a popup stage. */
    static void clickButton(Stage popup, String text) {
        for (Node node : popup.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof ButtonBase button && text.equals(button.getText())) {
                button.fire();
                return;
            }
        }

        throw new AssertionError("No button \"" + text + "\" in the popup");
    }
```
(imports `javafx.stage.Modality`, `java.util.function.Consumer`.) Replace the body of `fireAndAnswerPopup(scene, selector, answer)` with the one-line delegate `fireAndAnswer(scene, selector, popup -> clickButton(popup, answer));` (keeps `UiFlowTest` working; T23 removes it if unused). Because the popup is now answered after `fire()` returns control, `UiFlowTest.permanentDeleteHappensOnlyAfterExplicitConfirmation` must wait for the outcome instead of asserting immediately: after the "Cancel" answer call `FxTestSupport.onFxThread(() -> null)` once from the test thread (it runs after the button handler has returned; never nest `onFxThread` inside `waitUntil`, which already runs on the FX thread) before asserting the trash still has 1 item, and after "Delete permanently" use `FxTestSupport.waitUntil(() -> files.listTrash().isEmpty())`.

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/fabianrodas/encryptdrive/CloseGuardTest.java`:

```java
package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.security.SensitiveBytes;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultService;
import com.fabianrodas.services.VaultSessionService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/*
 * Spec 11: while background work runs, no exit path may close the window,
 * log out, or close the vault; when idle, closing wipes every key.
 */
class CloseGuardTest {

    @TempDir
    Path tempDir;

    private VaultContext vault;
    private SensitiveBytes userMasterKey;
    private CountDownLatch busy;

    @BeforeAll
    static void startJavaFx() {
        assumeTrue(FxTestSupport.start(), "JavaFX needs a desktop session");
    }

    @BeforeEach
    void openVaultAndSignIn() throws Exception {
        vault = new VaultService().createVault(tempDir.resolve("vault"), "correct vault password".toCharArray());
        AuthService auth = new AuthService(vault);
        auth.register("Example User", "ExampleUser", "example password".toCharArray());
        UserLoginResult login = auth.login("ExampleUser", "example password".toCharArray());
        userMasterKey = login.userMasterKey();
        SessionService.start(login.identity(), userMasterKey);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (busy != null) {
            busy.countDown();
        }

        FxTestSupport.waitUntil(() -> !Background.isBusy());
        FxTestSupport.onFxThread(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window instanceof Stage stage) {
                    stage.close();
                }
            }
            return null;
        });
        VaultSessionService.closeVault();
    }

    @Test
    void osCloseRequestIsRefusedWhileBusyAndExplained() throws Exception {
        Stage stage = FxTestSupport.showInStage("dashboard");
        busy = FxTestSupport.holdBusy();

        FxTestSupport.onFxThread(() -> {
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            return null;
        });

        assertTrue(FxTestSupport.onFxThread(stage::isShowing));
        assertTrue(FxTestSupport.onFxThread(() -> popupShows(App.BUSY_CLOSE_MESSAGE)));
        assertFalse(userMasterKey.isDestroyed());
        assertFalse(vault.isClosed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"vault-selection", "login", "register", "dashboard"})
    void everyTitleBarCloseButtonIsGuarded(String screen) throws Exception {
        Stage stage = FxTestSupport.showInStage(screen);
        busy = FxTestSupport.holdBusy();

        click(stage, "#close");

        assertTrue(FxTestSupport.onFxThread(stage::isShowing));
        assertFalse(vault.isClosed());
    }

    @Test
    void idleCloseWipesKeysReleasesTheLockAndLeavesNoWorker() throws Exception {
        Stage stage = FxTestSupport.showInStage("dashboard");

        click(stage, "#close");

        assertFalse(FxTestSupport.onFxThread(stage::isShowing));
        assertTrue(userMasterKey.isDestroyed());
        assertTrue(vault.isClosed());
        assertFalse(VaultSessionService.isOpen());
        new VaultService().unlockVault(vault.root(), "correct vault password".toCharArray());
        FxTestSupport.waitUntil(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().equals("EncryptDrive worker")));
    }

    @Test
    void logoutIsDisabledAndRefusedWhileBusy() throws Exception {
        Stage stage = FxTestSupport.showInStage("dashboard");
        busy = FxTestSupport.holdBusy();

        assertTrue(FxTestSupport.onFxThread(() -> lookup(stage, "#logoutButton").isDisabled()));
        assertFalse(FxTestSupport.onFxThread(App::logout));
        assertFalse(userMasterKey.isDestroyed());
        assertTrue(SessionService.isActive());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dashboard", "login", "register"})
    void closeVaultIsDisabledAndRefusedWhileBusy(String screen) throws Exception {
        Stage stage = FxTestSupport.showInStage(screen);
        busy = FxTestSupport.holdBusy();

        assertTrue(FxTestSupport.onFxThread(() -> lookup(stage, "#closeVaultButton").isDisabled()));
        assertFalse(FxTestSupport.onFxThread(App::closeVault));
        assertFalse(vault.isClosed());
    }

    @Test
    void vaultSettingsCloseVaultIsDisabledWhileBusy() throws Exception {
        Stage stage = FxTestSupport.showInStage("dashboard");
        click(stage, "#settingsNavButton");
        busy = FxTestSupport.holdBusy();

        assertTrue(FxTestSupport.onFxThread(() -> lookup(stage, "#settingsCloseVaultButton").isDisabled()));
    }

    private static Node lookup(Stage stage, String selector) {
        return stage.getScene().getRoot().lookup(selector);
    }

    private static void click(Stage stage, String selector) throws Exception {
        FxTestSupport.onFxThread(() -> {
            ((ButtonBase) lookup(stage, selector)).fire();
            return null;
        });
    }

    private static boolean popupShows(String message) {
        return Window.getWindows().stream()
                .filter(window -> window instanceof Stage stage && stage.isShowing())
                .flatMap(window -> window.getScene().getRoot().lookupAll(".label").stream())
                .anyMatch(node -> node instanceof Labeled label && message.equals(label.getText()));
    }
}
```

- [ ] **Step 3: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=CloseGuardTest"`
Expected: compile errors (`App.installCloseGuard`, `Background.isBusy`, `App.logout`, `App.BUSY_CLOSE_MESSAGE`). After adding empty stubs: `everyTitleBarCloseButtonIsGuarded` fails (window closes), `closeVaultIsDisabledAndRefusedWhileBusy[login]` fails (button enabled).

- [ ] **Step 4: Implement the guard**

`Background`:
```java
    /** True while a counted background task runs. Call on the JavaFX thread. */
    static boolean isBusy() {
        return BUSY.get();
    }
```

`App` — in `start` replace `stage.setOnCloseRequest(event -> destroySessionKeys());` with `installCloseGuard(stage);`; replace `stop()` and `destroySessionKeys()`/`closeVault()` with:

```java
    static final String BUSY_CLOSE_MESSAGE
            = "Please wait for the current file operation to finish before closing EncryptDrive.";

    @Override
    public void stop() {
        // Backstop for exits that bypass the close guard; idempotent.
        VaultSessionService.closeVault();
    }

    /**
     * The single close path. Title-bar buttons (through {@link #requestClose}),
     * Alt+F4 and OS close requests all reach this handler. While background
     * work runs the request is refused; otherwise user and vault keys are
     * wiped and the vault lock released before the window closes.
     */
    static void installCloseGuard(Stage stage) {
        stage.setOnCloseRequest(event -> {
            if (Background.isBusy()) {
                event.consume();
                DialogFactory.inform(stage, "Please wait", BUSY_CLOSE_MESSAGE);
                return;
            }

            VaultSessionService.closeVault();
        });
    }

    /** Asks the window to close the way the OS does, so the close guard decides. */
    static void requestClose(Stage stage) {
        if (stage != null) {
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        }
    }

    /** Ends the account session and shows Login; refused while background work runs. */
    static boolean logout() throws IOException {
        if (Background.isBusy()) {
            return false;
        }

        SessionService.logout();
        setRoot("login");
        return true;
    }

    /**
     * Logs out, wipes the vault key, releases the vault lock and shows Vault
     * Selection; refused while background work runs.
     */
    static boolean closeVault() throws IOException {
        if (Background.isBusy()) {
            return false;
        }

        VaultSessionService.closeVault();
        setRoot("vault-selection");
        return true;
    }
```
(import `javafx.stage.WindowEvent`; remove the now-unused `SessionService` import only if unused — `logout()` uses it.)

`DialogFactory` — replace `showModal` with a shared builder and add `inform`:

```java
    /** Shows a message with a single OK button and returns at once. */
    static void inform(Window owner, String title, String message) {
        ConfirmationPopupController popup = open(title, message, "OK");

        if (popup != null) {
            popup.setInformational();
            stage(owner, popup).show();
        }
    }

    private static Stage stage(Window owner, ConfirmationPopupController popup) {
        Stage popupStage = new Stage();

        if (owner != null) {
            popupStage.initOwner(owner);
        }

        popupStage.initModality(Modality.APPLICATION_MODAL);
        popupStage.initStyle(StageStyle.UNDECORATED);
        popupStage.setResizable(false);
        popupStage.setScene(new Scene((Parent) popup.root()));
        return popupStage;
    }
```
and replace every `showModal(owner, popup);` with `stage(owner, popup).showAndWait();`.

`confirmation-popup.fxml`: add `fx:id="cancelButton"` to the `Cancel` button. `ConfirmationPopupController`:
```java
    @FXML
    private Button cancelButton;

    /** A message only: an "i" badge and no Cancel button. */
    void setInformational() {
        iconLabel.setText("i");
        iconBadge.getStyleClass().add("info");
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
    }
```

Controllers:
- `DashboardController`: `close()` → `App.requestClose(getStage());`; `logout()` → `App.logout();` inside the existing try; `closeVault()` → `App.closeVault();`.
- `LoginController` and `RegisterController`: add `@FXML private Button closeVaultButton;`; in `initialize` add `closeVaultButton.disableProperty().bind(Background.busyProperty());`; `close()` → `App.requestClose(getStage());`; `closeVault()` body stays (`App.closeVault()` now returns boolean; ignore it).
- `VaultSelectionController.close()` → `App.requestClose(getStage());`.
- `vault-settings.fxml`: add `fx:id="settingsCloseVaultButton"` to the Close Vault button. `VaultSettingsController`: `@FXML private Button settingsCloseVaultButton;`, `settingsCloseVaultButton.disableProperty().bind(Background.busyProperty());` first in `initialize`.

`UiFlowTest.permanentDeleteHappensOnlyAfterExplicitConfirmation`: keep using `fireAndAnswerPopup` (now delegating).

- [ ] **Step 5: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=CloseGuardTest,UiFlowTest,UiLayoutTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 6: Prove no other exit path remains**

Run: `git grep -n "stage.close()\|setOnCloseRequest\|Platform.exit\|System.exit" src/main`
Expected: only `ConfirmationPopupController.close()` and `SuccessPopupController.closePopup()` (popup stages, not the app window) and the single `setOnCloseRequest` in `App.installCloseGuard`.

- [ ] **Step 7: Phase 02 boundary**

1. `git worktree add ../EncryptDrive-phase-check HEAD`, run `mvn -B clean verify` inside it, then `git worktree remove ../EncryptDrive-phase-check`.
2. superpowers:requesting-code-review for the range `<T4 commit>^..HEAD`; fix findings as separate `fix:` commits with tests.
3. Ledger: phase 02 DONE with test counts.

- [ ] **Step 8: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/encryptdrive src/main/resources/com/fabianrodas/encryptdrive/confirmation-popup.fxml src/main/resources/com/fabianrodas/encryptdrive/vault-settings.fxml src/test/java/com/fabianrodas/encryptdrive docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "fix: route every exit path through one busy-aware close guard" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** every exit path is refused while busy with the spec message; idle close wipes UMK + RMK, releases the lock and leaves no worker; Logout/Close Vault disabled while busy on every screen; build green.
