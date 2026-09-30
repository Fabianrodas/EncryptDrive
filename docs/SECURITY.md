# EncryptDrive security model

EncryptDrive protects data **at rest**: a vault folder that is copied, stolen,
synced to a cloud provider, or inspected through the operating system reveals
no account names and no file names or contents. It runs entirely offline:
there is no server, account service, telemetry, or network access.

The on-disk format is specified in [VAULT_FORMAT.md](VAULT_FORMAT.md).

## Threat model

Protected against:

- Reading usernames, full names, key envelopes, file names, folder names, or
  file contents from the vault files while the vault is closed.
- One account reading another account's files, even while the vault is
  unlocked: each account's files are keyed from that account's password.
- Silent modification of metadata or file content. Every ciphertext is
  authenticated (AES-GCM) and bound to its context (AAD); a changed byte makes
  decryption fail instead of producing plaintext.
- Partially written metadata after a crash or power loss (temp file, flush,
  atomic rename, three backup generations).
- The same vault being opened by two EncryptDrive processes on one computer.

Not protected against:

- Malware, keyloggers, debuggers, memory inspection, or an administrator who
  controls the running system, or a modified EncryptDrive binary. Such an
  attacker sees passwords and plaintext as they are typed or shown.
- Deletion of the vault's files. Encryption detects modification but cannot
  stop someone with file access from deleting ciphertext. Keep backups of the
  whole vault folder.
- Plaintext outside the vault: import copies a file into the vault and leaves
  the original untouched, and export deliberately writes plaintext where you
  choose. Delete originals yourself if they must not remain. Secure deletion on
  SSDs and flash drives cannot be guaranteed by any application.
