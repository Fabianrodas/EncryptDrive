# EncryptDrive v1.0.0 Release — Master Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute the child plans strictly in the order below; each child plan lists its own tasks.

**Goal:** Take `feature/encryptdrive-1.0` through a hardened, tested, packaged (installer + portable), cleanly versioned, tagged and manually validated EncryptDrive v1.0.0 that meets the spec's Definition of Done. Phase 03A freezes the portable Format 1 contract and performance behavior before packaging begins.

**Architecture:** No redesign. The existing layering stays (`security` → `repositories` → `services` → `encryptdrive` controllers). Hardening goes into the repositories (size limits, strict parsing, checkpoint saves), the deletion engine goes into `FileService` with an encrypted journal inside `UserManifest`, and one close guard in `App` gates every exit path. Packaging becomes two scripts (`build-release.ps1`, `verify-package.ps1`) that derive everything from the Maven version.

**Tech Stack:** Java 21, JavaFX 21, Maven 3.9, Gson 2.14.0, Bouncy Castle 1.85.2, JUnit Jupiter 6.1.3, JDK 21 `jpackage` + WiX 3.14, PowerShell 7 / Windows PowerShell 5.1, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md` (approved; read it in full before any task).

## Global Constraints

Every task in every child plan implicitly includes these.

- Java 21 / JavaFX 21; `mvn -B clean verify` must be green at the end of every task commit.
- Argon2id (65,536 KiB, 3 iterations, parallelism 1, 16-byte salt, 32-byte output), AES-256-GCM (12-byte nonce, 128-bit tag), contextual AAD strings from `Aad` — unchanged. No custom cryptography.
- Random RMK, random UMK, random per-file FDEK — unchanged. Account isolation stays cryptographic.
- Never store passwords, password hashes, plaintext usernames, display names, file or folder names, manifests or user metadata outside the authenticated encrypted format. No plaintext caches, no search index, no decrypted preview/open-in-place.
- Vault password minimum: 12 characters. Account password minimum: 12 characters for new registrations and every Change Password. Existing shorter account passwords still log in. Passwords are not trimmed; no composition rules.
- Metadata read limits (checked before reading): `vault.json` 256 KiB; `users.enc` and each registry backup 16 MiB; each manifest and each manifest backup 64 MiB. The same limits are enforced on write.
- File content stays streaming; no whole-file reads of file content. The 1 GiB / `-Xmx256m` test is mandatory before every RC tag.
- Long filesystem/crypto work never runs on the JavaFX application thread.
- Maven `project.version` is the only source of the application version. Development version: `1.0.0-SNAPSHOT`.
- Stable Windows upgrade UUID: `b1c326c7-6ed8-4052-868d-459a9cc54cd9`.
- Release artifacts: `EncryptDrive-<version>[-rc.N]-Setup.exe`, `EncryptDrive-<version>[-rc.N]-Windows-Portable.zip`, `SHA256SUMS.txt` under `target/release/<version>/`. Windows package version stays numeric (`1.0.0`).
- The installer installs the application only; it never creates, selects, moves, modifies or deletes a vault.
- No private keys, certificates or signing secrets in the repository. Unsigned release is allowed and documented.
- No tags during implementation. First tag: annotated `v1.0.0-rc.1`, only after Gates A–C on `main`. Tags are never moved or replaced. `v1.0.0` only after the full manual matrix passes on an accepted RC.
- The Java desktop app is the official Format 1 reference implementation. Vault Format 1 is language- and operating-system-independent; a future Rust CLI is planned but out of scope. Desktop, CLI, and vault-format versions have independent lifecycles.
- T20 packaging cannot start until `FORMAT_V1_READY` is `DONE` in `V1_RELEASE_STATE.md`.
- No destructive Git history rewrite, no force-push, no remote tag/branch rewrite without the user's explicit approval at Human Gate H2.
- Visual language of the existing UI is preserved; 1000×600 and maximized layouts must stay usable (`UiLayoutTest`).
- One commit per task with the exact message given in the task. Every commit message ends with the trailer:
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`

---

## Repository baseline found by the audit (2026-10-01)

