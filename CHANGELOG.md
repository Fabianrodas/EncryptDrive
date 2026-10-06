# Changelog

All notable changes to EncryptDrive. Versions follow MAJOR.MINOR.PATCH; release
candidates are Git tags `vX.Y.Z-rc.N` of the same version.

## [1.0.0] - 2026-10-05

First stable release.

### Added

- Encrypted vaults on local, removable, or synced folders, with several
  cryptographically isolated accounts per vault.
- Files: import files or whole folders (links and junctions are never
  followed), export, new folder, rename, move, search, Trash, restore,
  permanent delete, and Empty Trash.
- Windows installer (per-user, choose the install folder, Start Menu entry,
  optional desktop shortcut, normal uninstall) and a portable ZIP; both bundle
  Java.
- Application version shown in Vault Settings.

### Security

- Argon2id key derivation and AES-256-GCM with contextual associated data for
  all metadata and file content; no password hashes stored.
- Permanent deletion is crash-safe: entries leave the manifest and every
  backup before encrypted data is removed, using an encrypted deletion journal.
- Changing an account password replaces every registry backup, so the old
  password no longer opens any retained copy.
- Account and vault passwords need at least 12 characters.
- Vault metadata is size-limited and strictly validated before it is read.
- EncryptDrive cannot be closed, logged out, or have its vault closed while a
  file operation runs.
- Plaintext exports into the vault folder are refused.
