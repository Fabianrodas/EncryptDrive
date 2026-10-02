# EncryptDrive v1.0.0 Release Design Specification

**Status:** Approved architecture converted into a release specification for implementation planning  
**Target release:** `v1.0.0`  
**Current implementation branch:** `feature/encryptdrive-1.0`  
**Current baseline:** 19 roadmap tasks already implemented and committed; this specification defines the hardening, product-completion, packaging, cleanup, and release work still required before `v1.0.0` can exist.

---

## 1. Product Goal

EncryptDrive v1.0.0 is a Windows-first, local-first encrypted vault application built with Java 21 and JavaFX 21. It must protect user metadata and file contents at rest while remaining usable from internal disks, removable USB storage, and locally synchronized folders such as OneDrive.

A finished v1.0.0 must be more than a source build that passes tests. It must be a coherent end-user product with:

- crash-safe encrypted storage semantics;
- multiple cryptographically isolated accounts per vault;
- complete basic file-management workflows;
- safe password/vault-key rotation behavior;
- explicit handling of corrupted or malicious metadata;
- a Windows installer with a normal installation wizard;
- a separate portable Windows build;
- clean repository/version metadata;
- repeatable release artifacts and checksums;
- a documented release process using release-candidate tags before the final `v1.0.0` tag;
- completed physical/manual validation on USB, OneDrive, and a clean Windows environment without system Java.

The final tag `v1.0.0` is a release gate, not a development milestone. It must not be created until the release-candidate validation defined in this document is satisfied.

---

## 2. Current Baseline

The current branch already contains the first complete encrypted architecture:

- vault create/open UI;
- Argon2id password derivation;
- AES-256-GCM authenticated encryption with contextual AAD;
- random RMK, UMK, and per-file FDEKs;
- encrypted user registry;
- encrypted per-user manifests;
- per-user cryptographic isolation;
- streaming file encryption/decryption;
- folders;
- trash, restore, permanent delete;
- encrypted metadata backups and recovery;
- same-machine vault locking;
- JavaFX login/register/dashboard/files/trash/profile/vault settings flows;
- portable `jpackage` app-image script;
- Maven/JUnit automated test suite;
- CI build workflow;
- security and vault-format documentation.

This specification does not replace those features. It hardens and finishes them.

### 2.1 Known issues that must be fixed

The following are release blockers:

1. Permanent deletion can delete a blob before the manifest state is durably committed, creating a possible valid manifest that references a missing blob after an I/O failure or crash.
2. Historical manifest backups can resurrect metadata referring to blobs that were permanently deleted.
3. Window close / Alt+F4 can race with an active import/export operation even though normal navigation/logout is disabled.
4. User-registry backups may retain a user key envelope wrapped by an old account password after password change.
5. Account password minimum is still 8 characters; v1.0.0 requires 12.
6. Metadata readers currently need explicit size/resource limits before reading attacker-controlled vault metadata into memory.
7. The basic file manager lacks rename, move, recursive folder import, empty-trash, and search.
8. Version information is split/hard-coded (`1.0-SNAPSHOT` vs packaging `1.0.0`).
9. Installer packaging does not yet exist; only a portable app-image exists.
10. The repository contains local/generated artifacts in the working directory, and a historical plaintext `data/users.json` exists in public Git history.
11. `.gitattributes` does not normalize line endings, producing false CRLF/LF modifications across Windows/Linux environments.
12. The remaining real-device/release validation has not been performed.

---

## 3. Non-Goals for v1.0.0

The following remain explicitly outside v1.0.0:

- cloud backend or remote account service;
- telemetry or analytics;
- automatic network update service;
- remote password recovery;
- multi-device concurrent writing to one synchronized vault;
- file sharing between EncryptDrive users;
- mounted virtual drive / filesystem driver;
- decrypted preview/open-in-place temporary files;
- guaranteed secure erasure from SSD/flash media;
- mobile clients;
- macOS/Linux installers;
- a custom cryptographic algorithm.

Manual future upgrades will be supported by installer identity/versioning, but v1.0.0 does not include an online updater.

---

## 4. Installation and Vault Location Are Separate Concepts

EncryptDrive must keep application installation separate from encrypted data placement.

### 4.1 Installed edition

The Windows installer decides where the **application binaries and bundled runtime** are installed.

The installer must:

- be generated as a Windows `.exe` package using `jpackage`;
- include its own Java runtime;
- provide a directory chooser;
- offer Start Menu integration;
- offer a desktop shortcut choice;
- use a stable Windows upgrade UUID across future EncryptDrive versions;
- install per-user so normal installation does not require machine-wide administration unless Windows itself requires elevation for a user-selected destination;
- register a normal Windows uninstall entry;
- never create, move, delete, modify, or automatically select a vault;
- never delete vaults during uninstall.

Stable upgrade UUID for EncryptDrive Windows packages:

`b1c326c7-6ed8-4052-868d-459a9cc54cd9`

### 4.2 Portable edition

The portable edition remains a self-contained Windows app-image zipped for distribution.

It must:

- run without installed Java;
- be movable to USB or another directory;
- keep all application binaries/runtime under its portable application directory;
- not assume that the vault is beside the executable;
- use exactly the same vault format and application logic as the installed edition.

### 4.3 Vault selection remains in EncryptDrive

After either edition starts, the first product decision remains:

- **Create a new vault**; or
- **Open an existing vault**.

The user may place a vault on:

- an internal local drive;
- an external USB/removable drive;
- a local OneDrive-synchronized directory;
- another normal writable filesystem location.

The installer location must never determine the vault location.

---

## 5. Windows Distribution Artifacts

Final v1.0.0 release artifacts:

- `EncryptDrive-1.0.0-Setup.exe`
- `EncryptDrive-1.0.0-Windows-Portable.zip`
- `SHA256SUMS.txt`
- release notes / changelog

Release-candidate artifacts use a channel suffix in the distributed filename, for example:

- `EncryptDrive-1.0.0-rc.1-Setup.exe`
- `EncryptDrive-1.0.0-rc.1-Windows-Portable.zip`

The internal Windows application/package version remains numeric `1.0.0`; the RC identity is represented by the Git tag, release metadata, and artifact filename. This avoids coupling Windows package-version parsing to semantic prerelease syntax.

### 5.1 Packaging prerequisites

Windows installable packages must be built on Windows with:

- JDK 21;
- Maven;
- the Windows packaging prerequisites required by `jpackage` (WiX);
- no dependency on a system JRE in the produced artifact.

### 5.2 Code signing

The release pipeline must be **signing-ready**, but no signing secret or private certificate may be committed.

If a valid Authenticode certificate is available, release scripts may sign artifacts using credentials supplied only through the environment/certificate store. If no certificate is available, v1.0.0 may be released unsigned, but documentation must state that Windows may show an unknown-publisher warning and SHA-256 checksums must still be published.

---

## 6. Versioning and Git Tag Policy

### 6.1 Development version

During implementation work, Maven project version becomes:

`1.0.0-SNAPSHOT`

No production tag is created during ordinary implementation tasks.

### 6.2 Single source of application version

The Maven project version is the canonical application version.

Packaging scripts, generated application metadata, UI version display, filenames, documentation, and release checks must derive the base application version from the project rather than separately hard-coding `1.0.0`.

A generated build/version resource may expose the Maven version to the JavaFX UI.

### 6.3 Release candidate tags

Tags begin only after:

- all implementation tasks are complete;
- the full automatic test suite passes from a clean checkout;
- packaging scripts succeed;
- repository cleanup is complete;
- release documentation is ready;
- the release-ready commit is on `main`.

The first candidate is an **annotated tag**:

`v1.0.0-rc.1`

If candidate validation finds a defect:

1. create/fix on an appropriate release-fix branch;
2. merge the correction to `main`;
3. rerun all automatic gates;
4. create `v1.0.0-rc.2`, then `.3`, etc.

Tags must never be moved or silently replaced.

### 6.4 Final tag

The annotated tag:

`v1.0.0`

may be created only on the exact `main` commit whose release candidate passed the complete mandatory manual matrix.

If no source changes occurred after the accepted RC, the final tag may point to the same commit as the accepted RC tag.

The final GitHub release remains draft/unpublished until final artifacts built from the tag pass packaging smoke verification.

---

## 7. Cryptographic Architecture

The existing hierarchy remains the v1.0.0 foundation:

1. Vault password + Argon2id -> VKEK.
2. VKEK unwraps random RMK.
3. RMK encrypts the user registry.
4. Account password + Argon2id -> UKEK.
5. UKEK unwraps random UMK.
6. UMK encrypts the user's manifest.
7. Every file has a random FDEK.
8. FDEK encrypts file content and is wrapped under the account UMK.
9. AES-256-GCM with contextual AAD authenticates encrypted data.