| Item | Finding |
|---|---|
| Branch | `feature/encryptdrive-1.0`, clean, 19 commits ahead of `main` (`0f913d2`), **never pushed** (no upstream). |
| `main` | Untouched by feature work; equals `origin/main` (`0f913d2`). |
| Build | `mvn -B clean verify`: 166 tests, 0 failures, 1 skipped (opt-in `LargeFileStreamingTest`). UI tests run locally. |
| Version | `pom.xml` `1.0-SNAPSHOT`; `scripts/package-windows.ps1` hard-codes the jar name and `--app-version 1.0.0`. |
| `data/users.json` | Plaintext dev DB (`id, fullName, username, passwordHash, salt`), originally added in `0030c7c`. H2 rewrote it out of all reachable history before v1.0.0 on 2026-10-05. The verified pre-rewrite bundle remains local; copies in forks, clones, caches, or elsewhere may remain. Any reused development password is compromised. |
| Local junk | `data/`, `.github/java-upgrade/` (extension output), `target/` (old `1.0-SNAPSHOT` jar), `nb-configuration.xml` (NetBeans local settings, ignored — kept). |
| Line endings | Index is LF everywhere; 15 working-tree files are CRLF because `core.autocrlf=true`; no `.gitattributes`. `nbactions.xml` is tracked but also listed in `.gitignore`. |
| Toolchain | JDK 21.0.2 with `jpackage` (all `--win-*` flags used in T20 confirmed with `jpackage --help`); Maven 3.9.16; Python 3.13; .NET Framework 3.5 installed; **WiX not installed**; `git-filter-repo` not installed; `gh` not installed. |
| CI | `.github/workflows/build.yml`: `mvn -B clean verify` on push to `main` and PRs only (windows-latest). No packaging job, no release workflow. |
| TODO/FIXME/System.exit | None in tracked sources. |

### Defects and gaps found beyond the spec's list (all covered by tasks)

1. Title-bar `×` buttons call `Stage.close()`, which never fires `onCloseRequest`; the key-wiping close handler only runs for OS close requests. `App.stop()` is the only backstop.
2. Close Vault on Login, Register and Vault Settings is not disabled during background work. Clicking it during login's Argon2 work closes the vault and the login then completes into a closed vault; during a vault-password change it wipes the RMK mid-operation.
3. Manifest decryption and writes run on the JavaFX thread (folder listing, New Folder, Move to Trash, Restore, Permanent Delete, Overview stats) — violates spec 14.1.
4. Every Files refresh decrypts the manifest twice (`listChildren` + `pathTo`).
5. `FileService.forCurrentSession()` pairs an identity snapshot with a live key supplier, so after logout/login a stale service would use another account's key.
6. Gson coerces JSON types (`"65536"` → int, number → string) and silently defaults missing fields in the unauthenticated `vault.json` and envelopes.
7. Metadata writes have no size cap, so a save could produce a file the new read limit rejects (self-inflicted lockout / silent rollback through backup recovery).
8. `BackupRotator` copies metadata through `Files.readAllBytes`.
9. Source-read errors during import are reported as vault STORAGE errors (a locked source file looks like a full vault).
10. Nothing prevents importing a folder that contains the vault, or exporting plaintext into the vault folder (which may be OneDrive-synced).
11. `RegisterController.showSuccess` is dead code; password-length texts are hard-coded in FXML/controllers.
12. `VaultService.createVault` creates the folder structure in the chosen folder before the registry and header; a failure in between leaves a folder that can be neither created into (`ALREADY_EXISTS`) nor opened (`NOT_A_VAULT`).
13. There is no background executor: `Background` starts one daemon thread per task. This plan satisfies "executor shut down cleanly" by proving no worker thread is alive after an idle close, instead of adding an executor.

---

## Child plans and execution order

| # | Plan | Tasks | Depends on |
|---|---|---|---|
| 1 | `2026-10-01-encryptdrive-v1.0.0-01-foundation.md` | T1–T3 | — |
| 2 | `2026-10-01-encryptdrive-v1.0.0-02-storage-security.md` | T4–T11 (incl. T6A) | 1 |
| 3 | `2026-10-01-encryptdrive-v1.0.0-03-file-manager.md` | T12–T19 | 2 (T10 engine, T11 guard) |
| 3A | `2026-10-05-encryptdrive-v1.0.0-03a-format-portability.md` | T19A–T19C | 3 |
| 4 | `2026-10-01-encryptdrive-v1.0.0-04-packaging-ci.md` | T20–T22, H2 | 1, 3, 3A (`FORMAT_V1_READY`) |
| 5 | `2026-10-01-encryptdrive-v1.0.0-05-release.md` | T23–T33, H3–H6 | 4 |

