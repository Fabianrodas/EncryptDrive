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

- Vault and account passwords need at least 12 characters. Passwords are used
  exactly as entered: they are not trimmed or Unicode-normalized. Accounts
  created by pre-release builds with shorter passwords still work until the
  account owner changes the password.
- **There is no password recovery.** EncryptDrive stores no hint, reset key, or
  copy of any password. A lost vault password makes the whole vault unreadable;
  a lost account password makes that account's files unreadable. The app warns
  about this when a vault or an account is created.
- Passwords are read into `char[]`, the input fields are cleared, and the
  arrays are zeroed after key derivation. Keys live in zeroable byte arrays.
  This is best effort: the JavaFX `PasswordField` holds a `String` until it is
  cleared, and the JVM and JCE may keep transient copies. Memory inspection is
  outside the threat model.

### Account password change

Changing an account password wraps the same user master key (UMK) with a key
derived from the new password. It does not re-encrypt the manifest or file
blobs. The active registry and all three registry backups are replaced with
the new registry, so the old password cannot open any retained registry
generation in the current vault. The backups are written before the active
registry; if the operation is interrupted before the active registry is
replaced, the old password remains valid and the change must be repeated.

This does not revoke a UMK that someone already extracted from memory. It also
does not rewrite copies of the vault kept outside EncryptDrive. For example,
OneDrive version history, your own backups, or an attacker's earlier copy may
still contain a registry that opens with the old password.

### Vault password change

Changing the vault password wraps the same registry master key with a new salt
and derived key, and replaces `vault.json`. There are no `vault.json` backups,
so an older header copy is not retained by EncryptDrive. A copy of the whole
vault made outside EncryptDrive before the change can still be opened with the
old vault password.

## Sessions

- **Log Out** destroys the account's UMK and keeps the vault unlocked for the
  next account.
- **Close Vault** also destroys the RMK and releases the vault lock. Closing
  the window, or quitting the app, does the same.
- While a file operation runs, closing the window, Alt+F4, Log Out, and Close
  Vault are blocked with: "Please wait for the current file operation to
  finish before closing EncryptDrive." The sidebar is disabled too. Once the
  session closes, EncryptDrive wipes the account and vault keys from memory
  and releases the vault lock.

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
- Folder import never follows symbolic links or junctions, and refuses a
  source folder that contains the vault.
- Files are never opened as temporary plaintext previews.
- Export is the only operation that writes plaintext, only to a destination
  you choose, after the warning "Exported files are not encrypted by
  EncryptDrive at the selected destination." Exporting into the vault folder
  is refused. Each file is decrypted into a temporary `.part` file beside the
  destination and renamed only after the GCM tag verifies. If an export is
  interrupted, a plaintext `<name>.<digits>.part` file can remain beside the
  chosen destination; delete it. If verification fails, the partial file is
  deleted and nothing is exported.

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
- `vault.json` is limited to 256 KiB; `users.enc` and each registry backup to
  16 MiB; each encrypted manifest and each manifest backup to 64 MiB. Limits
  are checked before reading and before writing. Oversized or malformed
  metadata is treated as damage; recovery uses an authentic backup when one is
  available. The only contractual size limit is that one account's encrypted
  manifest may not exceed 64 MiB; the other values are defensive metadata
  bounds.
- Registry password changes replace the active registry and every backup with
  the new state. See [Account password change](#account-password-change) for
  the behavior of interrupted changes and copies outside EncryptDrive.

## Permanent deletion

Permanent deletion first removes the selected entries from the encrypted
manifest and replaces all three manifest backups with that same state. It then
deletes the entries' encrypted blobs. Blob IDs still awaiting removal are kept
in the encrypted manifest's `pendingDeletions` journal. Cleanup is retried
after login, before and after later permanent deletions, and when Empty Trash is
used. A failed removal can leave orphaned ciphertext, but metadata never points
to a blob that has already been deleted. SSDs and flash drives do not guarantee
physical erasure of deleted data.

## Synced folders and USB drives

- A vault can live on an internal disk, a removable drive, or a folder synced by
  OneDrive or a similar client. The sync provider only ever receives the files
  described above: ciphertext plus the plaintext header.
- EncryptDrive is single-writer. The lock file stops two processes on one
  computer, but it cannot coordinate two computers. Never open the same synced
  vault on two computers at once; wait for sync to finish before switching.

## Distribution

- The per-user Windows installer and the portable ZIP both bundle their own
  Java runtime and need no installed Java. Installing or uninstalling the app
  never creates, moves, or deletes a vault. The installer lets you choose its
  own install folder and shortcuts; uninstalling removes the app and shortcuts
  but leaves vault folders untouched.
- Release artifacts are not code-signed. Windows SmartScreen may show an
  unknown publisher; verify the SHA-256 value in `SHA256SUMS.txt` before
  choosing **More info → Run anyway**.
- JavaFX writes native libraries to `%USERPROFILE%\.openjfx\cache` on each
  computer where EncryptDrive runs. The portable edition leaves this cache on
  the host computer.
- The Bouncy Castle JAR is signed, but jlink cannot link signed modular JARs,
  so the image is built with `--ignore-signing-information`. EncryptDrive uses
  Bouncy Castle's lightweight API directly and never registers it as a JCE
  provider, so the provider signature is not needed.

## Repository history

An early development build stored a plaintext account list in
`data/users.json`, including account identifiers, usernames, password hashes,
full names, and salts. Current builds do not use that file, and it is not
included in release packages. Before v1.0.0, the repository history was
rewritten to remove the historical development user database from reachable
commits on `main` and the release branch. This rewrite does not erase copies in
forks, clones, caches, or copies already made elsewhere. Treat any password
used for one of those development accounts as compromised, especially if it
was reused anywhere else.
