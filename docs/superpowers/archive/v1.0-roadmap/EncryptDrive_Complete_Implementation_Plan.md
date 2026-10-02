# EncryptDrive Complete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the current EncryptDrive JavaFX prototype into a complete, local-only, portable, multi-user encrypted vault where account metadata, file metadata, and file contents are encrypted at rest and each user can decrypt only their own files.

**Architecture:** Keep JavaFX/FXML for UI, but separate UI controllers from business services, repositories, and cryptographic primitives. Use a two-level key hierarchy: a vault password unlocks a random registry key; each user password unlocks a random user master key; each file has a random file data key wrapped by that user's master key. Persist only opaque AES-GCM ciphertext and the minimum plaintext vault header required to derive and unwrap the vault registry key.

**Tech Stack:** Java 21, JavaFX 21, Maven, Gson 2.14.0, Bouncy Castle `bcprov-jdk18on` 1.85.2, JUnit Jupiter 6.1.3, AES-256-GCM, Argon2id, JDK 21 `jpackage`.

**Spec:** `docs/superpowers/specs/EncryptDrive_Complete_Design_Spec.md`

## Global Constraints

- Core operation is entirely local; do not add HTTP clients, servers, cloud APIs, telemetry, analytics, or remote databases.
- Minimum window size remains 1000x600; all primary screens must also render correctly maximized.
- Vaults may be placed on internal disks, removable USB drives, or locally-synchronized folders such as OneDrive.
- Never persist plaintext usernames, full names, logical file names, logical folder names, user password hashes, or user password salts outside encrypted metadata.
- Never persist vault passwords or user passwords.
- Use Argon2id parameters exactly: 65536 KiB memory, 3 iterations, parallelism 1, 16-byte random salt, 32-byte output.
- Use AES/GCM/NoPadding with 32-byte keys, 12-byte random nonce, and 128-bit authentication tag.
- Generate all salts, nonces, RMKs, UMKs, and FDEKs with `SecureRandom`.
- Every encryption context uses the AAD strings specified in the design spec.
- Imported files are streamed; do not load whole files into memory.
- Import never deletes the plaintext source file in version 1.0.
- Export is the only workflow that intentionally writes plaintext outside Java memory.
- Every metadata write is temp-file + atomic replace, with backup rotation before replacement.
- User logout clears UMK/session state. Close Vault clears UMK + RMK, releases the vault lock, and returns to Vault Selection.
- Do not use the existing `data/users.json` in the final application.
- Preserve the existing visual language of Login/Register/Dashboard instead of redesigning it.
- Do not silently migrate the current development account. Version 1.0 starts with encrypted vault-local accounts; existing development users re-register in a vault.

---

# Phase 0 — Stabilize the repository before security work

### Task 1: Align the build with Java 21 and add a real test harness

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/module-info.java`
- Create: `src/test/java/com/fabianrodas/smoke/BuildSmokeTest.java`

**Interfaces:**
- Consumes: current Maven JavaFX project.
- Produces: `mvn clean verify` as the required verification command for every subsequent task.

- [x] **Step 1: Fix the Java version contradiction in `pom.xml`**

Use a single Java version property and remove the current compiler `<release>11</release>` mismatch:

```xml
<properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <junit.version>6.1.3</junit.version>
    <bouncycastle.version>1.85.2</bouncycastle.version>
</properties>
```

Pin the compiler and test plugins:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.15.0</version>
    <configuration>
        <release>${maven.compiler.release}</release>
    </configuration>
</plugin>
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.5.5</version>
    <configuration>
        <useModulePath>true</useModulePath>
    </configuration>
</plugin>
```

Add test dependency:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${junit.version}</version>
    <scope>test</scope>
</dependency>
```

- [x] **Step 2: Write a build smoke test**

```java
package com.fabianrodas.smoke;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildSmokeTest {
    @Test
    void testRuntimeIsJava21OrNewer() {
        assertEquals(true, Runtime.version().feature() >= 21);
    }
}
```

- [x] **Step 3: Run the baseline test suite**

Run:

```bash
mvn clean verify
```

Expected: build succeeds and `BuildSmokeTest` passes.

- [x] **Step 4: Commit**

```bash
git add pom.xml src/main/java/module-info.java src/test/java/com/fabianrodas/smoke/BuildSmokeTest.java
git commit -m "chore: align build with Java 21 and add test harness"
```

### Task 2: Remove plaintext development data and generated artifacts from source control

**Files:**
- Modify: `.gitignore`
- Delete: `data/users.json`
- Delete from tracking if present: `target/`
- Modify: `README.md` only enough to remove instructions that encourage committing `data/users.json`; the full README rewrite happens in Task 18.

**Interfaces:**
- Produces: repository contains no local user database.

- [x] **Step 1: Add local data and packaging ignores**

Append:

```gitignore
##############################
## EncryptDrive local data
##############################
data/
*.edv.part
*.enc.part
*.tmp

##############################
## Packaging output
##############################
target/
dist/
```

- [x] **Step 2: Remove the development database and generated build output**

Run:

```bash
git rm -r --cached target 2>/dev/null || true
git rm --cached data/users.json 2>/dev/null || true
rm -rf target data/users.json
```

Do not print the former JSON contents to logs or commits.

- [x] **Step 3: Check repository history before continuing**

> Result (2026-09-30): `data/users.json` is in the history of the public `Fabianrodas/EncryptDrive` remote (commits `0030c7c`, `9d866c1`). Reported to the owner, who acknowledged it and chose to continue; history was not rewritten.

Run:

```bash
git log --all -- data/users.json
```

If the file was pushed to a public remote, stop execution and report that account metadata plus an offline password verifier existed in repository history. The affected development password should not be reused elsewhere. Do not rewrite shared history automatically.

- [x] **Step 4: Verify the project still builds**

Run:

```bash
mvn clean verify
```

Expected: PASS; runtime code may recreate no global `data/users.json` because the final persistence migration has not happened yet, but tests must not require that file.

- [x] **Step 5: Commit**

```bash
git add .gitignore README.md
git add -u
git commit -m "chore: remove plaintext development user data"
```

---

# Phase 1 — Build and prove the cryptographic primitives

### Task 3: Add Argon2id key derivation and sensitive byte handling

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/module-info.java`
- Create: `src/main/java/com/fabianrodas/security/CryptoConstants.java`
- Create: `src/main/java/com/fabianrodas/security/SensitiveBytes.java`
- Create: `src/main/java/com/fabianrodas/security/Argon2KeyDeriver.java`
- Test: `src/test/java/com/fabianrodas/security/Argon2KeyDeriverTest.java`
- Test: `src/test/java/com/fabianrodas/security/SensitiveBytesTest.java`

**Interfaces:**
- Produces: `byte[] derive(char[] password, byte[] salt, Argon2ParametersSpec parameters)` returning exactly 32 bytes.
- Produces: `SensitiveBytes` with `copy()`, `isDestroyed()`, and `close()`.