Roadmap after the current file-manager phase:

```text
Phase 03   File manager completion / current implementation
   ↓
Phase 03A  Performance + Format 1 + portability freeze
   ↓
Phase 04   Windows packaging / CI
   ↓
Phase 05   Release validation / RC / v1.0.0
```

All paths in the child plans are relative to the repository root.

### Task index (one commit per task unless marked "no commit")

| Task | Title | Commit message |
|---|---|---|
| T1 | Adopt spec/plans/ledger, archive superseded roadmap | `docs: adopt the v1.0.0 release spec and plan` |
| T2 | `.gitattributes`, `.gitignore`, local junk removal | `chore: normalize line endings and tidy ignore rules` |
| T3 | `1.0.0-SNAPSHOT` + generated version resource shown in Vault Settings | `feat: expose the canonical Maven version in the app` |
| T4 | Bounded metadata reads and writes | `fix: bound vault metadata reads and writes to the spec limits` |
| T5 | Strict parsing of `vault.json` and envelopes, wrapped-key size | `fix: parse unauthenticated vault metadata strictly` |
| T6 | Semantic validation of decrypted registry/manifest | `fix: reject structurally invalid registries and manifests` |
| T6A | Staged vault creation (nothing left behind on failure) | `fix: create vaults in a staging folder so a failed attempt leaves nothing behind` |
| T7 | 12-character account passwords, pre-release logins kept | `feat: require 12-character account passwords` |
| T8 | Registry backups reseeded on password change | `fix: purge old-password key envelopes from registry backups` |
| T9 | Encrypted pending-deletion journal + manifest checkpoint save | `feat: add an encrypted pending-deletion journal to manifests` |
| T10 | Crash-safe permanent deletion engine | `fix: make permanent deletion crash-safe` |
| T11 | One busy-aware close guard for every exit path | `fix: route every exit path through one busy-aware close guard` |
| T12 | Metadata work off the FX thread, session-bound FileService | `perf: load and change vault metadata off the JavaFX thread` |
| T13 | Rename | `feat: rename files and folders` |
| T14 | Move (multi-item, destination picker) | `feat: move files and folders` |
| T15 | Recursive folder import | `feat: import folders recursively without following links` |
| T16 | Refuse export into the vault | `fix: refuse to export plaintext into the vault folder` |
| T17 | Search | `feat: search active files across folders` |
| T18 | Empty Trash | `feat: empty the trash through the permanent-delete engine` |
| T19 | Streaming-memory and metadata-read regression guards | `test: guard streaming memory use and metadata reads` |
| T19A | Batched recursive folder-import manifest commits (maximum 64 logical mutations) | `perf: batch recursive folder-import manifest commits` |
| T19B | Freeze Vault Format 1 and publish conformance vectors | `test: freeze Vault Format 1 with public conformance vectors` |
| T19C | Cross-platform persistence audit and compatibility contract | `docs/test: freeze cross-platform Vault Format 1 compatibility` |
| T20 | WiX toolchain, icon, `build-release.ps1` | `build: produce versioned portable and installer artifacts` |
| T21 | `verify-package.ps1` (portable, ZIP, installer, silent install) | `build: verify portable and installer artifacts end to end` |
| T22 | CI packaging job + tag release workflow | `ci: package Windows artifacts and draft tagged releases` |
| H2 | **Human gate:** history rewrite decision + push approval | (rewrite has no commit of its own) |
| T23 | Dead code/resource audit | `refactor: remove unused code and resources` |
| T24 | Documentation + CHANGELOG + release checklist | `docs: document v1.0.0 editions, security semantics, and release process` |
| T25 | Gate A–C verification from a fresh worktree | no commit (ledger updated in T26) |
| T26 | Release version `1.0.0` | `chore: set release version 1.0.0` |
| T27 | Integrate into `main` | merge commit `Merge EncryptDrive v1.0.0 release work` |
| T28 | Annotated `v1.0.0-rc.1` | tag only |
| H3–H6 | **Human gates:** manual matrix (Windows, USB, OneDrive, clean Windows) | — |
| T29 | RC fix loop (only if a gate fails) | per fix |
| T30 | Annotated `v1.0.0` on the accepted RC commit | tag only |
| T31 | Final artifact smoke | — |
| T32 | Publish draft release | — |
| T33 | Archive v1.0.0 planning docs | `docs: archive the v1.0.0 planning documents` |

