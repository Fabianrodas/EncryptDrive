# EncryptDrive compatibility contract

This document separates the compatibility of the desktop application, the
vault bytes, and the planned command-line client. These products can have
different version numbers and release schedules.

## Product and format versions

| Product | Version and role | Current support statement |
|---|---|---|
| EncryptDrive Desktop | Java 21 reference application; `1.0.0` is the initial release | Windows is the initial release target. This contract does not claim a macOS or Linux desktop release. |
| Vault Format | `formatVersion: 1`, independent of desktop releases | Defined by [`VAULT_FORMAT.md`](VAULT_FORMAT.md) and the public fixtures in [`test-vectors/format-v1/`](../test-vectors/format-v1/). It is designed to be independent of Java and the host operating system. |
| Future CLI | Planned Rust client with its own version number | No CLI release or CLI-supported operating system is claimed by EncryptDrive Desktop 1.0.0. |

Desktop 1.x is intended to continue reading and writing Format 1 vaults. A
desktop release may change its UI, installer, or other application behavior
without changing the vault format. A vault change that an existing Format 1
reader cannot safely handle requires a new `formatVersion`; application and
format version numbers do not advance together by definition.

Any future CLI must implement the documented bytes and pass the public
conformance fixtures before it claims Format 1 compatibility. The CLI is not
part of the v1.0.0 release, and no Rust implementation is included here.

Format 1 is specified without host paths or Java serialization details. The
Java desktop application is its current reference implementation. The format
is intended to be portable; this does not claim that desktop builds have been
released or supported on operating systems other than Windows.

## Persistent data and host paths

The selected vault root is a runtime `Path` held by `VaultContext`. It is not
serialized. The path chosen for an import source and the destination chosen
for an export are also runtime-only inputs. The vault root folder name remains
visible to the host operating system, but EncryptDrive does not copy its
absolute path into vault metadata.

Persisted metadata uses these logical values:

- `vault.json`: format version, random vault ID, creation instant, KDF
  parameters, and the encrypted registry key.
- The encrypted registry: account IDs, account display data, manifest IDs,
  KDF parameters, and wrapped user keys.
- Each encrypted manifest: entry IDs, parent IDs, logical names, timestamps,
  wrapped file keys, blob IDs, and pending deletion IDs.
- Blob and manifest file names use random UUIDs. They do not contain account,
  source path, file, or folder names.

`Format1PathPersistenceTest` creates a vault and import source beneath unique
host-only sentinel directories, decrypts the persisted registry and manifest,
and checks their plaintext JSON and loaded models for the sentinel strings
and absolute paths. A passing ciphertext-only search is not considered path
portability evidence.

## Logical names in Format 1

Names are encrypted manifest metadata, not host paths. The Java 21 reference
implementation applies the following rules when creating or renaming an
entry:

| Input behavior | Format 1 reference behavior |
|---|---|
| Trimming | Apply Java `String.strip()` first. Leading and trailing characters recognized by Java 21 `Character.isWhitespace` are removed. A no-break space (`U+00A0`) is not removed. |
| Length | Require 1 to 255 Unicode code points after trimming. |
| Empty, `.` or `..` | Reject. |
| NUL | Reject anywhere in the name. |
| Other controls | No additional control-character filter is applied. Embedded tab and `U+0001` are retained; whitespace at the edges is stripped. |
| Unicode normalization | None. Composed `café` and decomposed `cafe` + `U+0301` remain distinct names. |
| Case collisions | Active siblings collide when Java 21 `String.equalsIgnoreCase` says they are equal. This is locale-independent simple case comparison, not normalization or full multi-character case folding. A deleted sibling does not reserve its name. |
| Separators | `/` and `\` are allowed in a logical name. Hierarchy comes from IDs and `parentId`, not from name separators. |
| Windows filename rules | Reserved names such as `CON`, trailing dots, and otherwise Windows-invalid filename characters are allowed as logical names. Export sanitizes a name for its destination. |

The examples are covered by `ManifestServiceTest` and the public Format 1
logical-name vectors. A future client must preserve these results when it
reads or writes Format 1 metadata. Changing them in a way that makes existing
Format 1 names ambiguous requires a recorded format ruling before a code
change.

## Evidence and limits

The Format 1 byte vectors and persistence tests run on Windows 11, NTFS, and
Java 21.0.2 in this release environment. They verify that persisted logical
metadata does not include host paths and that the current Java reference
implementation consumes the published bytes. They do not certify a future
Rust CLI or a desktop build on macOS or Linux.
