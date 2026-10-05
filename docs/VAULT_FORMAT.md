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

## Encoding and JSON rules

- JSON files are UTF-8 without a byte-order mark. JSON member names are
  case-sensitive and use the spellings shown in this document.
- JSON object member order and insignificant whitespace have no meaning to a
  reader. The bytes fed to AES-GCM do matter: each envelope authenticates the
  exact UTF-8 plaintext bytes. The vectors pin those bytes for their examples.
- Writers use the standard padded Base64 alphabet (`A-Z`, `a-z`, `0-9`, `+`,
  `/`, with `=` padding) with no line breaks. Decoded lengths are stated for
  each field below.
- Writers omit absent optional fields rather than writing JSON `null`. They
  write only the fields defined for Format 1. Readers ignore unknown members
  in `vault.json` and the decrypted registry/manifest objects. This does not
  make new fields part of Format 1: a new mandatory field or changed meaning
  requires a format-version increase.
- The on-disk envelope in `users.enc` and each manifest file is stricter: it
  is exactly one JSON object with one each of `version`, `algorithm`, `nonce`,
  and `ciphertext`. Missing, duplicate, extra, wrongly typed, or trailing
  JSON data is rejected. Its syntax is strict JSON; comments, single-quoted
  strings, unquoted member names, and trailing commas are invalid. The
  `wrappedRegistryKey` envelope is nested in `vault.json`; writers use the
  same four fields, while the current header reader ignores unknown members.
- Writers serialize integer fields as integer number tokens, not quoted
  strings. Readers reject values that are not exact integers or exceed the
  field range.
- Timestamps written by EncryptDrive are UTC ISO-8601 instants ending in
  `Z`. UUIDs are canonical lowercase `8-4-4-4-12` text; IDs are generated as
  random UUID version 4 values.

`vault.json`, `users.enc`, and each manifest are limited to 256 KiB, 16 MiB,
and 64 MiB respectively, including their JSON envelopes. A reader rejects an
oversized file before parsing it; a writer refuses to create one that exceeds
the corresponding limit.

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
  is not 12 bytes, or whose ciphertext is shorter than the tag. The decoded
  ciphertext field is the raw GCM ciphertext followed by the 16-byte tag.
- Keys are exactly 32 bytes. A wrapped 32-byte key therefore has a 48-byte
  decoded ciphertext field. GCM decryption returns plaintext only after the
  tag verifies; a changed key, nonce, AAD, ciphertext, or tag is an
  authentication failure.

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
- The vault password is encoded as UTF-8 with no trimming or Unicode
  normalization, then passed to Argon2id. The VKEK is 32 bytes.
- Changing the vault password replaces `vault.json` with a new salt and a new
  wrapping of the same RMK. Nothing else changes.
- `vault.json` has no backups on purpose: an old copy would keep an old vault
  password working.

### Password-based key derivation

Format 1 uses Argon2id version 1.3 (`v = 0x13`) with no Argon2 secret or
additional-data input. The output is 32 bytes. The writer uses 65,536 KiB of
memory, 3 iterations, and 1 lane for both vault and account passwords. Every
KDF salt is 16 random bytes and is stored as padded Base64 next to its
parameters. Stored configurations are accepted only when the algorithm is
`Argon2id`, parallelism is 1–8, iterations are 1–10, memory is at least
`8 × parallelism` KiB and at most 1,048,576 KiB, and the decoded salt is 16
bytes. These bounds are checked before allocating Argon2 memory.

The vault password derives the VKEK that unwraps the random RMK. An account
password derives a separate key that unwraps that account's random UMK. A
random per-file FDEK is wrapped by the UMK. There are no password hashes.
Changing a password changes only its salt and wrapped-key envelope; it does
not re-encrypt the registry, manifest, or file blobs.

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
- Account passwords are encoded as UTF-8 without trimming or Unicode
  normalization, just like the vault password.

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
  ],
  "pendingDeletions": [
    { "blobId": "canonical UUID", "queuedAt": "ISO-8601 UTC instant" }
  ]
}
```

- Absent values are omitted, not written as `null`. Folders (`"kind":
  "FOLDER"`) never carry `plainSize`, `blobId`, `wrappedFileKey`, or
  `contentNonce`.
- Every manifest has exactly one root folder: `parentId` absent, `name` `"/"`.
  It is never trashed, deleted, or renamed.
- Names are logical metadata; see [Logical names](#logical-names) for the
  Format 1 rules and examples.
- Trash: moving an entry to the trash sets `deletedAt` on it and on every
  active descendant, using the same timestamp, and sets `originalParentId` on
  the entry itself. Restoring clears `deletedAt` for the entries that carry
  that timestamp.
- `wrappedFileKey` is the file's 32-byte data key (FDEK), encrypted under the
  UMK with AAD `EncryptDrive|file-key|v1|<vaultId>|<userId>|<entryId>`.
- `pendingDeletions` is an encrypted list of blob IDs whose manifest entries
  have already been removed but whose physical deletion still needs to be
  completed. Writers include an empty array when there is no pending cleanup.
- `plainSize` is the original file length as a nonnegative signed 64-bit
  integer. The blob ciphertext length is exactly `plainSize + 16` bytes.

### Logical names

Names in manifests are logical names, not paths. For entries created through
the Format 1 application rules, EncryptDrive strips leading/trailing
whitespace, then requires 1–255 Unicode code points and rejects `.`, `..`,
and NUL. Active siblings must be unique using case-insensitive comparison.
The name is not Unicode-normalized. `/`, `\`, Windows-reserved names, and
characters that Windows cannot use in a filename are allowed as logical
names; export sanitizes names for the destination filesystem. Hierarchy is
stored through IDs and `parentId`, never by putting separators in a path.

The public vectors include accepted-name, rejected-name, and sibling-collision
examples. These examples pin what the Java Format 1 reference implementation
does today; Phase 03A's compatibility contract records the application and
OS support lifecycle separately.

## Blobs

`storage/blobs/<shard>/<blobId>.edv` contains, with no header:

```text
AES-256-GCM(FDEK, contentNonce, AAD) ciphertext  ||  16-byte tag
```

with AAD `EncryptDrive|file|v1|<vaultId>|<userId>|<entryId>`. The blob size is
therefore `plainSize + 16`. Blobs are immutable: once renamed from `.part`
they are never rewritten, only deleted when their entry is permanently
deleted.

The file key is a random 32-byte FDEK. `contentNonce` is a random 12-byte
nonce stored as padded Base64 in the encrypted manifest. The `.edv` file is
the binary ciphertext followed immediately by the 16-byte authentication
tag; it has no JSON envelope or other header. The first two lowercase
hexadecimal characters of the blob UUID without hyphens form the shard name.

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

## Public conformance vectors

`test-vectors/format-v1/` contains deterministic, synthetic fixtures for the
Argon2id profiles, all AAD strings, wrapped RMK/UMK/FDEK values, the plaintext
and encrypted user registry and manifest, and a raw encrypted file blob.
`README.md` records the independent Python libraries and regeneration command.
The Java test suite consumes the exact files and checks them through the
repository readers, JCE, and the streaming Bouncy Castle implementation.

The Argon2id algorithm and version follow [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html).
AES-GCM follows [NIST SP 800-38D](https://csrc.nist.gov/pubs/sp/800/38/d/final).