Count: 6 phases, 37 tasks — 31 commit tasks (the existing commit tasks plus T19A–T19C; T23 may end "audit clean" with no commit), 2 tag tasks (T28, T30), 4 verification/fix/publishing tasks (T25, T29 conditional, T31, T32) — plus 5 human gates (H1 = this plan review, H2–H6) and 3 conditional gates.

---

## Interface contract (names every task must use)

Produced by the task in brackets; consumed by later tasks.

**Repositories**
- `BoundedFiles.readUtf8(Path file, long maxBytes): String` throws `IOException, VaultStorageException` [T4]
- `BoundedFiles.requireWithin(byte[] bytes, long maxBytes)` throws `VaultStorageException(TOO_LARGE)` [T4]
- `VaultStorageException.Reason.TOO_LARGE` [T4]
- `VaultRepository.MAX_HEADER_BYTES = 256L * 1024` [T4]
- `UserRegistryRepository.MAX_REGISTRY_BYTES = 16L * 1024 * 1024` [T4]
- `ManifestRepository.MAX_MANIFEST_BYTES = 64L * 1024 * 1024` [T4]
- `AtomicFileWriter.copy(Path source, Path destination)` [T4]
- `BackupRotator.reseed(String name, byte[] content, Path backupDirectory, int generations)` [T8] — registry checkpoint: backups first, `users.enc` last; manifest checkpoint (T9): manifest first, then backups (spec 9.2)
- `MetadataJson.object(String json)`, `.integer(JsonObject, String)`, `.string(JsonObject, String)`, `.kdf(JsonObject, String)`, `.envelope(JsonObject, String)`, `.envelope(String json)` [T5]
- `UserRegistryRepository.saveCheckpoint(VaultContext, UserRegistry)` [T8]
- `ManifestRepository.saveCheckpoint(UserManifest, UUID manifestId, byte[] userMasterKey)` [T9]

**Security**
- Test seams: `AtomicFileWriter` non-final, `public UserRegistryRepository(AtomicFileWriter)`, package-private `BackupRotator(AtomicFileWriter)` and `AuthService(VaultContext, UserRegistryRepository)` [T8]; `VaultService` non-final with package-private `moveIntoPlace(Path, Path)` [T6A]; test helpers `FailingWriter` [T8], `ManifestLockProbe` [T12]
- `AesGcmService.WRAPPED_KEY_BYTES = 48`; `unwrapKey` rejects other ciphertext sizes before decrypting [T5]

**Models**
- `record PendingDeletion(UUID blobId, String queuedAt)` [T9]
- `UserManifest.getPendingDeletions(): List<PendingDeletion>` (never null) [T9]
- `record WorkspaceStats(int activeFileCount, long activePlainBytes, long encryptedBytes, int trashCount, int pendingDeletions)` [T12]

**Services**
- `AuthService.MIN_PASSWORD_LENGTH = 12` [T7]
- `FileServiceException.Reason`: adds `LIMIT` [T4], `INVALID_MOVE` [T14], `INSIDE_VAULT` [T15]
- `FileService.permanentlyDelete(List<UUID> entryIds): int` (blobs still queued) [T10]
- `FileService.resumePendingDeletions(): int` [T10]
- `FileService.folderView(UUID folderIdOrNull): FileService.FolderView` with `record FolderView(UUID folderId, List<ManifestEntry> path, List<ManifestEntry> children)` [T12]
- `FileService.rename(UUID entryId, String newName)` [T13]
- `FileService.move(List<UUID> entryIds, UUID destinationFolderId)`; `FileService.activeFolders(): List<ManifestEntry>` [T14]
- `FileService.importFolder(Path source, UUID parentFolderId, FileService.ImportProgress progress): FileService.FolderImport` with `interface ImportProgress { void update(int filesDone, int filesTotal, long bytesDone, long bytesTotal); }`, `record FolderImport(int foldersCreated, int filesImported, int linksSkipped, List<ImportFailure> failures, boolean stopped)` and `record ImportFailure(String path, FileServiceException.Reason reason)` [T15]
- private `FileService.requireOutsideVault(Path, boolean refuseAncestors)` [T15, used by T16]; private `FileService.deletePermanently(UserManifest, Collection<UUID>, byte[])` [T10, used by T18]
- `FileService.search(String query): List<FileService.SearchResult>` with `record SearchResult(ManifestEntry entry, List<String> folders)` [T17]
- `FileService.emptyTrash(): int` [T18]
- `ManifestService.requireMovable(UUID)`, `ManifestService.requireAvailableName(UUID parentId, String name, ManifestEntry ignored)`, `ManifestService.rename(UUID, String)` [T13], `ManifestService.move(List<UUID>, UUID)` [T14]
- `SessionService.copyUserMasterKey(UserSessionIdentity expected): SensitiveBytes` replaces the no-arg method [T12]
- `StreamingFileCryptoService.SourceReadException extends IOException` [T15]
- package-private `SourceTree` [T15]