- [x] **Step 1: Add Bouncy Castle**

```xml
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>${bouncycastle.version}</version>
</dependency>
```

Add the Bouncy Castle module requirement matching the dependency's module descriptor/automatic-module name. Verify it with:

```bash
jar --describe-module --file ~/.m2/repository/org/bouncycastle/bcprov-jdk18on/1.85.2/bcprov-jdk18on-1.85.2.jar
```

Then use the exact module name reported by that command in `module-info.java`.

- [x] **Step 2: Define immutable crypto constants**

`CryptoConstants` must expose:

```java
public static final int KEY_BYTES = 32;
public static final int SALT_BYTES = 16;
public static final int GCM_NONCE_BYTES = 12;
public static final int GCM_TAG_BITS = 128;
public static final int ARGON_MEMORY_KIB = 65_536;
public static final int ARGON_ITERATIONS = 3;
public static final int ARGON_PARALLELISM = 1;
```

- [x] **Step 3: Write failing Argon2 tests**

Test cases:

```java
@Test
void deriveReturns32Bytes() { ... }

@Test
void samePasswordAndSaltProduceSameKey() { ... }

@Test
void differentPasswordProducesDifferentKey() { ... }

@Test
void callerPasswordArrayIsNotModified() { ... }
```

- [x] **Step 4: Implement `Argon2KeyDeriver`**

Required signature:

```java
public final class Argon2KeyDeriver {
    public byte[] derive(char[] password, byte[] salt, int memoryKiB, int iterations, int parallelism);
}
```

Convert the password copy to UTF-8 bytes, derive with Bouncy Castle `Argon2BytesGenerator`, and zero all temporary byte/char arrays in `finally` blocks.

- [x] **Step 5: Implement and test `SensitiveBytes`**

Required behavior:

```java
try (SensitiveBytes key = SensitiveBytes.copyOf(source)) {
    byte[] working = key.copy();
    // use working
    Arrays.fill(working, (byte) 0);
}
```

`close()` must zero its internal array and become idempotent. `copy()` after destruction throws `IllegalStateException`.

- [x] **Step 6: Verify**

Run:

```bash
mvn -Dtest=Argon2KeyDeriverTest,SensitiveBytesTest test
mvn clean verify
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add pom.xml src/main/java/module-info.java src/main/java/com/fabianrodas/security src/test/java/com/fabianrodas/security
git commit -m "feat: add Argon2id key derivation"
```

### Task 4: Add authenticated AES-GCM encryption and key wrapping

**Files:**
- Create: `src/main/java/com/fabianrodas/models/EncryptedPayload.java`
- Create: `src/main/java/com/fabianrodas/security/AesGcmService.java`
- Create: `src/main/java/com/fabianrodas/security/CryptoException.java`
- Test: `src/test/java/com/fabianrodas/security/AesGcmServiceTest.java`

**Interfaces:**
- Produces: `EncryptedPayload encrypt(byte[] plaintext, byte[] key, byte[] aad)`.
- Produces: `byte[] decrypt(EncryptedPayload payload, byte[] key, byte[] aad) throws CryptoException`.
- Produces: `EncryptedPayload wrapKey(byte[] rawKeyToWrap, byte[] wrappingKey, byte[] aad)` and matching `unwrapKey`.

- [x] **Step 1: Define `EncryptedPayload`**

Use a Gson-friendly POJO containing only:

```java
private int version;
private String algorithm;
private String nonce;
private String ciphertext;
```

Version is `1`; algorithm is exactly `AES/GCM/NoPadding`.

- [x] **Step 2: Write failing round-trip and tamper tests**

Required tests:

```java
@Test void encryptThenDecryptReturnsOriginalBytes()
@Test void twoEncryptionsUseDifferentNonces()
@Test void wrongKeyFailsAuthentication()
@Test void wrongAadFailsAuthentication()
@Test void modifiedCiphertextFailsAuthentication()
@Test void wrapThenUnwrapKeyReturnsSameKey()
```

- [x] **Step 3: Implement `AesGcmService`**

Generate a fresh 12-byte nonce for each `encrypt`/`wrapKey`; never accept caller-supplied encryption nonces. Use `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, nonce)`, and `cipher.updateAAD(aad)` before `doFinal`.

- [x] **Step 4: Verify**

```bash
mvn -Dtest=AesGcmServiceTest test
mvn clean verify
```