No password hash is required.

### 7.1 Password requirements

For v1.0.0:

- vault password minimum: 12 characters;
- account password minimum: 12 characters;
- passwords are not trimmed;
- no mandatory composition rule (uppercase/symbol/etc.) is added;
- password fields must be cleared as soon as practical;
- security-layer password inputs remain `char[]` and are wiped best-effort.

Existing pre-release account records whose passwords are shorter than 12 characters remain loggable so development vaults are not silently destroyed, but **any newly registered account and any new password set through Change Password must meet 12 characters**. Documentation should recommend changing old development passwords.

### 7.2 KDF parameter validation

Argon2 parameters read from vault metadata must remain strictly bounded before allocating memory or performing work. Tampered metadata may not request arbitrary resource consumption.

---

## 8. Password Change Hardening

### 8.1 Required v1.0.0 behavior

A successful account password change must immediately invalidate all retained registry backups that contain the old password-wrapped UMK.

The existing UMK may remain unchanged for v1.0.0. The security contract is:

- the new password is the only account password accepted by the active registry and every retained registry backup after change;
- previous registry backups are reseeded/replaced with authenticated post-change state;
- file blobs are not re-encrypted;
- the account manifest is not re-encrypted solely because of password change.

This eliminates the known old-password-through-backup weakness without introducing a risky cross-file key-rotation transaction immediately before the first stable release.

### 8.2 Explicit limitation

Password change does not revoke an attacker who already extracted the live UMK from process memory or from another compromise. Memory compromise is outside the v1.0.0 at-rest threat model.

### 8.3 Vault password change

Vault password change continues to rewrap the same RMK. `vault.json` is not rotated through old backups, so the previous vault password must stop opening the current vault immediately after a successful change.

---

## 9. Crash-Safe Permanent Deletion

Permanent delete must become transaction-safe.

### 9.1 Encrypted deletion journal

`UserManifest` gains an optional encrypted list of pending physical blob deletions. It lives inside the already encrypted manifest and reveals nothing new at rest.

Conceptual model:

```text
pendingDeletions: [
  { blobId, queuedAt }
]
```

No plaintext deletion journal is created.

### 9.2 Delete sequence

For a file or trashed folder subtree:

1. Resolve every affected active/trash manifest entry and every referenced blob.
2. Remove the logical entries from the manifest in memory.
3. Add their blob IDs to `pendingDeletions`.
4. Atomically save the new encrypted manifest.
5. Reseed retained manifest backups from the post-delete logical state so no recoverable backup can resurrect references to blobs about to be destroyed.
6. Delete queued blobs one by one. A missing blob is treated as already deleted.
7. Remove successfully completed IDs from `pendingDeletions`.
8. Atomically save the cleaned manifest.
9. Reseed backups again from the cleaned post-delete state.

### 9.3 Crash behavior

- Crash before step 4: old state still references intact blobs; deletion did not happen.
- Crash after step 4 but before all blobs are deleted: user-visible entries are already gone; remaining blob IDs stay in encrypted `pendingDeletions` and cleanup resumes later.
- Crash after a blob is deleted but before step 8: missing blob is treated as completed on retry.
- Failure deleting a blob may leave ciphertext orphaned temporarily, but may never leave live/recovered metadata referencing intentionally deleted ciphertext.

### 9.4 Cleanup trigger

Pending deletions are retried:

- after successful user login/manifest load;
- before/after another permanent-delete operation;
- from Empty Trash where applicable.

Failure to physically delete an orphan must not prevent the account from opening; it produces a non-destructive warning and remains queued for a later retry.

---

## 10. Metadata Backup Semantics

Backups remain encrypted and authenticated.

### 10.1 Registry

Three registry generations remain the normal recovery policy.

Special security-sensitive changes such as account password change must reseed all retained generations from the new state so an obsolete credential envelope does not survive in recovery data.

### 10.2 Manifest

Three manifest generations remain the normal recovery policy.

Permanent deletion is a special destructive checkpoint: before physical blob removal, every retained recoverable manifest state must already represent the post-delete state.

### 10.3 Recovery notification

If an authenticated backup is automatically restored, the app must continue notifying the user that recovery occurred and that changes newer than the restored generation may have been lost.