**UI (package `com.fabianrodas.encryptdrive`)**
- `App.version(): String` [T3]; `App.BUSY_CLOSE_MESSAGE`, `App.installCloseGuard(Stage)`, `App.requestClose(Stage)`, `App.logout(): boolean`, `App.closeVault(): boolean` [T11]
- `Background.isBusy(): boolean` [T11]; `Background.read(Callable<T>, Consumer<T>, Consumer<Throwable>)` [T12]
- `DialogFactory.inform(Window, String, String)` [T11]; `DialogFactory.chooseFolder(Window, String, String, TreeItem<ManifestEntry>, String): Optional<ManifestEntry>` [T14]
- `ConfirmationPopupController.setInformational()` [T11], `.setFolderChoice(TreeItem<ManifestEntry>)`, `.getChosenFolder()` [T14]
- `Formats.CLEANUP_PENDING` [T10]
- `FilesController.describe(Throwable)` and private `change(Callable<T>, Consumer<T>)` [T12], `FilesController.describe(FileServiceException.Reason)` [T15], `FilesController.folderTree(List<ManifestEntry>, Set<UUID>)` [T14], `FilesController.location(List<String>)` [T17]
- FXML ids: `vault-settings.fxml` `appVersionLabel` [T3], `settingsCloseVaultButton` [T11]; `confirmation-popup.fxml` `cancelButton` [T11], `folderTree` [T14]; `files.fxml` `renameButton` [T13], `moveButton` [T14], `importMenu`/`importFilesItem`/`importFolderItem` [T15], `searchField`/`clearSearchButton`/`locationColumn` [T17]; `trash.fxml` `emptyTrashButton` [T18]

**Test support**
- `src/test/java/com/fabianrodas/services/TestVault.java` [T10]; `src/test/java/com/fabianrodas/services/CountingManifests.java` [T12]
- `FxTestSupport.showInStage(String fxml): Stage`, `FxTestSupport.holdBusy(): CountDownLatch`, `FxTestSupport.fireAndAnswer(Scene, String selector, Consumer<Stage> answer)`, `FxTestSupport.clickButton(Stage, String)` [T11]
- `src/test/java/com/fabianrodas/repositories/BoundedFilesTest.sparseFile(Path, long)` [T4]
- `src/test/java/com/fabianrodas/encryptdrive/ReleaseMetadataTest.pomVersion()` [T3]

**Scripts / CI**
- `scripts/build-release.ps1 [-Channel rc.N] [-Release] [-SkipTests] [-DryRun]` [T20]
- `scripts/verify-package.ps1 [-Channel rc.N] [-ArtifactDir <dir>] [-Launch] [-Install]` [T21]
- `packaging/windows/EncryptDrive.ico` [T20]
- `.github/workflows/build.yml` (jobs `verify`, `package`), `.github/workflows/release.yml` [T22]

---

## Per-task testing discipline (applies to every code task)

1. Write the focused failing test(s) listed in the task.
2. Run exactly those tests: `mvn -B -q test -Dtest=<Class>#<method>` (PowerShell: quote as `"-Dtest=Class#method"`). Confirm the stated failure reason.
3. Implement the smallest change.
4. Re-run the focused tests → PASS.
5. Run the related test classes listed in the task.
6. Run `mvn -B clean verify` → BUILD SUCCESS, 0 failures.
7. `git diff --stat` and `git diff` review: no unrelated changes, no debug output, no TODOs.
8. Update `docs/superpowers/plans/V1_RELEASE_STATE.md` (current task, last commit, tests last run + result).
9. Commit with the exact message (plus trailer). Stage files by name, never `git add -A`.