Expected: all tamper cases fail closed with `CryptoException` and never return plaintext.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/fabianrodas/models/EncryptedPayload.java src/main/java/com/fabianrodas/security/AesGcmService.java src/main/java/com/fabianrodas/security/CryptoException.java src/test/java/com/fabianrodas/security/AesGcmServiceTest.java
git commit -m "feat: add authenticated AES-GCM primitives"
```

---

# Phase 2 — Define the encrypted vault format and persistence layer

### Task 5: Replace the current vault model with the versioned encrypted vault header

**Files:**
- Replace: `src/main/java/com/fabianrodas/models/Vault.java`
- Create: `src/main/java/com/fabianrodas/models/KdfConfig.java`
- Create: `src/main/java/com/fabianrodas/models/VaultHeader.java`
- Create: `src/main/java/com/fabianrodas/repositories/AtomicFileWriter.java`
- Create: `src/main/java/com/fabianrodas/repositories/VaultRepository.java`
- Test: `src/test/java/com/fabianrodas/repositories/AtomicFileWriterTest.java`
- Test: `src/test/java/com/fabianrodas/repositories/VaultRepositoryTest.java`

> Execution note: `VaultStorageException` lives in `repositories`. The legacy `Vault.java` is deleted in Task 7 together with its only consumers (`VaultService`, `VaultSessionService`), so that every commit compiles.

**Interfaces:**
- `VaultHeader` matches the `vault.json` contract from the design spec.
- `VaultRepository.writeHeader(Path vaultRoot, VaultHeader header)`.
- `VaultRepository.readHeader(Path vaultRoot)`.

- [x] **Step 1: Write persistence tests using `@TempDir`**

Cover:

```java
@Test void writeThenReadHeaderRoundTrips(@TempDir Path tempDir)
@Test void missingHeaderThrowsVaultStorageException(@TempDir Path tempDir)
@Test void malformedHeaderThrowsVaultStorageException(@TempDir Path tempDir)
@Test void atomicWriterReplacesExistingFile(@TempDir Path tempDir)
```

- [x] **Step 2: Implement `AtomicFileWriter`**

Required signature:

```java
public void write(Path destination, byte[] bytes) throws IOException
```

Behavior: create same-directory temp file, write bytes, close, then `ATOMIC_MOVE + REPLACE_EXISTING`; fallback to `REPLACE_EXISTING`; delete leftover temp file in `finally`.

- [x] **Step 3: Implement `VaultHeader` contract**

Use fields exactly corresponding to the spec: `formatVersion`, `vaultId`, `createdAt`, `KdfConfig kdf`, `EncryptedPayload wrappedRegistryKey`.

- [x] **Step 4: Implement `VaultRepository`**

Paths are centralized constants:

```java
public static final String META_DIR = ".encryptdrive";
public static final String VAULT_HEADER = "vault.json";
public static final String USERS_FILE = "users.enc";
public static final String MANIFESTS_DIR = "manifests";
public static final String BACKUPS_DIR = "backups";
public static final String BLOBS_DIR = "storage/blobs";
public static final String LOCK_FILE = "lock";
```

Reject unsupported `formatVersion` before any decrypt attempt.

- [x] **Step 5: Verify and commit**

```bash
mvn -Dtest=AtomicFileWriterTest,VaultRepositoryTest test
mvn clean verify
git add src/main/java/com/fabianrodas/models src/main/java/com/fabianrodas/repositories src/test/java/com/fabianrodas/repositories
git commit -m "feat: define encrypted vault persistence format"
```

### Task 6: Add vault locking and encrypted metadata backup rotation

**Files:**
- Create: `src/main/java/com/fabianrodas/services/VaultLockService.java`
- Create: `src/main/java/com/fabianrodas/repositories/BackupRotator.java`
- Test: `src/test/java/com/fabianrodas/services/VaultLockServiceTest.java`
- Test: `src/test/java/com/fabianrodas/repositories/BackupRotatorTest.java`

> Execution note: the BUSY domain exception is `services/VaultException` (created here instead of Task 7, which extends its reasons).

**Interfaces:**
- `VaultLockService.acquire(Path vaultRoot)` returns an `AutoCloseable VaultLock` retaining `FileChannel` + `FileLock`.
- `BackupRotator.rotate(Path encryptedFile, Path backupDirectory, int generations)`.

- [x] **Step 1: Write tests**

Required behavior:

```java
@Test void secondLockInSameJvmFailsWhileFirstIsHeld()
@Test void lockCanBeReacquiredAfterClose()
@Test void backupRotationKeepsExactlyThreeGenerations()
@Test void backupRotationNeverCopiesPlaintext()
```

- [x] **Step 2: Implement locking with `FileChannel.tryLock()`**

Open `.encryptdrive/lock` with `CREATE, WRITE`. Keep both channel and lock alive until vault close. Convert `OverlappingFileLockException` and null lock result into a domain exception with reason `BUSY`.

- [x] **Step 3: Implement three-generation rotation**

For `users.enc`, rotate `users.enc.2 -> users.enc.3`, `.1 -> .2`, current -> `.1` before replacement. Apply the same naming rule to manifests inside `backups/manifests`.

- [x] **Step 4: Verify and commit**

```bash
mvn -Dtest=VaultLockServiceTest,BackupRotatorTest test
mvn clean verify
git add src/main/java/com/fabianrodas/services/VaultLockService.java src/main/java/com/fabianrodas/repositories/BackupRotator.java src/test/java/com/fabianrodas/services/VaultLockServiceTest.java src/test/java/com/fabianrodas/repositories/BackupRotatorTest.java
git commit -m "feat: add vault locking and metadata backups"
```

---

# Phase 3 — Make vault creation/unlock real and encrypted

### Task 7: Refactor `VaultService` around the RMK key hierarchy

**Files:**
- Replace: `src/main/java/com/fabianrodas/services/VaultService.java`
- Replace: `src/main/java/com/fabianrodas/services/VaultSessionService.java`
- Create: `src/main/java/com/fabianrodas/models/VaultContext.java`
- Create: `src/main/java/com/fabianrodas/services/VaultException.java`
- Test: `src/test/java/com/fabianrodas/services/VaultServiceTest.java`

**Interfaces:**

```java
public VaultContext createVault(Path root, char[] vaultPassword) throws VaultException;
public VaultContext unlockVault(Path root, char[] vaultPassword) throws VaultException;
public void changeVaultPassword(char[] currentPassword, char[] newPassword) throws VaultException;
public void closeVault();
```

`VaultContext` exposes non-secret metadata and holds RMK through `SensitiveBytes`; it is `AutoCloseable`.

- [x] **Step 1: Write failing lifecycle tests**

Use `@TempDir` and assert:

```java
@Test void createVaultCreatesOnlyExpectedStructure()
@Test void createVaultDoesNotWritePasswordOrUserDataInPlaintext()
@Test void correctPasswordUnlocksVault()
@Test void wrongPasswordCannotUnwrapRegistryKey()
@Test void tamperedWrappedRegistryKeyFailsUnlock()
@Test void changeVaultPasswordKeepsSameRegistryKey()
@Test void oldVaultPasswordFailsAfterChange()
@Test void newVaultPasswordWorksAfterChange()
```

- [x] **Step 2: Implement create flow**

Create directories, generate UUID vaultId, random RMK, random Argon2 salt, derive VKEK, wrap RMK with AAD `EncryptDrive|vault-key|v1|<vaultId>`, write `vault.json`, and write an encrypted empty registry to `users.enc` under RMK.

- [x] **Step 3: Implement unlock flow**

Acquire vault lock before unlock completion; parse header; derive VKEK; unwrap RMK; decrypt and parse `users.enc` as a validation step. On any failure, close lock and zero temporary keys.

- [x] **Step 4: Implement vault password change**

Require current password to unwrap the current RMK. Generate a new salt, derive a new VKEK, rewrap the same RMK, and atomically replace only `vault.json`. Assert in test that `users.enc` bytes do not change.

- [x] **Step 5: Verify and commit**

```bash
mvn -Dtest=VaultServiceTest test
mvn clean verify
git add src/main/java/com/fabianrodas/services/VaultService.java src/main/java/com/fabianrodas/services/VaultSessionService.java src/main/java/com/fabianrodas/models/VaultContext.java src/main/java/com/fabianrodas/services/VaultException.java src/test/java/com/fabianrodas/services/VaultServiceTest.java
git commit -m "feat: implement encrypted vault lifecycle"
```

---

# Phase 4 — Replace plaintext users with encrypted account registry

### Task 8: Introduce encrypted user records, registry repository, and AuthService

**Files:**
- Delete after migration: `src/main/java/com/fabianrodas/controllers/UserController.java`
- Replace: `src/main/java/com/fabianrodas/models/User.java`
- Create: `src/main/java/com/fabianrodas/models/UserRecord.java`
- Create: `src/main/java/com/fabianrodas/models/UserRegistry.java`
- Create: `src/main/java/com/fabianrodas/models/UserSessionIdentity.java`
- Create: `src/main/java/com/fabianrodas/repositories/UserRegistryRepository.java`
- Create: `src/main/java/com/fabianrodas/services/AuthService.java`
- Create: `src/main/java/com/fabianrodas/services/AuthException.java`
- Test: `src/test/java/com/fabianrodas/repositories/UserRegistryRepositoryTest.java`
- Test: `src/test/java/com/fabianrodas/services/AuthServiceTest.java`

> Execution note: Step 7 requires Login/Register/Profile to use `AuthService` before `UserController`, `PasswordHasher`, and `User` can be deleted, so the minimal call-site migration of `LoginController`, `RegisterController`, and `DashboardController` (plus `SessionService` holding `UserSessionIdentity`) lands in this commit to keep it compiling. `UserLoginResult` lives in `models`. Task 11 completes the UI reconnection.

**Interfaces:**

```java
public UserSessionIdentity register(String fullName, String username, char[] password) throws AuthException;
public UserLoginResult login(String username, char[] password) throws AuthException;
public void changePassword(UUID userId, char[] currentPassword, char[] newPassword) throws AuthException;
```

`UserLoginResult` contains `UserSessionIdentity identity` and `SensitiveBytes userMasterKey`.

- [x] **Step 1: Write registry confidentiality tests**

After registering a user, read raw `.encryptdrive/users.enc` bytes and assert the UTF-8 text does not contain the full name, username, normalized username, or user UUID string. Decrypt through the repository and assert the record exists.

- [x] **Step 2: Write account security tests**

Required:

```java
@Test void registerCreatesRandomUmkAndEncryptedManifestReference()
@Test void usernamesAreUniqueCaseInsensitively()
@Test void correctPasswordUnwrapsUmk()
@Test void wrongPasswordCannotUnwrapUmk()
@Test void changePasswordKeepsSameUmk()
@Test void oldPasswordFailsAfterPasswordChange()
@Test void newPasswordWorksAfterPasswordChange()
```

- [x] **Step 3: Implement encrypted registry repository**

The repository receives the current RMK from `VaultSessionService`, serializes `UserRegistry` with Gson, encrypts it under RMK with AAD `EncryptDrive|users|v1|<vaultId>`, rotates backups, and writes `users.enc` atomically.

- [x] **Step 4: Implement registration without a stored password hash**

Generate user UUID, manifest UUID, UMK, Argon2 salt, derive UKEK, wrap UMK with AAD `EncryptDrive|user-key|v1|<vaultId>|<userId>`. Persist only the wrapped UMK and KDF parameters inside the encrypted registry.

- [x] **Step 5: Implement login by key unwrap**

Normalize username with `trim().toLowerCase(Locale.ROOT)`. Locate the user record in the decrypted registry. Derive UKEK from entered password and attempt UMK unwrap. Any missing user or unwrap failure maps to exactly `Invalid username or password.` at the controller boundary.

- [x] **Step 6: Implement password change as UMK rewrap**

Unwrap the UMK with the current password, derive a new UKEK using a fresh salt, wrap the same UMK, replace the user record, encrypt registry, zero all temporary key arrays.

- [x] **Step 7: Remove obsolete password hashing persistence**

Delete `PasswordHasher.java` only after Login/Register/Profile use `AuthService`. No `passwordHash` or user `salt` fields remain in persisted models.

- [x] **Step 8: Verify and commit**

```bash
mvn -Dtest=UserRegistryRepositoryTest,AuthServiceTest test
mvn clean verify
git add -A src/main/java/com/fabianrodas/controllers src/main/java/com/fabianrodas/models src/main/java/com/fabianrodas/repositories src/main/java/com/fabianrodas/services src/main/java/com/fabianrodas/security src/test/java/com/fabianrodas/repositories src/test/java/com/fabianrodas/services
git commit -m "feat: add encrypted local account registry"
```

### Task 9: Harden session lifecycle around RMK and UMK

**Files:**
- Replace: `src/main/java/com/fabianrodas/services/SessionService.java`
- Modify: `src/main/java/com/fabianrodas/services/VaultSessionService.java`
- Modify: `src/main/java/com/fabianrodas/encryptdrive/App.java`
- Test: `src/test/java/com/fabianrodas/services/SessionServiceTest.java`

> Execution note: the session holders stay static (the existing idiom, and FXML controllers are created by the loader); `isActive()` was added for UI checks. `LoginController` and `DashboardController` move to the new session API in this commit so it compiles.

**Interfaces:**

```java
public void start(UserSessionIdentity identity, SensitiveBytes userMasterKey);
public UserSessionIdentity identity();
public SensitiveBytes copyUserMasterKey();
public void logout();
```

- [x] **Step 1: Write tests that keys are destroyed on logout/close**

The test must retain a reference to the original `SensitiveBytes`, call logout, and assert `isDestroyed()` is true.

- [x] **Step 2: Replace the static `User` session object**

Session state contains only UI-safe identity plus UMK. It does not carry KDF salt, wrapped key, registry payload, or full `UserRecord`.

- [x] **Step 3: Add application shutdown cleanup**

In `App.start`, register a close-request handler that calls `SessionService.logout()` then `VaultSessionService.closeVault()`. Override `Application.stop()` with the same idempotent cleanup.

- [x] **Step 4: Verify and commit**

```bash
mvn -Dtest=SessionServiceTest test
mvn clean verify
git add src/main/java/com/fabianrodas/services/SessionService.java src/main/java/com/fabianrodas/services/VaultSessionService.java src/main/java/com/fabianrodas/encryptdrive/App.java src/test/java/com/fabianrodas/services/SessionServiceTest.java
git commit -m "refactor: harden in-memory session key lifecycle"
```

---

# Phase 5 — Add the startup vault UX and reconnect authentication

### Task 10: Add Vault Selection / Create / Unlock screens

**Files:**
- Create: `src/main/java/com/fabianrodas/encryptdrive/VaultSelectionController.java`
- Create: `src/main/resources/com/fabianrodas/encryptdrive/vault-selection.fxml`
- Create: `src/main/resources/com/fabianrodas/css/vault-selection.css`
- Modify: `src/main/java/com/fabianrodas/encryptdrive/App.java`
- Modify: `src/main/java/module-info.java`

> Execution note: Step 2's continue/cancel modal needs the shared popup, so `DialogFactory`, `ConfirmationPopupController`, `confirmation-popup.fxml`, and `confirmation-popup.css` are created here (Task 16 extends them). `Background` runs key derivation off the JavaFX thread. `UiLayoutTest` automates the 1000x600 / maximized fit check whenever a desktop session is available. `module-info.java` needed no change.

**Interfaces:**
- Startup root becomes `vault-selection`.
- Create flow calls `VaultService.createVault`.
- Open flow calls `VaultService.unlockVault`.

- [x] **Step 1: Build screen using the existing Login/Register visual language**

The screen contains exactly two sections:

1. Create New Vault: vault name/folder name, vault password, confirm vault password, choose parent directory.
2. Open Existing Vault: choose directory, vault password.

Include the existing custom title bar and `WindowDragHandler` behavior.

- [x] **Step 2: Enforce vault-password UX rules**

Creation requires at least 12 characters and confirmation match. Before final create action, show a modal with exact message:

`EncryptDrive cannot recover a lost vault password. If you lose it, the vault cannot be unlocked.`

The user must explicitly continue or cancel.

- [x] **Step 3: Route after successful create/open**

- New vault with zero users -> `register.fxml`.
- Existing unlocked vault -> `login.fxml`.

Do not store the selected path in global OS settings; the vault remains portable.

- [x] **Step 4: Add manual UI smoke checklist to `docs/testing/manual-ui.md`**

Document minimum-size and maximized checks for create/open/cancel/wrong password/close/maximize/restore-drag.

- [x] **Step 5: Verify and commit**

```bash
mvn clean verify
git add src/main/java/com/fabianrodas/encryptdrive/VaultSelectionController.java src/main/resources/com/fabianrodas/encryptdrive/vault-selection.fxml src/main/resources/com/fabianrodas/css/vault-selection.css src/main/java/com/fabianrodas/encryptdrive/App.java src/main/java/module-info.java docs/testing/manual-ui.md
git commit -m "feat: add vault selection and unlock flow"
```

### Task 11: Reconnect Login, Register, Profile, Logout, and Close Vault to the new services

**Files:**
- Modify: `src/main/java/com/fabianrodas/encryptdrive/LoginController.java`
- Modify: `src/main/java/com/fabianrodas/encryptdrive/RegisterController.java`
- Modify: `src/main/java/com/fabianrodas/encryptdrive/DashboardController.java`
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/login.fxml`
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/register.fxml`
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/dashboard.fxml`