---

## 11. Safe Application Shutdown and Background Operations

All mutating or plaintext-producing file operations remain background operations.

While an operation is active:

- sidebar navigation that would invalidate the session is disabled;
- Log Out is disabled;
- Close Vault is disabled;
- the custom window close (`X`) is blocked;
- Alt+F4 / standard window-close requests are blocked;
- closing through any application exit path must use the same guard.

The user receives a clear non-modal or dialog message such as:

`Please wait for the current file operation to finish before closing EncryptDrive.`

v1.0.0 does not add cancellation unless a particular operation already has a safely testable cancellation path.

OS/process termination can still happen unexpectedly. Storage transactions must remain crash-safe even when the guard cannot run.

After normal close:

- user UMK is wiped best-effort;
- RMK is wiped best-effort;
- vault lock is released;
- background executor is shut down cleanly.

---

## 12. Defensive Metadata Parsing and Resource Limits

Vault-controlled metadata is untrusted input even when it is expected to be encrypted/authenticated.

Before `readString`, JSON parsing, Base64 decoding, or large allocation, enforce limits.

Default v1.0.0 limits:

- `vault.json`: 256 KiB maximum;
- `users.enc` and each registry backup: 16 MiB maximum;
- one encrypted manifest and each manifest backup: 64 MiB maximum;
- JSON/envelope mandatory fields must exist and have valid types;
- decoded AES-GCM nonce must be exactly 12 bytes;
- wrapped 256-bit key ciphertext must have the expected key+tag size;
- UUID fields must parse as UUIDs;
- format versions outside supported values fail closed;
- Argon2 salt/parameter sizes must remain bounded;
- negative or overflowing logical sizes are rejected.

Exceeded limits are treated as corruption/tampering, not as a request to allocate more memory.

File content blobs are streamed and do not use the metadata size caps. File sizes remain `long` and are bounded by filesystem/runtime capability rather than an arbitrary product limit.

---

## 13. File Manager Completion

The current encrypted file manager is extended to complete the basic v1.0.0 workflow.

### 13.1 Rename

Users can rename files or folders inside their encrypted logical filesystem.

Requirements:

- metadata-only operation;
- never decrypt/re-encrypt the file blob;
- reject invalid/empty names;
- reject case-insensitive sibling conflicts;
- preserve original file extension only if the user chooses it; EncryptDrive does not force an extension;
- trashed items are not renamed until restored.

### 13.2 Move

Users can move one or more active files/folders to another active logical folder.

Requirements:

- metadata-only operation;
- no blob rewrite;
- cannot move root;
- cannot move a folder into itself or any of its descendants;
- reject sibling name conflicts at the destination before mutating state;
- preserve a valid tree after every operation.

### 13.3 Recursive folder import

Users can choose a source directory and import it recursively.

Requirements:

- preserve the directory hierarchy, including empty directories;
- encrypt every regular file using the existing streaming file crypto;
- do **not** follow symbolic links/junctions/reparse-point traversal outside the selected source tree;
- unreadable files fail safely with a clear error;
- the source tree is never modified or deleted;
- show aggregate progress (`files completed / total`, plus byte progress where practical);
- Close/Logout remains blocked during the operation;
- duplicate logical names discovered during preflight must abort before importing that conflicting subtree rather than silently overwrite data;
- any blobs created by an import that cannot be committed must be cleaned best-effort and must never be referenced by a partially committed manifest.

For v1.0.0, correctness and crash safety take priority over batching metadata writes for maximum throughput.

### 13.4 Search

Files view gains case-insensitive search across the signed-in user's active manifest entries.

Requirements:

- no persistent plaintext or encrypted search index;
- search uses the decrypted in-memory manifest data already available to the current account;
- recursive results across folders;
- show enough logical path context to distinguish same/similar names;
- double-click/navigate result to its parent folder or select/open folder as appropriate;
- Trash has its own filtering behavior and is not mixed into active search results.

### 13.5 Empty Trash

Trash view gains `Empty Trash`.

Requirements:

- destructive confirmation explicitly states the item count;
- uses the same crash-safe permanent-deletion engine as individual deletion;
- does not duplicate deletion logic in the controller;
- UI remains usable if some physical orphan cleanup must be retried later.

### 13.6 Existing behavior retained

The following remain supported:

- create folder;
- import individual files;
- export individual/multiple items;
- move to Trash;
- restore from Trash;
- permanent delete;
- breadcrumbs;
- real storage statistics.

No decrypted preview/open-in-place is added.

---

## 14. Performance and Optimization Policy

v1.0.0 performs targeted optimization only where measurement or architecture justifies it.

### 14.1 Required characteristics

- File encryption/decryption remains streaming and must not load full file contents into heap.
- The existing 1 GiB round-trip with `-Xmx256m` remains a release test.
- Rename/move/search do not touch encrypted blobs.
- UI filesystem/crypto work never runs on the JavaFX application thread.
- UI updates from long operations are throttled/batched enough to avoid flooding the JavaFX event queue.
- Metadata is not repeatedly re-read more than required within one service operation.
- No plaintext cache or persistent search index is introduced for performance.

### 14.2 Folder-import strategy

Do not introduce a complicated multi-file database-style transaction solely to maximize folder-import speed for v1.0.0. Prefer the existing safe per-file/manifest transaction model unless profiling proves it unusable.

### 14.3 Performance regression checks

Add repeatable tests/benchmarks sufficient to catch accidental whole-file buffering and gross metadata regressions. Do not make wall-clock thresholds brittle in normal CI.

---

## 15. UI/UX Completion

The current visual language remains. v1.0.0 is refinement, not a redesign.

Required UX additions:

- rename action;
- move action with destination picker within the vault tree;
- Import Folder action;
- Files search field/results behavior;
- Empty Trash action;
- application version displayed in Vault Settings or an About subsection;
- clear busy/close-blocked messaging;
- password minimum text updated to 12 characters;
- password-loss warnings remain explicit;
- recovery warnings remain visible;
- installer and portable documentation clearly explain that installing EncryptDrive does not choose or move a vault.

Normal 1000x600 and maximized layouts must remain usable after additions.

---

## 16. Repository Hygiene and Cleanup

### 16.1 Tracked repository

Add `.gitattributes` to normalize textual source files and eliminate CRLF/LF false changes while leaving binary assets untouched.

Review and remove only **proven unused**:

- Java classes;
- FXML resources;
- CSS rules/resources;
- obsolete scripts;
- stale IDE-specific tracked configuration that is not required for the supported build/run workflow.

Do not perform speculative cleanup that makes NetBeans or Maven usage worse.

### 16.2 Local generated/untracked content

The release working tree must not contain stale local artifacts such as:

- `target/` after final clean verification except intentional packaging output under the release build step;
- `data/users.json`;
- `.github/java-upgrade/` extension artifacts;
- temporary export/import files;
- stale package output from previous versions.

`.gitignore` must continue preventing these from becoming tracked.

### 16.3 Planning documentation

The existing completed v1.0 roadmap/spec is no longer the active implementation authority once the new v1.0.0 release plan begins.

During development:

- retain the new v1.0.0 spec and active plan(s) under `docs/superpowers/`;
- either archive or remove completed superseded planning documents so only one active plan chain is obvious;
- Git history remains the historical record.

At final release, active planning docs may be archived under a clearly named `docs/superpowers/archive/v1.0.0/` directory rather than mixed with future active plans.

---

## 17. Historical `data/users.json` Cleanup

The plaintext development user file is no longer tracked or used by the current application, but it exists in public historical commits.

Before the first public stable release:

1. confirm the current tree contains no tracked or packaged `data/users.json`;
2. remove any local copy from the release checkout;
3. treat any real/reused development password represented by the historical hash as compromised and change it anywhere it was reused;
4. decide whether to rewrite public Git history.

### 17.1 History rewrite safety gate

History rewriting is destructive to commit IDs and existing clones. Codex must **not force-push or rewrite the remote automatically without explicit human confirmation at that step**.

If approved, remove `data/users.json` from all reachable Git history using `git filter-repo` or equivalent, verify it is unreachable from rewritten branch history, and force-update the remote only with explicit confirmation.

Perform history cleanup before creating any v1.0.0 RC/final tags so release tags never need rewriting.

A rewrite cannot erase copies that already exist in forks/clones/caches; password reuse must still be addressed separately.

---

## 18. Build and Release Automation

### 18.1 Build scripts

Replace one-off hard-coded version assumptions with reusable release scripts.

Expected responsibilities:

- obtain the canonical Maven project version;
- run `mvn -B clean verify`;
- build the self-contained app image;
- verify portable runtime independence;
- create the portable ZIP;
- create the Windows installer EXE;
- verify expected installer/portable files exist;
- optionally sign when credentials are explicitly provided;
- compute SHA-256 checksums;
- write all distributable artifacts into a clean versioned release directory.

### 18.2 Release output directory

Use a deterministic structure such as:

```text
target/release/1.0.0/
├── EncryptDrive-1.0.0-Setup.exe
├── EncryptDrive-1.0.0-Windows-Portable.zip
└── SHA256SUMS.txt
```

RC wrapper/output may add `-rc.N` to filenames while preserving the numeric application version passed to Windows packaging.

### 18.3 Clean package verification

Verification must prove:

- bundled runtime exists;
- launcher starts with `JAVA_HOME` removed and system Java absent from `PATH`;
- package config has no hard-coded build-machine JDK path;
- both installer and portable package contain only intended app/runtime resources;
- vault/test data is not packaged;
- installed application can launch and uninstall normally.

---

## 19. CI and Release Workflow

### 19.1 Normal CI

Every pushed development commit/PR must at minimum run:

`mvn -B clean verify`

with Java 21.

### 19.2 Windows packaging CI

A Windows job must verify package creation because Windows EXE packaging cannot be validated completely on Linux/macOS.

It should:

- install/configure required packaging tooling;
- build portable app-image;
- build installer;
- run noninteractive artifact checks;
- upload artifacts for RC validation when appropriate.

### 19.3 Tag workflow

RC and final annotated tags trigger a release packaging workflow.

For RC tags:

- build artifacts;
- generate checksums;
- attach them to a prerelease/draft release;
- do not call the build stable.

For `v1.0.0`:

- build final-named artifacts;
- generate checksums;
- prepare the stable release as draft until final package smoke checks succeed.

---

## 20. Documentation Required for v1.0.0

Update/create:

- `README.md`
- `docs/SECURITY.md`
- `docs/VAULT_FORMAT.md`
- `CHANGELOG.md`
- `docs/testing/manual-ui.md`
- `docs/testing/release-checklist.md`
- release notes template/instructions

Documentation must cover:

- installed vs portable editions;
- choosing vault location separately from installation;
- USB use;
- OneDrive single-writer rule;
- no password recovery;
- password-change semantics;
- metadata backup/recovery semantics;
- permanent-delete semantics and secure-delete limitation;
- exported plaintext warning;
- threat-model limitations;
- uninstall does not remove vaults;
- version/tag meaning;
- how to verify SHA-256 checksums.

---

## 21. Automated Test Requirements

The existing suite is retained and expanded. Every bug fix or feature is test-first where practical.

Mandatory new automatic coverage includes at least:

### 21.1 Permanent deletion

- manifest save failure cannot leave a live manifest pointing to a blob already intentionally deleted;
- crash-state/pending deletion resumes safely;
- missing already-deleted blob is idempotent success;
- backups after permanent delete cannot resurrect deleted entries;
- folder subtree deletion queues every referenced blob exactly once;
- failed physical deletion leaves retryable encrypted pending state.

### 21.2 Password change

- new account password minimum is 12;
- newly registered account minimum is 12;
- old password fails after change;
- new password succeeds;
- every retained `users.enc` backup after change also rejects the old password and accepts the new state;
- file export still works after password change.

### 21.3 Close/busy handling

- close request is consumed while a background operation is marked busy;
- logout/close-vault remain disabled while busy;
- normal close clears sessions/keys and releases lock when idle.

### 21.4 Metadata limits

- oversize `vault.json` fails before parsing;
- oversize registry fails safely;
- oversize manifest fails safely;
- malformed Base64/nonce/key envelopes fail closed;
- absurd KDF parameters remain rejected.

### 21.5 File manager

- rename file/folder;
- reject rename conflict;
- move file/folder;
- reject descendant-cycle move;
- reject destination conflict;
- recursive folder import preserves hierarchy and empty folders;
- recursive import does not follow symlinks/reparse traversal;
- unreadable source fails safely;
- search is recursive and case-insensitive;
- search never exposes another account;
- Empty Trash uses permanent-delete semantics.

### 21.6 Distribution/version

- application can read/display canonical build version;
- packaging scripts do not contain stale `1.0-SNAPSHOT` artifact assumptions;
- release artifact names derive from version/channel;
- portable package verifier continues to launch without system Java.