Unexpected failure at any step → superpowers:systematic-debugging before changing code. Before claiming any task/phase/gate complete → superpowers:verification-before-completion.

### Phase boundaries

At the end of plans 2, 3 and 4:
- `git worktree add ../EncryptDrive-phase-check HEAD` then `mvn -B clean verify` inside it; `git worktree remove ../EncryptDrive-phase-check`.
- superpowers:requesting-code-review over the phase's commit range; fix findings (each fix = its own commit `fix: <finding>` with a test).
- Ledger updated with the phase result.

---

## Release state machine

States are tracked in `V1_RELEASE_STATE.md` as `TODO | IN PROGRESS | DONE | BLOCKED (<reason>)`.

| State | Becomes DONE when |
|---|---|
| FORMAT_V1_READY | T19A–T19C complete; full suite and all Format 1 vector/portability tests pass; the 1,000- and 10,000-file benchmarks confirm batch scaling; working tree is clean. T20 is blocked until this is DONE. |
| IMPLEMENTATION_COMPLETE | T1–T24 (incl. T6A) committed; T25 audit finds no release-blocking issue (Gate A). |
| AUTOMATED_GATES_COMPLETE | On the `main` merge commit: fresh-worktree `mvn -B clean verify` green, 1 GiB test PASS, portable + installer build and `verify-package.ps1 -Launch -Install` PASS (Gate B). |
| PACKAGING_COMPLETE | T20–T22 done and the CI `package` job green on GitHub. |
| REPOSITORY_SECURITY_READY | H2 resolved; no plaintext DB in tree/packages; no secrets; `.gitattributes`/`.gitignore` clean; version `1.0.0`; docs/changelog complete (Gate C). |
| RC_CREATED | Annotated `v1.0.0-rc.N` pushed on `main` and the release workflow produced a draft prerelease with both artifacts + checksums (Gate D). |
| MANUAL_VALIDATION_COMPLETE | H3–H6 all PASS on the same RC (Gate E). |
| FINAL_TAG_CREATED | Annotated `v1.0.0` pushed on the accepted RC commit (Gate F). |
| FINAL_ARTIFACT_SMOKE_COMPLETE | Final draft artifacts installed + launched and portable-launched once (spec 22.5). |
| RELEASE_READY | Draft published; planning docs archived. |

No task may create `v1.0.0` before MANUAL_VALIDATION_COMPLETE is DONE. No task may create any tag before IMPLEMENTATION_COMPLETE, AUTOMATED_GATES_COMPLETE, PACKAGING_COMPLETE and REPOSITORY_SECURITY_READY are DONE and the release commit is on `main`.

---

## Human gates (the only planned stops)

| Gate | When | Why it cannot be autonomous | Blocks |
|---|---|---|---|
| H1 | Now | Superpowers plan review | everything |
| H2 | After T22, before the first push | Destructive history rewrite + force-push of `main`, and permission to push branch/tags to `origin` | REPOSITORY_SECURITY_READY, PACKAGING_COMPLETE (CI run), all tags |
| H3 | After T28 | Interactive GUI walkthrough of the installed app (spec 22.1) on a normal Windows machine | MANUAL_VALIDATION_COMPLETE |
| H4 | After T28 | Physical USB drive incl. unplug during I/O (spec 22.2) | MANUAL_VALIDATION_COMPLETE |
| H5 | After T28 | Real OneDrive sync across two computers (spec 22.3) | MANUAL_VALIDATION_COMPLETE |
| H6 | After T28 | Clean Windows without Java (spec 22.4) | MANUAL_VALIDATION_COMPLETE |
| (conditional) | T20 | Only if the no-admin WiX 3.14 binaries cannot be downloaded or run (.NET Framework 3.5 is already installed here, so this is not expected) | PACKAGING_COMPLETE |
| (conditional) | T31/T32 | Downloading/publishing draft release assets needs GitHub auth (no `gh` login here) | FINAL_ARTIFACT_SMOKE_COMPLETE, RELEASE_READY |
| (optional) | T20 | Authenticode certificate, only if the user wants signed artifacts | nothing (unsigned allowed) |

Each gate's exact checklist, evidence to send back, and resume point is in the child plan that owns it.

---

## Spec coverage map