**Interfaces:**
- Controllers call `AuthService`, never repositories or crypto services directly.
- Password values are converted to `char[]`, input controls are cleared, and arrays are zeroed in `finally`.

- [x] **Step 1: Replace `UserController` calls in Login/Register**

Controller pattern:

```java
char[] password = passwordField.getText().toCharArray();
passwordField.clear();
visiblePasswordField.clear();
try {
    UserLoginResult result = authService.login(usernameField.getText(), password);
    sessionService.start(result.identity(), result.userMasterKey());
    App.setRoot("dashboard");
} finally {
    Arrays.fill(password, '\0');
}
```

Apply the same cleanup pattern to registration and password-change flows.

- [x] **Step 2: Keep current success-popup behavior**

On successful registration, the custom popup still allows X to remain on Register and `Go to Log In` to navigate to Login.

- [x] **Step 3: Separate Log Out and Close Vault**

Add `Close Vault` in dashboard sidebar/settings. Logout returns to Login with RMK and lock retained; Close Vault destroys UMK/RMK, releases lock, and returns to Vault Selection.

- [x] **Step 4: Verify manually and commit**

Run:

```bash
mvn clean verify
mvn javafx:run
```

Manual: create vault -> register two accounts -> login/logout between them -> close vault -> reopen -> wrong vault password fails -> correct password succeeds.