- Two computers writing to the same synced vault at the same time (see
  [Synced folders](#synced-folders-and-usb-drives)).

## Cryptography

| Purpose | Choice |
|---|---|
| Password key derivation | Argon2id v1.3 (Bouncy Castle), 65,536 KiB memory, 3 iterations, parallelism 1, 16-byte random salt, 32-byte output |
| Encryption | AES-256-GCM, fresh random 12-byte nonce per encryption, 128-bit tag |
| Randomness | `java.security.SecureRandom` for every key, salt, and nonce |
| Metadata AEAD | JDK `AES/GCM/NoPadding` |
| File content AEAD | Bouncy Castle streaming GCM with 64 KiB buffers, the same standard format as the JDK (the JDK buffers all ciphertext while decrypting, so it cannot stream large files) |

Argon2id parameters are stored with each salt. Values read from disk are
bounds-checked before use, because `vault.json` is not encrypted.

### Key hierarchy

```text
vault password ──Argon2id──▶ VKEK ──wraps──▶ RMK (random)   stored in vault.json
                                              │
                                              └─encrypts──▶ users.enc
account password ─Argon2id─▶ UKEK ──wraps──▶ UMK (random)   stored in users.enc
                                              │
                                              ├─encrypts──▶ the account's manifest
                                              └─wraps─────▶ FDEK (random, one per file)
                                                              │
                                                              └─encrypts──▶ file blob
```

- The vault password gates access to the account list. An account password
  gates that account's files. They are separate layers.
- Changing the vault password rewraps only the RMK (`vault.json` changes).
  Changing an account password rewraps only that UMK (`users.enc` changes).
  Manifests and file blobs are never re-encrypted.
- No password hash is stored. Unwrapping the UMK (or RMK) with a key derived
  from the entered password is the check, and GCM authentication failure is
  indistinguishable from "wrong password".
- A login for an unknown username performs the same Argon2id work as a wrong
  password, so response time does not reveal which usernames exist.

### Associated data

| Ciphertext | AAD |
|---|---|
| Wrapped RMK | `EncryptDrive\|vault-key\|v1\|<vaultId>` |
| User registry | `EncryptDrive\|users\|v1\|<vaultId>` |
| Wrapped UMK | `EncryptDrive\|user-key\|v1\|<vaultId>\|<userId>` |
| Manifest | `EncryptDrive\|manifest\|v1\|<vaultId>\|<userId>` |
| Wrapped FDEK | `EncryptDrive\|file-key\|v1\|<vaultId>\|<userId>\|<fileId>` |
| File content | `EncryptDrive\|file\|v1\|<vaultId>\|<userId>\|<fileId>` |

A ciphertext copied to another vault, account, or file fails authentication.

## Passwords

- Vault passwords need at least 12 characters; account passwords at least 8.
- **There is no password recovery.** EncryptDrive stores no hint, reset key, or
  copy of any password. A lost vault password makes the whole vault unreadable;
  a lost account password makes that account's files unreadable. The app warns
  about this when a vault or an account is created.
- Passwords are read into `char[]`, the input fields are cleared, and the
  arrays are zeroed after key derivation. Keys live in zeroable byte arrays.
  This is best effort: the JavaFX `PasswordField` holds a `String` until it is
  cleared, and the JVM and JCE may keep transient copies. Memory inspection is
  outside the threat model.

## Sessions

- **Log Out** destroys the account's UMK and keeps the vault unlocked for the
  next account.
- **Close Vault** also destroys the RMK and releases the vault lock. Closing
  the window, or quitting the app, does the same.
- Background work (imports, exports) disables Log Out and Close Vault until it
  finishes.

## What the vault reveals

The vault hides names and contents, but not everything:

- `vault.json` shows the vault id, its creation time, and the Argon2id
  parameters.
- The number of files in `manifests/` equals the number of accounts.
- Each blob is exactly 16 bytes larger than the file it holds, so file sizes
  and the number of files are visible, though not which account owns them.
- File system timestamps show when metadata or blobs were last written.
- The vault folder's own name is visible; choose a neutral one if that matters.

## Import, export, and previews

- Import encrypts a copy and never modifies or deletes the source file.
- Files are never opened as temporary plaintext previews.
- Export is the only operation that writes plaintext, only to a destination
  you choose, after the warning "Exported files are not encrypted by
  EncryptDrive at the selected destination." Each file is decrypted into a
  temporary `.part` file beside the destination and renamed only after the GCM
  tag verifies. If verification fails, the partial file is deleted and nothing
  is exported.

## Integrity and recovery

- Every change to `users.enc` or a manifest keeps the three previous versions as
  encrypted backups. If the current file fails authentication, the newest
  backup that authenticates is restored automatically and the app says so.
  Changes made after that backup are lost.
- Damaged file content is only reported. EncryptDrive never guesses or repairs
  ciphertext.
- `vault.json` is not backed up, so an old copy cannot keep an old vault
  password working. If it is damaged, the vault cannot be opened: keep an
  external backup of the vault folder.
- Registry backups can still contain an account key wrapped under that
  account's previous password until three more registry changes have happened.
  Someone with a copy of the vault and both the vault password and the old
  account password could use such a backup. The UMK itself never changes, so a
  password change does not revoke access for anyone who already had it.

## Synced folders and USB drives

- A vault can live on an internal disk, a removable drive, or a folder synced by
  OneDrive or a similar client. The sync provider only ever receives the files
  described above: ciphertext plus the plaintext header.
- EncryptDrive is single-writer. The lock file stops two processes on one
  computer, but it cannot coordinate two computers. Never open the same synced
  vault on two computers at once; wait for sync to finish before switching.

## Distribution

- The portable Windows app-image bundles its own Java runtime and needs no
  installed Java.
- The Bouncy Castle JAR is signed, but jlink cannot link signed modular JARs,
  so the image is built with `--ignore-signing-information`. EncryptDrive uses
  Bouncy Castle's lightweight API directly and never registers it as a JCE
  provider, so the provider signature is not needed.
- JavaFX extracts its native libraries into `%USERPROFILE%\.openjfx\cache`
  the first time it runs.