| Spec section | Requirement | Task(s) |
|---|---|---|
| 1, 24 | Definition of Done | all; tracked by state machine |
| 2.1 #1–2 | Crash-safe permanent delete; backups cannot resurrect | T9, T10, T18 |
| 2.1 #3, 11 | Close/Alt+F4 race; safe shutdown | T11 |
| 2.1 #4, 8 | Registry backups after password change | T8 |
| 1 (crash-safe storage) | Vault creation is transactional (staging folder + one rename) | T6A |
| 14.1 | Dedicated task: no manifest read/write on the JavaFX thread, with boundary and per-flow tests | T12 (`BackgroundTest`, `FxThreadBoundaryTest`) |
| 2.1 #5, 7.1 | 12-char passwords; untrimmed; pre-release logins | T7 |
| 2.1 #6, 12 | Metadata limits before read; strict validation | T4, T5, T6 |
| 2.1 #7, 13 | Rename, move, folder import, search, empty trash | T13–T18 |
| 2.1 #8, 6.1–6.2 | Single version source; `1.0.0-SNAPSHOT`; UI version | T3, T20, T26 |
| 2.1 #9, 4.1, 5 | Installer with dir chooser, Start Menu, desktop choice, per-user, upgrade UUID, uninstall entry | T20, T21 |
| 2.1 #10, 16.2, 17 | Local junk removal; historical `data/users.json` | T2, H2, T25 |
| 2.1 #11, 16.1 | `.gitattributes`; proven-unused cleanup | T2, T23 |
| 2.1 #12, 22 | Manual validation matrix | H3–H6, T31 |
| 3 | Non-goals respected (no preview, no network, no recovery) | Global Constraints; T23 review |
| 4.2–4.3 | Portable edition; vault chosen in app | T20, T21, T24 |
| 5.1 | Packaging prerequisites (JDK 21, WiX) | T20, T22 |
| 5.2 | Signing-ready, no secrets, unsigned documented | T20, T21, T24 |
| 6.3–6.4 | RC tags, fix loop, final tag on accepted RC | T28–T30 |
| 7, 7.2 | Key hierarchy unchanged; KDF bounds before allocation | T5 (tests), existing `Argon2KeyDeriver` |
| 8.1–8.3 | Password change semantics; vault password change | T8, existing tests kept |
| 9.1–9.4 | Journal, 9-step sequence, crash behaviour, retry triggers | T9, T10, T12 (after login), T18 |
| 10.1–10.3 | Registry/manifest backup policy; recovery notice kept | T8, T9, T10 (recovery test) |
| 11 | Busy guard on X, Alt+F4, Logout, Close Vault; message; key wipe; lock release | T11 |
| 13.1 | Rename rules | T13 |
| 13.2 | Move rules | T14 |
| 13.3 | Folder import rules | T15 |
| 13.4 | Search rules | T17 |
| 13.5 | Empty Trash rules | T18 |
| 13.6 | Existing behaviour retained | existing tests + T12 regression |
| 14.1–14.3 | Streaming; off-FX-thread; throttled updates; no repeated reads; regression checks | T12, T19, T19A |
| Format 1 portability | Byte-level protocol, public vectors, host-independent persistence, logical-name contract | T19B, T19C |
| 15 | UX additions; 1000×600 + maximized | T3, T7, T11–T18 (`UiLayoutTest`) |
| 16.3 | One active plan chain; archive at release | T1, T33 |
| 18.1–18.3 | Release scripts, output dir, clean package verification | T20, T21 |
| 19.1–19.3 | Normal CI, Windows packaging CI, tag workflow (draft, prerelease) | T22 |
| 20 | Documentation set | T24 |
| 21.1–21.7 | Mandatory automated coverage | T3, T7, T8, T10, T11, T13–T20 |
| 23 Gates A–F | Release gates | T25–T32 |

## Five most dangerous failure modes not covered by the spec's test list (tests added)