Commit:

```bash
git add src/main/java/com/fabianrodas/encryptdrive src/main/resources/com/fabianrodas/encryptdrive
git commit -m "feat: connect encrypted authentication to JavaFX flows"
```

---

# Phase 6 — Build per-user encrypted manifests

### Task 12: Add logical folders, manifests, and encrypted manifest repository

**Files:**
- Create: `src/main/java/com/fabianrodas/models/ManifestEntryKind.java`
- Create: `src/main/java/com/fabianrodas/models/ManifestEntry.java`
- Create: `src/main/java/com/fabianrodas/models/UserManifest.java`
- Create: `src/main/java/com/fabianrodas/repositories/ManifestRepository.java`
- Create: `src/main/java/com/fabianrodas/services/ManifestService.java`
- Test: `src/test/java/com/fabianrodas/repositories/ManifestRepositoryTest.java`
- Test: `src/test/java/com/fabianrodas/services/ManifestServiceTest.java`

> Execution note: folder-rule violations use `services/FileServiceException` (created here; Task 14 extends its reasons). `AuthService.register` now writes the new user's manifest before the registry commit and deletes it if that commit fails. The root-folder protection against delete/rename is enforced by the Task 14 operations (v1 has no rename).

**Interfaces:**

```java
public UserManifest load(UUID userId, UUID manifestId, byte[] umk);
public void save(UserManifest manifest, UUID manifestId, byte[] umk);
public ManifestEntry createFolder(UUID parentId, String name);
public List<ManifestEntry> listChildren(UUID parentId, boolean includeDeleted);
```

- [x] **Step 1: Write confidentiality/tamper tests**

Raw manifest ciphertext must not contain logical folder names, file names, or user ID as UTF-8 text. Wrong UMK, wrong AAD, or one-byte corruption must fail authentication.

- [x] **Step 2: Define root-folder invariant**

Every new user gets one root folder entry created during registration. Root has a UUID, `kind=FOLDER`, `parentId=null`, `name="/"`, and can never be deleted or renamed.

- [x] **Step 3: Implement encrypted manifest repository**

Use UMK and AAD `EncryptDrive|manifest|v1|<vaultId>|<userId>`. Rotate three encrypted backups before writes.

- [x] **Step 4: Implement folder rules**

Reject empty names, `.` and `..`, embedded NUL, names over 255 code points, and duplicate sibling names case-insensitively. Logical names may contain OS path separators because they are metadata, but export must sanitize/validate for the target OS.

- [x] **Step 5: Verify and commit**

```bash
mvn -Dtest=ManifestRepositoryTest,ManifestServiceTest test
mvn clean verify
git add src/main/java/com/fabianrodas/models src/main/java/com/fabianrodas/repositories/ManifestRepository.java src/main/java/com/fabianrodas/services/ManifestService.java src/test/java/com/fabianrodas
git commit -m "feat: add encrypted per-user manifests"
```

---

# Phase 7 — Encrypt actual file bytes with streaming I/O

### Task 13: Add immutable encrypted blob storage

**Files:**
- Create: `src/main/java/com/fabianrodas/repositories/BlobRepository.java`
- Create: `src/main/java/com/fabianrodas/services/StreamingFileCryptoService.java`
- Create: `src/main/java/com/fabianrodas/models/EncryptedFileDescriptor.java`
- Test: `src/test/java/com/fabianrodas/services/StreamingFileCryptoServiceTest.java`
- Test: `src/test/java/com/fabianrodas/repositories/BlobRepositoryTest.java`

> Execution note: file content uses Bouncy Castle's streaming GCM because the JDK's `AES/GCM` buffers the entire ciphertext while decrypting, which cannot pass Step 5. The output is standard GCM (ciphertext followed by the 128-bit tag) and a test decrypts a streamed blob with the JCE implementation. `EncryptResult` is `EncryptedFileDescriptor(plainSize, encryptedSize)`. Step 5 is the opt-in `LargeFileStreamingTest` (`-Dencryptdrive.largeFileCheck=true -DargLine=-Xmx256m`): 1 GiB round-tripped in 32 s on 2026-09-30.

