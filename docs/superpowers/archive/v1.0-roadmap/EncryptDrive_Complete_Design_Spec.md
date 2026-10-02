# EncryptDrive Complete Product Design

## Purpose

EncryptDrive is a Windows-first, local-first JavaFX desktop application that creates or opens a portable encrypted vault on any user-selected local filesystem location, including an internal disk, removable USB drive, or a folder synchronized by a provider such as OneDrive. Core operation must not depend on an HTTP backend, remote database, cloud API, telemetry service, or Internet connection.

The final product must allow multiple local accounts inside one vault while guaranteeing that each account can decrypt only its own protected files. All user-identifying metadata, user authentication material, logical file metadata, and file contents must be encrypted at rest. The only plaintext metadata permitted in the vault is the minimum technical header required to identify the vault format and derive/unlock its cryptographic keys.

## Current Repository Audit

The uploaded repository is a useful UI/authentication prototype, not yet the complete EncryptDrive product.

### What already works

- Java 21 / JavaFX 21 project structure with Maven.
- Login and registration screens with responsive FXML/CSS.
- Custom undecorated window controls, maximize/minimize/close, and restore-on-drag behavior.
- Local registration and login flow.
- Password hashing with PBKDF2-HMAC-SHA256 and per-user random salts.
- Session state in memory.
- Dashboard shell with overview/profile views.
- Password change flow that verifies the current password first.
- Registration success popup.
- Initial `Vault` model plus `VaultService` and `VaultSessionService` skeletons.

### Critical gaps found in the uploaded ZIP

1. `App.java` still starts at `login.fxml`; no vault-selection screen exists in the uploaded source tree.
2. `UserController` still reads and writes `data/users.json` from the application working directory instead of using `VaultSessionService`.
3. `data/users.json` contains a real development account record and is not ignored by `.gitignore`; user metadata, username, password hash, and salt are visible at rest.
4. `VaultService` creates plaintext `.encryptdrive/users.json` and is not connected to the UI flow.
5. No encrypted registry, no vault-password unlock flow, and no vault key hierarchy exist.
6. No file encryption, file import, file export/decryption, folder model, trash, or per-user encrypted manifest exists.
7. Dashboard vault/file counters are hard-coded placeholders and the vault setup control is disabled.
8. No automated tests exist under `src/test`.
9. Maven configuration is internally inconsistent: project properties target Java 21 while `maven-compiler-plugin` explicitly uses `<release>11</release>`.
10. `target/` build output is present in the ZIP despite being ignored; repository hygiene needs cleanup.
11. `README.md` is stale: it marks login/dashboard as unfinished even though those flows exist, and it documents plaintext `users.json` as the active persistence model.
12. `SessionService` stores the entire `User` model, including hash/salt fields that do not belong in UI session state.
13. The current password APIs use `String` deeply into the security layer. JavaFX necessarily exposes a `String` from `PasswordField`, but the value should be converted to `char[]` at the controller/service boundary and cleared as soon as possible.
14. There is no vault lock, so two processes can write the same vault concurrently and corrupt metadata.
15. There is no corruption/tamper handling or atomic recovery strategy beyond the current JSON write helper.
16. There is no portable runtime packaging; another PC still needs a suitable Java environment unless a runtime is bundled.

## Product Scope for Version 1.0

### Included

- Create a vault in any writable local directory.
- Open an existing vault.
- Vault password required to unlock encrypted account metadata.
- Register multiple local users after a vault is unlocked.
- Login with username + user password.
- User password change without re-encrypting every file.
- Change vault password without re-encrypting every file.
- Per-user encrypted logical filesystem with files and folders.
- Import one or more files into the active logical folder.
- Create logical folders.
- Navigate folders with breadcrumbs.
- Export/decrypt files or folders to a user-selected destination.
- Soft delete to encrypted trash.
- Restore from trash.
- Permanently delete trashed data.
- Dashboard counters based on real encrypted data.
- Profile view.
- Vault settings view with path, vault ID, format version, change vault password, close vault.
- Local process-level vault locking.
- Metadata backup rotation for registry and manifests before replacement.
- Portable Windows `app-image` package containing its own Java runtime.
- Offline operation with no network dependency.

### Explicitly excluded from 1.0