### 21.7 Existing large-file gate

The 1 GiB streaming round-trip with `-Xmx256m` remains optional in routine CI but is **mandatory before every release-candidate tag**.

---

## 22. Manual Release Validation Matrix

The following are mandatory before final `v1.0.0` tagging.

### 22.1 General Windows workflow

On a normal Windows machine:

- install with the setup wizard;
- choose a non-default install directory once;
- test Start Menu shortcut;
- test optional desktop shortcut behavior;
- create vault;
- create at least two accounts;
- prove each account only sees/exports its own content;
- import individual files;
- import a nested directory including empty folders;
- rename/move/search;
- Trash/restore/Empty Trash;
- change account password;
- change vault password;
- close/reopen vault;
- restart application;
- uninstall application;
- confirm the external vault is untouched;
- reinstall and open the same vault successfully.

### 22.2 USB

Using a real removable drive:

- create/open a vault on USB;
- import/export files;
- close vault normally and eject;
- move USB to another supported Windows machine/build environment and open it;
- simulate removal during a non-destructive import/export operation and verify failure is clean and metadata remains recoverable;
- reconnect and verify vault consistency.

### 22.3 OneDrive

Using a real OneDrive-synchronized directory:

- create or copy a vault into OneDrive;
- let synchronization complete;
- verify only encrypted vault artifacts are synchronized;
- close EncryptDrive before switching computers;
- wait for sync completion;
- open from the second machine;
- verify data and accounts;
- verify documentation clearly prohibits simultaneous two-machine writes.

### 22.4 Clean Windows without Java

On a clean Windows environment with no Java installed:

- installer launches/installs;
- installed EncryptDrive launches;
- portable ZIP launches;
- create/open vault works;
- basic import/export works;
- uninstall succeeds;
- vault remains untouched.

### 22.5 Final artifact smoke

Final artifacts produced from `v1.0.0` tag must at minimum be installed/launched and portable-launched once before the draft release is published.

---

## 23. Release Gates

### Gate A — Implementation complete

- every planned implementation task committed;
- no known release-blocking TODOs;
- clean code/resource audit complete.

### Gate B — Automatic verification

- `mvn -B clean verify` passes from a clean checkout;
- all normal tests pass;
- 1 GiB / 256 MiB streaming test passes;
- Windows portable packaging passes;
- Windows installer packaging passes;
- package verification passes.

### Gate C — Repository/security readiness

- no plaintext development user DB in current tree/package;
- history-cleanup decision completed before tags;
- no secrets/certificates in repository;
- `.gitattributes` and `.gitignore` clean;
- version is release-ready;
- docs/changelog complete.

### Gate D — RC tag

Only after Gates A-C on `main`:

`v1.0.0-rc.1`

### Gate E — Manual product validation

- USB real-device matrix passes;
- OneDrive real synchronization matrix passes;
- clean Windows without Java matrix passes;
- installer/reinstall/uninstall preserves vaults;
- installed and portable editions pass functional smoke.

Any code fix resets the relevant gates and requires a new RC tag.

### Gate F — Final stable tag

Only after accepted RC:

`v1.0.0`

Then build final artifacts from the tag, perform final artifact smoke, and publish the release.

---

## 24. Definition of Done for v1.0.0

EncryptDrive v1.0.0 is complete only when all of the following are true:

- storage and key semantics satisfy this spec;
- permanent delete is crash-safe;
- obsolete-password recovery backups are eliminated after password change;
- close/background races are handled;
- metadata resource limits fail closed;
- basic file manager includes import file/folder, export, create folder, rename, move, search, Trash, restore, permanent delete, and Empty Trash;
- multi-user isolation remains cryptographic;
- streaming remains memory-bounded;
- normal Windows installer exists and lets the user select application installation location;
- portable edition exists independently;
- vault location remains chosen inside EncryptDrive;
- uninstall does not delete vaults;
- repository is clean and versioning is centralized;
- release artifacts have SHA-256 checksums;
- mandatory automated and manual release matrices pass;
- the accepted release commit is on `main`;
- an annotated `v1.0.0` tag is created only after the accepted RC validation;
- final tagged artifacts pass smoke verification before publication.

Anything short of these conditions remains a prerelease build, regardless of whether the application compiles or the ordinary test suite is green.