**Interfaces:**

```java
public EncryptResult encrypt(Path source, Path destinationPart, byte[] fdek, byte[] nonce, byte[] aad);
public void decrypt(Path encryptedBlob, Path destinationPart, byte[] fdek, byte[] nonce, byte[] aad);
public Path blobPath(Path vaultRoot, UUID blobId);
```

- [x] **Step 1: Write file round-trip tests**

Test zero-byte, 1-byte, 1 MiB, and at least 32 MiB deterministic test files. Assert SHA-256 of exported plaintext equals original. Assert ciphertext differs from plaintext and raw blob does not contain a known 64-byte marker from the source.

- [x] **Step 2: Write tamper tests**

Flip one byte near the start, middle, and end/tag of separate ciphertext copies. Every export must fail and the `.part` plaintext destination must be deleted.

- [x] **Step 3: Implement streaming encryption**

Use `CipherOutputStream` or explicit chunked `Cipher.update` loop with 64 KiB buffers. Do not call `Files.readAllBytes` on user files. Write only to `.part`; caller performs final move after successful close/tag generation.

- [x] **Step 4: Implement sharded blob paths**

`blobId` UUID without dashes -> first two hex characters -> `storage/blobs/<shard>/<blobId>.edv`.

- [x] **Step 5: Verify memory behavior**

Run a manual test with a file larger than available comfortable heap, for example 1 GiB if disk space permits, with `-Xmx256m`; import/export must not throw `OutOfMemoryError`.

- [x] **Step 6: Commit**

```bash
mvn clean verify
git add src/main/java/com/fabianrodas/repositories/BlobRepository.java src/main/java/com/fabianrodas/services/StreamingFileCryptoService.java src/main/java/com/fabianrodas/models/EncryptedFileDescriptor.java src/test/java/com/fabianrodas
git commit -m "feat: add streaming encrypted blob storage"
```

### Task 14: Implement transactional file/folder workflows

**Files:**
- Create: `src/main/java/com/fabianrodas/services/FileService.java`
- Create: `src/main/java/com/fabianrodas/services/FileServiceException.java`
- Create: `src/main/java/com/fabianrodas/models/WorkspaceStats.java`
- Test: `src/test/java/com/fabianrodas/services/FileServiceTest.java`

> Execution note: `FileService` also exposes `rootFolderId()`, `listChildren()`, `pathTo()`, `listTrash()`, `forCurrentSession()`, and `safeFileName()` (Windows-safe export names, also reused for vault folder names). It never holds the UMK: each operation takes a fresh copy from a `Supplier<SensitiveBytes>` and wipes it. `ManifestRepository` and `StreamingFileCryptoService` are no longer `final`, so tests can inject disk failures.

**Interfaces:**

```java
public ManifestEntry importFile(Path source, UUID parentFolderId);
public ManifestEntry createFolder(String name, UUID parentFolderId);
public void exportEntry(UUID entryId, Path destination);
public void moveToTrash(UUID entryId);
public void restore(UUID entryId);
public void permanentlyDelete(UUID entryId);
public WorkspaceStats stats();
```

- [x] **Step 1: Write import transaction tests**

Cover successful import, duplicate logical name, encryption failure, manifest-save failure, and source-file disappearance during import. No failed case may leave a committed manifest entry pointing to a missing blob; newly-created orphan blobs are deleted on rollback.

- [x] **Step 2: Implement FDEK envelope workflow**

For each file: random FDEK, random content nonce, encrypt blob, wrap FDEK under UMK with AAD `EncryptDrive|file-key|v1|<vaultId>|<userId>|<fileId>`, then commit manifest entry.

- [x] **Step 3: Implement export transaction**

Decrypt to `<chosen-name>.part`, authenticate successfully, then replace destination only after user overwrite confirmation from the controller. Service never exposes decrypted byte arrays for whole files.

- [x] **Step 4: Implement recursive logical folders**

Folder export recursively creates target directories and exports only non-deleted descendants. Folder trash recursively marks descendants deleted with the same deletion timestamp. Restore rehydrates the subtree.

- [x] **Step 5: Implement stats**

`WorkspaceStats` returns active file count, active plain-byte total, encrypted blob-byte total, and trash count for the current user.

- [x] **Step 6: Verify cross-user isolation**

Create A and B in one test vault. Import a file as A. Start B session and assert B's `FileService.listChildren(root)` cannot return A's entry. Attempt to unwrap A's FDEK with B's UMK and assert authentication failure.

- [x] **Step 7: Commit**

```bash
mvn -Dtest=FileServiceTest test
mvn clean verify
git add src/main/java/com/fabianrodas/services/FileService.java src/main/java/com/fabianrodas/services/FileServiceException.java src/main/java/com/fabianrodas/models/WorkspaceStats.java src/test/java/com/fabianrodas/services/FileServiceTest.java
git commit -m "feat: add encrypted file and folder workflows"
```

---

# Phase 8 — Complete the desktop workspace UI

### Task 15: Split the oversized dashboard into focused child views

**Files:**
- Refactor: `src/main/resources/com/fabianrodas/encryptdrive/dashboard.fxml`
- Refactor: `src/main/java/com/fabianrodas/encryptdrive/DashboardController.java`
- Create: `src/main/resources/com/fabianrodas/encryptdrive/overview.fxml`
- Create: `src/main/resources/com/fabianrodas/encryptdrive/files.fxml`
- Create: `src/main/resources/com/fabianrodas/encryptdrive/trash.fxml`
- Create: `src/main/resources/com/fabianrodas/encryptdrive/profile.fxml`
- Create: `src/main/resources/com/fabianrodas/encryptdrive/vault-settings.fxml`
- Create controllers: `OverviewController.java`, `FilesController.java`, `TrashController.java`, `ProfileController.java`, `VaultSettingsController.java`
- Split CSS: retain `dashboard.css` for shell/sidebar and create `workspace.css` for child views.

> Execution note: Import Files, Export, and Permanently Delete are present but disabled here; Task 16 wires them with their dialogs. New Folder uses `DialogFactory.prompt` (added here). `Formats` holds the shared size/date/type/initials formatting. The old `"sidebar-nav-button active"` style class (one name containing a space) is fixed.

**Interfaces:**
- `DashboardController` owns sidebar and a `StackPane contentHost` only.
- Child controllers call services and do not manipulate the stage shell.

- [x] **Step 1: Reduce dashboard shell**

Sidebar items in this exact order: Overview, Files, Trash, Personal Profile, Vault Settings; then user card; Log Out; Close Vault.

- [x] **Step 2: Keep responsive behavior**

Retain the current dynamic sidebar width binding (230..290 px) and `ScrollPane fitToWidth=true`. Every child view gets a centered max content width only where appropriate; the Files table uses all available width.

- [x] **Step 3: Implement Overview**

Display current vault folder path, active file count, plaintext size, encrypted storage size, trash count, and a primary `Open Files` action. No placeholder `coming soon` controls remain.