| # | Failure mode | Consequence | Test added in |
|---|---|---|---|
| 1 | Folder import whose source contains (or is inside) the vault; export into the vault folder | Self-import of ciphertext/lock file; plaintext written into a OneDrive-synced vault | T15 `vaultInsideSourceIsRefused`, `sourceInsideVaultIsRefused`; T16 `exportIntoTheVaultIsRefused` |
| 2 | Metadata save larger than the new read limit | Next load rejects the file → silent rollback via backup recovery or account lockout | T4 `saveRefusesManifestOverTheLimit`, `saveRefusesRegistryOverTheLimit` |
| 3 | Title-bar `×` / Close Vault (Login, Register, Settings) bypassing the guard | Keys wiped mid-operation; login completing into a closed vault | T11 `everyTitleBarCloseButtonIsGuarded`, `closeVaultIsRefusedWhileBusy` |
| 4 | Pre-release accounts with < 12-char passwords locked out by the new minimum | Existing development vault accounts become unusable | T7 `preReleaseShortPasswordStillLogsIn` |
| 5 | Async UI work using a FileService bound to a previous session; in-process manifest read during a write | Wrong-key decrypt attempts after logout/login; read/rename race on Windows | T12 `keyCopyForAnotherIdentityIsRefused`, manifest loads serialized under `MANIFEST_LOCK` |
| 6 | Registry checkpoint fails or is interrupted part-way | Old-password envelope left in a recoverable backup with nothing to retry it; or failure reported for a change that took effect | T8: failure at each backup generation, immediately before the live write and during it (`FailingWriter`), plus `recoveryFromAPartiallyReseededStateRestoresTheNewestGeneration` (backups first, `users.enc` last) |
| 7 | Vault creation interrupted after the folders exist | Folder that can neither be created into nor opened | T6A `aFailedCreationLeavesNothingBehindAndCanBeRetried`, `anIncompleteLookingFolderWithAUserFileIsRefusedAndUntouched`, `aBareSkeletonIsNotReusedEither` |
| 8 | Tag workflow sees the pushed annotated tag as a plain commit ref (`actions/checkout` behaviour) | Every release tag rejected in CI, or the annotated-tag check silently meaningless | T22 fetches the tag object before `git cat-file -t`; first exercised by `v1.0.0-rc.1` (T28 Step 4) |

Also added: the purge never deletes a blob that a live entry still references (T10 `queuedBlobStillInUseIsNeverDeleted`).

## Known risks (tracked in the ledger)

- **Folder-import throughput.** T19 recorded the 1,000-file measurement; later measured scaling justified Phase 03A T19A. Recursive import now must commit at most 64 logical mutations per manifest save, while single-file import remains immediate.
- **Staged vault creation** renames a folder into place; a sync client or antivirus holding a handle inside the staging folder can make that rename fail. The failure is clean (staging removed, target untouched) and creation can be retried. H5 creates a vault inside OneDrive.
- **OneDrive / antivirus holding files open** can make `ATOMIC_MOVE` fail transiently on Windows. Not changed speculatively; H5 will show it. If it occurs, a bounded retry in `AtomicFileWriter.replace` becomes a release-fix task with a test.
- **OneDrive Files-On-Demand placeholders** are reparse points; T15 detects links by "real path differs from location", not by "is a reparse point", so placeholders still import. H5 includes importing a folder from OneDrive.
- **WiX 3.14**: .NET Framework 3.5 is already installed on this machine; the binaries ZIP needs no admin. Its download URL and SHA-256 are pinned in T20/T22.
- **Account size limit.** The contractual limit is the spec's 64 MiB encrypted-manifest bound (an estimated ~90,000 entries, not a guarantee); saves beyond it are refused with a clear message (T4) and the limit is documented (T24).
- **Silent installer switches** (`/qn`, `INSTALLDIR=`) for the jpackage EXE are verified for real in T21 Step 4, not assumed.
- **Plan verification.** The chain was read in full against the source and the build baseline by one reviewer in-session; the planned multi-agent adversarial verification could not run (session limit, then the workflow tool was unavailable). Per-task reviews during execution (subagent-driven development) are therefore mandatory, not optional.
- **CI desktop session** for the `-Launch`/`-Install` checks on GitHub runners is unverified until the first push (H2).
- **`-Install` on the dev machine** installs per-user (HKCU + Start Menu) and uninstalls again; a failed run is cleaned with the commands in T21.
- **History rewrite** changes every commit id from `0030c7c` on; forks/caches keep old objects; any reused password from the old dev DB must be changed by the user regardless.
- **JavaFX native cache**: the app extracts natives to `%USERPROFILE%\.openjfx\cache` on every machine it runs on (portable leaves this trace); documented in T24.
- **Export partial files**: a hard crash during export can leave a plaintext `<name>.<digits>.part` next to the chosen destination; documented in T24 (export is the deliberate plaintext path).
