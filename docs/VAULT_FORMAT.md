# EncryptDrive vault format (version 1)

This document describes every file EncryptDrive writes inside a vault folder.
It is the reference for compatibility: a change that older versions cannot
read must increase `formatVersion` (see [Versioning](#versioning)).

## Layout

```text
<VaultRoot>/
├── .encryptdrive/
│   ├── vault.json                      plaintext header (the only plaintext file)
│   ├── users.enc                       encrypted account registry
│   ├── lock                            empty file, locked while the vault is open
│   ├── manifests/
│   │   └── <manifestId>.enc            one encrypted manifest per account
│   └── backups/
│       ├── users.enc.1 .. users.enc.3
│       └── manifests/
│           └── <manifestId>.enc.1 .. .3
└── storage/
    └── blobs/
        └── <shard>/
            └── <blobId>.edv            encrypted file content
```

- `<manifestId>` and `<blobId>` are random UUIDs in canonical lowercase form.
  They are unrelated to user ids, file ids, and names.
- `<shard>` is the first two hexadecimal characters of the blob id
  (for `3f0b8c55-…`, the shard is `3f`).
- No path contains a username, full name, file name, or folder name. The name
  of `<VaultRoot>` itself is chosen by the user and is visible to the OS.

Files that exist only while a write is in progress:

| Pattern | Where | Meaning |
|---|---|---|
| `<name>.<digits>.tmp` | anywhere under `.encryptdrive/` | metadata being written; renamed over `<name>` when complete |
| `<blobId>.edv.part` | `storage/blobs/<shard>/` | blob being encrypted; renamed to `.edv` when complete |

Leftovers of both patterns older than 24 hours are deleted when the vault is
opened. Nothing else is ever deleted by that cleanup.

## Encrypted envelope

Every encrypted metadata value uses the same JSON envelope:

```json
{
  "version": 1,
  "algorithm": "AES/GCM/NoPadding",
  "nonce": "base64 of 12 random bytes",
  "ciphertext": "base64 of ciphertext followed by the 16-byte GCM tag"
}
```

- AES-256-GCM, a fresh random 96-bit nonce per encryption, 128-bit tag.
- Every envelope is bound to its context with associated data (AAD); see
  [Associated data](#associated-data).
- Readers reject envelopes whose `version` or `algorithm` differ, whose nonce
  is not 12 bytes, or whose ciphertext is shorter than the tag.

`users.enc` and every `manifests/<manifestId>.enc` file is exactly one
envelope serialized as JSON.

## `vault.json`

```json
{
  "formatVersion": 1,
  "vaultId": "canonical UUID",
  "createdAt": "ISO-8601 UTC instant, e.g. 2026-09-30T17:00:00.123Z",
  "kdf": {
    "algorithm": "Argon2id",
    "memoryKiB": 65536,
    "iterations": 3,
    "parallelism": 1,
    "salt": "base64 of 16 random bytes"
  },
  "wrappedRegistryKey": { "…envelope…" }
}
```

- `kdf` derives the vault key-encryption key (VKEK) from the vault password.
- `wrappedRegistryKey` is the 32-byte registry master key (RMK) encrypted
  under the VKEK with AAD `EncryptDrive|vault-key|v1|<vaultId>`.
- Changing the vault password replaces `vault.json` with a new salt and a new
  wrapping of the same RMK. Nothing else changes.
- `vault.json` has no backups on purpose: an old copy would keep an old vault
  password working.

## `users.enc`

An envelope encrypted under the RMK with AAD `EncryptDrive|users|v1|<vaultId>`.
Decrypted:

```json
{
  "formatVersion": 1,
  "users": [
    {
      "userId": "canonical UUID",
      "fullName": "Example User",
      "username": "ExampleUser",
      "normalizedUsername": "exampleuser",
      "createdAt": "ISO-8601 UTC instant",
      "manifestId": "canonical UUID",
      "userKdf": { "algorithm": "Argon2id", "memoryKiB": 65536, "iterations": 3, "parallelism": 1, "salt": "base64" },
      "wrappedUserMasterKey": { "…envelope…" }
    }
  ]
}
```

- `normalizedUsername` is `username.trim().toLowerCase(Locale.ROOT)` and is
  unique within the vault.
- `wrappedUserMasterKey` is the account's 32-byte user master key (UMK),
  encrypted under a key derived from the account password with `userKdf`, AAD
  `EncryptDrive|user-key|v1|<vaultId>|<userId>`. There is no password hash:
  unwrapping the UMK is the password check.

## Manifests

`manifests/<manifestId>.enc` is an envelope encrypted under the account's UMK
with AAD `EncryptDrive|manifest|v1|<vaultId>|<userId>`. Decrypted:

```json
{
  "formatVersion": 1,
  "userId": "canonical UUID",
  "rootFolderId": "canonical UUID",
  "entries": [
    {
      "entryId": "canonical UUID",
      "kind": "FILE",
      "parentId": "canonical UUID",
      "name": "budget.xlsx",
      "createdAt": "ISO-8601 UTC instant",
      "modifiedAt": "ISO-8601 UTC instant",
      "deletedAt": "ISO-8601 UTC instant (only while in the trash)",
      "originalParentId": "canonical UUID (only on the entry the user trashed)",
      "plainSize": 12345,
      "blobId": "canonical UUID",
      "wrappedFileKey": { "…envelope…" },
      "contentNonce": "base64 of 12 random bytes"
    }
  ]
}
```

- Absent values are omitted, not written as `null`. Folders (`"kind":
  "FOLDER"`) never carry `plainSize`, `blobId`, `wrappedFileKey`, or
  `contentNonce`.
- Every manifest has exactly one root folder: `parentId` absent, `name` `"/"`.
  It is never trashed, deleted, or renamed.
- Names are logical metadata (1 to 255 code points, not `.` or `..`, no NUL,
  unique among active siblings ignoring case). They may contain characters that
  are illegal in Windows file names; export replaces those.
- Trash: moving an entry to the trash sets `deletedAt` on it and on every
  active descendant, using the same timestamp, and sets `originalParentId` on
  the entry itself. Restoring clears `deletedAt` for the entries that carry
  that timestamp.
- `wrappedFileKey` is the file's 32-byte data key (FDEK), encrypted under the
  UMK with AAD `EncryptDrive|file-key|v1|<vaultId>|<userId>|<entryId>`.

## Blobs

`storage/blobs/<shard>/<blobId>.edv` contains, with no header:

```text
AES-256-GCM(FDEK, contentNonce, AAD) ciphertext  ||  16-byte tag
```

with AAD `EncryptDrive|file|v1|<vaultId>|<userId>|<entryId>`. The blob size is
therefore `plainSize + 16`. Blobs are immutable: once renamed from `.part`
they are never rewritten, only deleted when their entry is permanently
deleted.

## Associated data

| Ciphertext | AAD (UTF-8) |
|---|---|
| Wrapped RMK | `EncryptDrive\|vault-key\|v1\|<vaultId>` |
| User registry | `EncryptDrive\|users\|v1\|<vaultId>` |
| Wrapped UMK | `EncryptDrive\|user-key\|v1\|<vaultId>\|<userId>` |
| Manifest | `EncryptDrive\|manifest\|v1\|<vaultId>\|<userId>` |
| Wrapped FDEK | `EncryptDrive\|file-key\|v1\|<vaultId>\|<userId>\|<fileId>` |
| File content | `EncryptDrive\|file\|v1\|<vaultId>\|<userId>\|<fileId>` |

`<fileId>` is the manifest `entryId`. Ids are canonical UUID strings.

## Writes, backups, and locking

- Metadata is written to a temporary file in the same directory, flushed to
  disk, and moved over the destination with `ATOMIC_MOVE` (falling back to a
  replacing move where atomic moves are unsupported).
- Before `users.enc` or a manifest is replaced, the previous version is
  rotated into `backups/`: `.2` → `.3`, `.1` → `.2`, current → `.1`. Backups
  are verbatim ciphertext.
- When `users.enc` or a manifest fails authenticated decryption, `.1`, `.2`,
  and `.3` are tried in order. The first that authenticates and parses is
  copied back over the damaged file. Backups that fail authentication are
  never used.
- While a vault is open, EncryptDrive holds an exclusive `FileChannel` lock on
  `.encryptdrive/lock`. A second process that cannot take the lock refuses to
  open the vault.

## Versioning

- `formatVersion` appears in `vault.json`, the decrypted registry, and every
  decrypted manifest. The envelope has its own `version`.
- The `vault.json` version is checked before any key derivation or decryption:
  a higher value is reported as "created by a newer version", a missing or lower
  one as damage. Registry and manifest versions are checked after decryption,
  and anything other than `1` is rejected.
- Any change older readers cannot handle increments `formatVersion`: new
  mandatory fields, different AAD strings, a different cipher or blob layout.
- Argon2id parameters are stored with every salt, so they can be raised for new
  keys without a format change. Readers accept Argon2id with 1 to 8 lanes, 1 to
  10 iterations, `8 × lanes` to 1,048,576 KiB of memory, and a 16-byte salt.
  Anything else is treated as damage (the header is plaintext, so these bounds
  also stop a modified `vault.json` from demanding unbounded memory).