- HTTP backend.
- Cloud accounts or cloud authentication.
- Cross-device simultaneous editing of the same synchronized vault.
- Remote password recovery.
- Sharing/delegating a file from one local account to another.
- Secure preview/open-in-place using decrypted temporary files.
- Filesystem-driver integration or mounting the vault as a Windows drive letter.
- Claims of secure deletion from SSD/flash media; physical deletion cannot be guaranteed by an application because of wear leveling and filesystem behavior.

## Threat Model

EncryptDrive 1.0 is designed to protect data at rest if a vault directory or removable drive is copied, stolen, synchronized to a third-party storage provider, or inspected directly through the operating system.

### Protected against

- Reading usernames, full names, user key envelopes, file names, folder names, or file contents directly from vault files while the vault is locked.
- Reading another local user's file content without that user's password, even after the shared vault has been unlocked.
- Silent modification of encrypted metadata or file content: AES-GCM authentication must fail on tampering.
- Accidental partial metadata writes: temp-file + atomic-replace semantics are required.
- Opening the same vault twice in two EncryptDrive processes on the same machine.

### Not protected against

- Malware, keyloggers, debuggers, memory inspection, or an administrator controlling the running OS.
- A malicious modified EncryptDrive binary running after a user provides passwords.
- Deletion of ciphertext by a filesystem-level attacker. Encryption can detect modification but cannot prevent external deletion.
- Plaintext source files left outside the vault after an import operation.
- Plaintext files intentionally exported by the user.
- Concurrent writes from two different computers to a OneDrive-synchronized vault. Version 1.0 must document single-writer usage.

## Cryptographic Architecture

### Algorithms

- Password KDF: Argon2id via Bouncy Castle `bcprov-jdk18on`.
- Vault KDF parameters: memory 65536 KiB, iterations 3, parallelism 1, output 32 bytes, random 16-byte salt.
- User KDF parameters: same defaults as vault KDF, stored per user so the format can evolve.
- Authenticated encryption: AES-256-GCM with a fresh random 12-byte nonce for every encryption operation and a 128-bit authentication tag.
- Random generation: `SecureRandom`.
- Keys: random 32-byte AES keys.
- Encoded binary fields in JSON envelopes: Base64.

### Key hierarchy

1. The vault password derives a Vault Key Encryption Key (VKEK) with Argon2id.
2. Vault creation generates a random 256-bit Registry Master Key (RMK).
3. `vault.json` stores only technical metadata plus the RMK encrypted/wrapped under the VKEK with AES-GCM.
4. The RMK encrypts `users.enc`.
5. Each new user gets a random 256-bit User Master Key (UMK).
6. The user password derives a User Key Encryption Key (UKEK) with Argon2id.
7. The UMK is wrapped under the UKEK using AES-GCM; successful unwrap is also the user-password authentication check. A separate password hash is unnecessary in the final format.
8. Each user's encrypted manifest is encrypted under that user's UMK.
9. Every imported file gets a random 256-bit File Data Encryption Key (FDEK).
10. The FDEK is wrapped under the user's UMK and stored only inside that user's encrypted manifest.
11. File bytes are encrypted with the FDEK using AES-GCM and stored as an opaque blob.

This hierarchy allows changing a vault password by rewrapping only the RMK, and changing a user password by rewrapping only the UMK. Existing file blobs do not need to be re-encrypted.

### Associated authenticated data

Every AES-GCM operation must bind ciphertext to its context using UTF-8 AAD:

- Wrapped RMK: `EncryptDrive|vault-key|v1|<vaultId>`
- User registry: `EncryptDrive|users|v1|<vaultId>`
- Wrapped UMK: `EncryptDrive|user-key|v1|<vaultId>|<userId>`
- User manifest: `EncryptDrive|manifest|v1|<vaultId>|<userId>`
- Wrapped FDEK: `EncryptDrive|file-key|v1|<vaultId>|<userId>|<fileId>`
- File content: `EncryptDrive|file|v1|<vaultId>|<userId>|<fileId>`

## Final Vault Layout

```text
<VaultRoot>/
├── .encryptdrive/
│   ├── vault.json
│   ├── users.enc
│   ├── lock
│   ├── manifests/
│   │   ├── <random-manifest-id>.enc
│   │   └── ...
│   └── backups/
│       ├── users.enc.1
│       ├── users.enc.2
│       ├── users.enc.3
│       └── manifests/
│           └── <manifest-id>.enc.1..3
└── storage/
    └── blobs/
        ├── 00/
        ├── 01/
        └── ...
```