- [x] **Step 4: Implement Files view**

Use breadcrumb bar + `TableView<ManifestEntry>` with Name, Type, Size, Modified. Actions: New Folder, Import Files, Export, Move to Trash. Double-click folder navigates into it. File double-click does not create a temporary plaintext preview; show/select Export action instead.

- [x] **Step 5: Implement Trash view**

Show deleted entries with Name, Type, Deleted At. Actions: Restore, Permanently Delete. Permanent delete requires confirmation.

- [x] **Step 6: Move profile logic into `ProfileController`**

Show full name/username and current/new/confirm password. Continue using the existing success/error visual treatment.

- [x] **Step 7: Implement Vault Settings**

Show root path, vault ID, format version, created date, change vault password, and Close Vault. Add copy-path button if desired only if it uses JavaFX clipboard and does not add dependencies.

- [x] **Step 8: Verify responsive UI manually**

At 1000x600 and maximized, verify every sidebar view, scroll behavior, tables, modals, and title-bar controls. Add screenshots only to issue/PR artifacts, not repository runtime resources.

- [x] **Step 9: Commit**

```bash
git add src/main/java/com/fabianrodas/encryptdrive src/main/resources/com/fabianrodas/encryptdrive src/main/resources/com/fabianrodas/css
git commit -m "refactor: split dashboard into encrypted workspace views"
```

### Task 16: Wire JavaFX file/folder dialogs and destructive confirmations

**Files:**
- Modify: `FilesController.java`
- Modify: `TrashController.java`
- Create: `src/main/java/com/fabianrodas/encryptdrive/DialogFactory.java`
- Create: `src/main/resources/com/fabianrodas/encryptdrive/confirmation-popup.fxml`
- Create: `src/main/resources/com/fabianrodas/css/confirmation-popup.css`

> Execution note: `DialogFactory` and the confirmation popup already existed (Task 10); this task adds the destructive tone. Import progress comes from a `LongConsumer` passed through `FileService.importFile` into `StreamingFileCryptoService.encrypt`. `FileService.exportTargets` computes Windows-safe, unique names for directory exports. While a background task runs, the sidebar (navigation, Log Out, Close Vault) is disabled so the vault cannot be closed mid-write.

**Interfaces:**
- UI dialogs return user intent only; services remain responsible for filesystem/crypto work.

- [x] **Step 1: Import files**

Use JavaFX `FileChooser.showOpenMultipleDialog`. Run encryption on a background `Task` so the JavaFX thread stays responsive. Disable conflicting actions while work is active and show per-operation progress based on source bytes processed.

- [x] **Step 2: Export files/folders**

Use `FileChooser` for a single file and `DirectoryChooser` for folders/multiple files. Before writing plaintext, show: `Exported files are not encrypted by EncryptDrive at the selected destination.`

- [x] **Step 3: Add consistent confirmation popup**

Reuse visual conventions from `success-popup.fxml`. Permanent delete text must name the selected logical item and state that recovery through EncryptDrive will no longer be possible.

- [x] **Step 4: Handle cancellation cleanly**

Canceling any chooser or popup performs no persistence operation and leaves selection/state unchanged.

- [x] **Step 5: Commit**

```bash
mvn clean verify
git add src/main/java/com/fabianrodas/encryptdrive src/main/resources/com/fabianrodas/encryptdrive src/main/resources/com/fabianrodas/css
git commit -m "feat: connect workspace actions to encrypted storage"
```

---

# Phase 9 — Failure recovery and integrity behavior

### Task 17: Add corruption classification, backup recovery, and cleanup of partial files

**Files:**
- Create: `src/main/java/com/fabianrodas/services/RecoveryService.java`
- Modify: `VaultService.java`
- Modify: `UserRegistryRepository.java`
- Modify: `ManifestRepository.java`
- Modify: `BlobRepository.java`
- Test: `src/test/java/com/fabianrodas/services/RecoveryServiceTest.java`
- Test: `src/test/java/com/fabianrodas/integration/CorruptionIntegrationTest.java`

> Execution note: backup fallback lives in `BackupRotator.recover` (next to `rotate`), and `UserRegistryRepository.load` and `ManifestRepository.load` use it, so repositories never depend on services. `RecoveryService` exposes the one-shot recovery notice (shown on Login, Overview, Files, and Trash) and `cleanStalePartials`, which `VaultService.unlockVault` runs. `BlobRepository` only contributes its name constants. `vault.json` is deliberately not backed up: an old copy would keep an old vault password working.

**Interfaces:**
- Recovery is automatic only for metadata backup generations whose AES-GCM authentication succeeds.
- File-content corruption is reported; no attempt is made to guess or repair ciphertext.

- [x] **Step 1: Write corruption matrix tests**

Mutate separately: `vault.json` syntax, wrapped RMK ciphertext, `users.enc`, current manifest, backup manifest, blob body/tag. Assert each maps to the correct domain/UI outcome and no unverified plaintext is returned.

- [x] **Step 2: Implement metadata fallback**

If current `users.enc` or manifest fails authenticated decrypt, test `.1`, `.2`, `.3` in order. If one authenticates and parses correctly, open read-only long enough to replace the current file atomically with the valid backup, then continue normally and notify the user that metadata was recovered from backup.

- [x] **Step 3: Clean stale partial files safely**

At vault open, scan only `storage/blobs/**/*.part` and metadata same-directory temp naming conventions created by EncryptDrive. Delete partials older than 24 hours. Never delete unknown user files outside EncryptDrive-controlled directories.

- [x] **Step 4: Verify and commit**

```bash
mvn -Dtest=RecoveryServiceTest,CorruptionIntegrationTest test
mvn clean verify
git add src/main/java/com/fabianrodas/services src/main/java/com/fabianrodas/repositories src/test/java/com/fabianrodas
git commit -m "feat: add authenticated metadata recovery"
```

---

# Phase 10 — Packaging, CI, and release readiness

### Task 18: Produce a portable Windows app-image with bundled runtime

**Files:**
- Modify: `pom.xml`
- Create: `scripts/package-windows.ps1`
- Create: `scripts/verify-portable-package.ps1`
- Modify: `.gitignore`

> Execution note: `mvn clean verify` already runs the `package` phase, which copies the runtime modules, so the script does not call `dependency:copy-dependencies` again. jlink needs `--ignore-signing-information` because Bouncy Castle is a signed modular JAR, so `--jlink-options` restates jpackage's defaults plus that flag. `verify-portable-package.ps1 -Launch` starts the image with no `JAVA_HOME` and a system-only `PATH` and waits for the window of the launcher's child process (verified 2026-09-30). `.gitignore` needed no change. Step 4 (a clean Windows VM running the full flows) is still to be done by hand.

**Interfaces:**
- Produces: `target/dist/EncryptDrive/EncryptDrive.exe` plus bundled runtime.