Blob names are random UUIDs with `.edv` extension. Use the first two hexadecimal characters of the UUID without dashes as a shard directory to avoid an unbounded single directory. The physical layout never contains the original file name, logical folder name, username, or full name.

## Plaintext `vault.json` Contract

`vault.json` is not a user database. It contains only what is required to identify and unlock the vault.

```json
{
  "formatVersion": 1,
  "vaultId": "UUID",
  "createdAt": "ISO-8601 UTC timestamp",
  "kdf": {
    "algorithm": "Argon2id",
    "memoryKiB": 65536,
    "iterations": 3,
    "parallelism": 1,
    "salt": "base64"
  },
  "wrappedRegistryKey": {
    "algorithm": "AES/GCM/NoPadding",
    "nonce": "base64",
    "ciphertext": "base64"
  }
}
```

The selected directory name itself is visible to the OS and cannot be hidden by EncryptDrive. No user names or file names are placed in `vault.json`.

## Encrypted User Registry Contract

After decryption, `users.enc` contains JSON equivalent to:

```json
{
  "formatVersion": 1,
  "users": [
    {
      "userId": "UUID",
      "fullName": "Example User",
      "username": "ExampleUser",
      "normalizedUsername": "exampleuser",
      "createdAt": "ISO-8601 UTC timestamp",
      "manifestId": "random UUID",
      "userKdf": {
        "algorithm": "Argon2id",
        "memoryKiB": 65536,
        "iterations": 3,
        "parallelism": 1,
        "salt": "base64"
      },
      "wrappedUserMasterKey": {
        "algorithm": "AES/GCM/NoPadding",
        "nonce": "base64",
        "ciphertext": "base64"
      }
    }
  ]
}
```

The whole document is AES-GCM encrypted under the RMK before it reaches disk.

## Encrypted Manifest Contract

Each account has one encrypted manifest. After decryption it contains:

```json
{
  "formatVersion": 1,
  "userId": "UUID",
  "rootFolderId": "UUID",
  "entries": [
    {
      "entryId": "UUID",
      "kind": "FILE",
      "parentId": "UUID",
      "name": "budget.xlsx",
      "createdAt": "ISO-8601 UTC timestamp",
      "modifiedAt": "ISO-8601 UTC timestamp",
      "deletedAt": null,
      "originalParentId": null,
      "plainSize": 12345,
      "blobId": "UUID",
      "wrappedFileKey": {
        "algorithm": "AES/GCM/NoPadding",
        "nonce": "base64",
        "ciphertext": "base64"
      },
      "contentNonce": "base64"
    }
  ]
}
```

Folder entries omit file-only fields. Trash is represented by `deletedAt != null`; file blobs remain encrypted in place until permanent deletion.

## Application Architecture

Keep the existing JavaFX/FXML desktop architecture but enforce clearer boundaries.

### `com.fabianrodas.encryptdrive`

FXML controllers and `App`. UI controllers may validate presentation-level input and map service results to user-facing messages, but must not contain cryptographic or filesystem persistence logic.

### `com.fabianrodas.models`

Serializable DTOs only: vault headers, key envelopes, user records, manifests, manifest entries, view-neutral result records.

### `com.fabianrodas.security`

Cryptographic primitives and sensitive-memory helpers. No JavaFX dependencies and no direct knowledge of screen flow.

### `com.fabianrodas.repositories`

Atomic file persistence for vault header, encrypted registry, manifests, blobs, and backups. No UI dependencies.

### `com.fabianrodas.services`

Business workflows: vault lifecycle, authentication, session lifecycle, file/folder operations, trash, lock management, dashboard statistics.

### `com.fabianrodas.utils`

Window behavior and non-domain helpers only.

## UI Flow

### Startup

```text
App
→ Vault Selection
   → Create Vault
      → set vault password
      → vault unlocked
      → Register first user
      → Login
   → Open Existing Vault
      → choose vault directory
      → enter vault password
      → Login
```

### Authenticated workspace

```text
Dashboard shell
├── Overview
├── Files
├── Trash
├── Personal Profile
└── Vault Settings
```

### Logout versus Close Vault

- `Log Out`: clear user session and UMK, keep the vault unlocked, return to Login so another local user can sign in.
- `Close Vault`: log out if necessary, clear RMK + UMK, release the vault lock, return to Vault Selection.
- Application close: clear session key material and release the lock in a `Stage.setOnCloseRequest` handler and `Application.stop()` fallback.