- [x] **Step 1: Copy modular runtime dependencies during package**

Configure `maven-dependency-plugin` to copy runtime dependencies to `target/modules` during `package`. Copy the application JAR there too.

- [x] **Step 2: Implement the packaging script**

The script runs:

```powershell
$ErrorActionPreference = "Stop"
mvn clean verify package
mvn dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=target/modules"
Copy-Item "target/EncryptDrive-1.0-SNAPSHOT.jar" "target/modules/" -Force
Remove-Item "target/dist" -Recurse -Force -ErrorAction SilentlyContinue
jpackage `
  --type app-image `
  --name EncryptDrive `
  --dest target/dist `
  --app-version 1.0.0 `
  --vendor "Fabian Rodas" `
  --module-path target/modules `
  --module "com.fabianrodas.encryptdrive/com.fabianrodas.encryptdrive.App"
```

Do not use `--win-console` so the GUI application does not open a console window.

- [x] **Step 3: Implement package verification script**

Assert `target/dist/EncryptDrive/EncryptDrive.exe` exists, `runtime/` exists inside the image, and no path in the package points to the developer's JDK installation.

- [ ] **Step 4: Test on a clean Windows environment**

Use a Windows VM or machine without a separately installed Java runtime. Copy only `target/dist/EncryptDrive` and verify create vault, reopen vault, register, login, import/export 1 file.

- [x] **Step 5: Commit**

```bash
git add pom.xml scripts .gitignore
git commit -m "build: add portable Windows app-image packaging"
```

### Task 19: Add continuous verification and final documentation

**Files:**
- Create: `.github/workflows/build.yml`
- Replace: `README.md`
- Create: `docs/SECURITY.md`
- Create: `docs/VAULT_FORMAT.md`
- Update: `docs/testing/manual-ui.md`

**Interfaces:**
- Pull requests run `mvn -B clean verify` on Windows with JDK 21.

- [x] **Step 1: Add GitHub Actions build**

Use `windows-latest`, `actions/checkout`, `actions/setup-java` with Temurin 21 and Maven cache, then:

```yaml
- name: Verify
  run: mvn -B clean verify
```

Do not commit generated vaults, account data, or package output as workflow artifacts. Package artifacts may be published only from explicit tagged release workflows added in a future release process.

- [x] **Step 2: Rewrite README to match reality**

README must cover: purpose, screenshots section without embedded private data, local-only architecture, create/open vault flow, user flow, file operations, portable build command, project structure, test command, no-password-recovery warning, OneDrive/USB behavior, and known threat-model limitations.

- [x] **Step 3: Write `docs/SECURITY.md`**

Document exact KDF parameters, AES-GCM rules, key hierarchy, AAD formats, password-loss behavior, exported plaintext warning, malware/admin limitation, and single-writer OneDrive limitation.

- [x] **Step 4: Write `docs/VAULT_FORMAT.md`**

Document `vault.json`, `users.enc`, manifest schema, blob sharding, format version policy, and rule that future breaking format changes increment `formatVersion`.

- [ ] **Step 5: Full verification**

> Status (2026-09-30): `mvn clean verify` (166 tests, 1 opt-in skipped), `mvn javafx:run`, `package-windows.ps1`, and `verify-portable-package.ps1 -Launch` all pass. Matrix items 1 and 4-13 are covered by automated tests (see docs/testing/manual-ui.md). Items 2 (USB drive), 3 (live OneDrive sync), and 14 (clean Windows VM) need hardware and remain manual.

Run:

```bash
mvn clean verify
mvn javafx:run
powershell -ExecutionPolicy Bypass -File scripts/package-windows.ps1
powershell -ExecutionPolicy Bypass -File scripts/verify-portable-package.ps1
```

Manual acceptance matrix:

1. Create vault on normal local folder.
2. Create vault on removable USB drive.
3. Create vault inside a OneDrive-synchronized local folder while OneDrive is online; confirm only ciphertext metadata/blobs are sync-visible.
4. Register users A and B.
5. Import a known file as A; logout; B cannot list/export it.
6. Re-login as A; export; SHA-256 equals source.
7. Change A password; old password fails, new password retains file access.
8. Change vault password; old vault password fails, new password preserves both users and files.
9. Trash, restore, permanent delete.
10. Tamper with a copy of a blob and confirm export fails without final plaintext output.
11. Attempt to open vault in two processes; second process is rejected as busy.
12. Close Vault; verify login cannot proceed until vault is reopened.
13. Test all primary screens at 1000x600 and maximized.
14. Run packaged app-image on Windows without system Java.

- [x] **Step 6: Final release commit**

```bash
git add .github README.md docs
git commit -m "docs: finalize EncryptDrive 1.0 architecture and usage"
```

---

# Final Definition of Done

Codex must not mark the plan complete until every item below is true:

- [x] `mvn clean verify` passes from a clean checkout.
- [x] No `data/users.json`, plaintext user registry, plaintext manifest, or plaintext vault-local file names exist in the repository or generated vault format.
  - Note: the file is untracked and ignored, but it is still in the history of the public remote (commits `0030c7c`, `9d866c1`); history was not rewritten.
- [x] Raw vault inspection does not reveal registered user identity or logical file/folder names.
- [x] Vault password unlock and user password login are separate key-unwrapping layers.
- [x] Another registered user cannot decrypt another user's files.
- [x] User password change does not re-encrypt file blobs.
- [x] Vault password change does not re-encrypt users/manifests/file blobs.
- [x] Every encrypted payload is authenticated with AES-GCM and context-specific AAD.
- [x] Import/export is streaming and tested with large files.
- [x] Export failure or ciphertext tampering leaves no final plaintext output.
- [x] All metadata writes are atomic and backed up three generations.
  - Note: `vault.json` is written atomically but deliberately not backed up (the spec requires backups for `users.enc` and manifests; an old header would keep an old vault password working).
- [x] Vault locking prevents two local processes from writing simultaneously.
- [x] Overview, Files, Trash, Profile, and Vault Settings contain real data and no disabled `coming soon` controls.
- [x] Login/Register/Dashboard/Vault Selection remain usable at 1000x600 and maximized.
- [x] Logout destroys user session key material.
- [x] Close Vault destroys vault key material and releases the lock.
- [ ] Windows portable app-image bundles its runtime and runs without separately installed Java.
  - Verified with Java removed from `PATH` and `JAVA_HOME` (`verify-portable-package.ps1 -Launch`); still needs a run on a clean Windows VM (Task 18 Step 4).
- [x] README, SECURITY, and VAULT_FORMAT documentation match the implementation.

# Recommended Codex Execution Order

Use one task per review/commit boundary. Do not batch multiple tasks into one commit. After each task, run the narrow test command listed in that task and then `mvn clean verify`. If a task uncovers a format/interface problem that changes a contract consumed by later tasks, update this plan and the design spec in the same commit before continuing so downstream tasks never implement against stale contracts.