## File Operations

### Import

- User selects one or more source files.
- Generate file ID, blob ID, FDEK, content nonce, and FDEK-wrap nonce.
- Stream source bytes through AES-GCM to `storage/blobs/<shard>/<blobId>.edv.part`.
- Flush and close the stream.
- Atomically rename `.part` to `.edv`.
- Only then update the encrypted manifest atomically.
- If manifest update fails, delete the newly-created blob.
- Import copies the source; it never deletes the plaintext source in version 1.0.

### Export

- User explicitly chooses an output destination.
- Unwrap FDEK with the UMK.
- Stream-decrypt to `<destination>.part`.
- Verify GCM tag by completing the cipher stream.
- Atomically rename the partial file to the requested output name only after successful authentication.
- If authentication fails, delete the partial plaintext output.

### Delete / Trash

- Soft delete modifies only encrypted manifest metadata.
- Restore clears `deletedAt` and returns the entry to `originalParentId` if valid; otherwise root.
- Permanent deletion removes the encrypted blob after manifest validation and then removes the entry from the manifest.

## Concurrency and Atomicity

- Acquire an exclusive `FileChannel.tryLock()` on `.encryptdrive/lock` before treating a vault as open.
- If the lock cannot be acquired, show `This vault is already open in another EncryptDrive process.` and refuse write access.
- Lock lifetime equals vault session lifetime.
- All metadata replacement uses same-directory temp files and `ATOMIC_MOVE` where supported, with `REPLACE_EXISTING` fallback.
- Before replacing `users.enc` or a manifest, rotate three encrypted backups in `.encryptdrive/backups`.
- Blobs are immutable after successful import. A changed file is represented by a new blob + manifest update, followed by deletion of the old blob only after the new manifest is committed.

## Security-sensitive UX Rules

- Never display or log raw cryptographic exceptions, keys, salts, nonces, password values, or decrypted metadata payloads.
- Wrong vault password and corrupted RMK envelope produce a generic unlock failure in the UI; structured logs are not written in version 1.0.
- Wrong user password produces `Invalid username or password.`
- Vault creation warns: `EncryptDrive cannot recover a lost vault password.`
- User registration warns: `EncryptDrive cannot recover a lost account password.`
- Export dialog explains that exported files are plaintext outside EncryptDrive.
- OneDrive/local-sync folders are allowed, but the settings screen states that the synced provider receives ciphertext and that the same vault must not be edited simultaneously from multiple computers.

## Build and Distribution

- Compile and test with Java 21.
- JavaFX remains version 21.
- Gson remains 2.14.0 unless an incompatibility is discovered during implementation.
- Use Bouncy Castle `bcprov-jdk18on` 1.85.2 for Argon2id.
- Use JUnit Jupiter 6.1.3 for tests.
- Use Maven Compiler Plugin 3.15.0 with `<release>21</release>`.
- Use Maven Surefire Plugin 3.5.5.
- Provide a Windows packaging script using JDK 21 `jpackage --type app-image`; output must contain an `.exe` plus bundled runtime and run on a clean Windows machine without a separately installed JDK/JRE.

## Acceptance Criteria

1. A fresh app opens Vault Selection, not Login.
2. A new vault can be created on an internal disk or removable drive.
3. Directly opening vault files in a text editor reveals no username, full name, original file name, folder name, file content, password hash, or user salt.
4. Correct vault password unlocks the registry; incorrect password does not.
5. Two users can register in one vault.
6. User A can import a file and export it byte-for-byte identical.
7. User B cannot list User A's file in the UI and cannot decrypt User A's blob with User B's UMK.
8. Tampering with `users.enc`, a manifest, wrapped key, or blob causes authenticated decryption failure and never produces trusted plaintext.
9. Changing a user password preserves access to all existing user files without rewriting blobs.
10. Changing the vault password preserves all users and files without rewriting manifests or blobs.
11. Logout clears user key material and returns to Login.
12. Close Vault clears both user and vault key material, releases lock, and returns to Vault Selection.
13. The Files view supports folder creation, import, navigation, export, trash, restore, and permanent delete.
14. Dashboard file count and storage usage are derived from the current user's encrypted manifest/blob sizes.
15. The UI works at the 1000x600 minimum and when maximized.
16. `mvn clean verify` passes.
17. The repository contains no plaintext development user database or generated `target/` artifacts.
18. The portable Windows app-image launches without a system-wide Java installation.
