# EncryptDrive v1.0.0 — Plan 05: Cleanup, Documentation, Release Gates, and Tagging

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. T27 uses superpowers:finishing-a-development-branch.

**Goal:** Remove proven-unused code, complete the documentation set, pass Gates A–C from a fresh checkout, set version `1.0.0`, integrate into `main`, create `v1.0.0-rc.1`, drive the manual matrix (human gates H3–H6), then create `v1.0.0`, smoke the final artifacts, publish, and archive the plan chain.

**Architecture:** No new product code. Every release action is gated by the state machine in the master plan; every human action has an exact checklist, expected result, evidence and resume point.

**Tech Stack:** Git, Maven, PowerShell, GitHub Actions (public API for status), the scripts from Plan 04.

**Spec:** `docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md` sections 6, 16, 17, 20, 22, 23, 24.

## Global Constraints

See the master plan. No tag before T28's preconditions; never move or replace a tag; `v1.0.0` only on the accepted RC commit after H3–H6 pass. Every commit ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

---

### Task 23: Dead code and resource audit

**Purpose:** Spec 16.1, Gate A ("clean code/resource audit complete"). Remove only what is proven unused.

**Files:** whatever the audit proves unused (expected candidates: `FxTestSupport.fireAndAnswerPopup` if no longer called; any import or private member orphaned by Plans 02–04). Keep: `nbactions.xml` and the `javafx-maven-plugin` `ide-debug`/`ide-profile` executions (NetBeans), `FileService.listChildren`/`rootFolderId` (public API used by tests and `folderView`).

**Interfaces:** none.

- [x] **Step 1: Classes** — every production class is referenced outside its own file (Java or FXML `fx:controller`):

```bash
for f in $(git ls-files 'src/main/java/*.java' | grep -v module-info); do
  n=$(basename "$f" .java)
  c=$(git grep -lw "$n" -- src ':!'"$f" | wc -l)
  [ "$c" -eq 0 ] && echo "UNREFERENCED CLASS: $f"
done
```
Expected: no output.

- [x] **Step 2: FXML and CSS files**

```bash
for f in src/main/resources/com/fabianrodas/encryptdrive/*.fxml; do
  n=$(basename "$f" .fxml)
  git grep -q "\"$n\"\|\"$n.fxml\"" -- src/main/java src/test/java || echo "UNUSED FXML: $f"
done
for f in src/main/resources/com/fabianrodas/css/*.css; do
  n=$(basename "$f")
  git grep -q "$n" -- src/main/resources || echo "UNUSED CSS FILE: $f"
done
```
Expected: no output.

- [x] **Step 3: CSS selectors** — every `.class` and `#id` in each stylesheet appears in an FXML `styleClass`/`id`/`fx:id` or a Java string:

```bash
for css in src/main/resources/com/fabianrodas/css/*.css; do
  for sel in $(grep -oE '[.#][a-z][a-z0-9-]+' "$css" | sort -u); do
    name=${sel:1}
    case "$name" in root|button|label|text-field|password-field|table-view|table-row-cell|table-cell|column-header|column-header-background|filler|scroll-bar|thumb|track|increment-button|decrement-button|progress-bar|bar|text|content|viewport|scroll-pane|toggle-button|placeholder|arrow|arrow-button|menu-button|menu-item|context-menu|tree-view|tree-cell|list-cell|corner|show-hide-columns-button|nested-column-header|selected|focused|hover|armed|disabled|pressed) continue;; esac
    git grep -qE "styleClass=\"[^\"]*\b$name\b|id=\"$name\"|\"$name\"" -- src || echo "UNUSED SELECTOR: $css $sel"
  done
done
```
Expected: no output, or a list to remove (each removal re-checked visually in `UiLayoutTest` screens).

- [x] **Step 4: Unused imports and private members**

```bash
for f in $(git ls-files 'src/*.java'); do
  grep -oE '^import (static )?[a-zA-Z0-9_.]+\.([A-Za-z0-9_]+);' "$f" | sed -E 's/.*\.([A-Za-z0-9_]+);/\1/' | sort -u | while read n; do
    [ "$(grep -cw "$n" "$f")" -le 1 ] && echo "UNUSED IMPORT: $f $n"
  done
  grep -oE 'private (static )?(final )?[A-Za-z0-9_<>, ?\[\]]+ ([a-z][A-Za-z0-9_]*) ?[(=;]' "$f" | sed -E 's/.* ([a-z][A-Za-z0-9_]*) ?[(=;]$/\1/' | sort -u | while read n; do
    [ "$(grep -cw "$n" "$f")" -le 1 ] && echo "UNUSED PRIVATE: $f $n"
  done
done
```
Expected: no output; investigate each hit (FXML-injected `@FXML` fields count as used if their `fx:id` exists in the FXML).

- [x] **Step 5: Leftover patterns**

Run: `git grep -nE "TODO|FIXME|XXX|HACK|System\.exit|printStackTrace|1\.0-SNAPSHOT|users\.json" -- src scripts .github ':!docs/superpowers'`
Expected: no unexplained output. Keep `users.json` in `verify-package.ps1`'s forbidden-file list, the `1.0-SNAPSHOT` regression assertion in `ReleaseMetadataTest`, and `System.exit` in the isolated `StreamingMemoryProbe` child process.

- [x] **Step 6: Remove what was proven unused, verify, commit**

Run: `mvn -B clean verify` → BUILD SUCCESS. If nothing was found, skip the commit and record "audit clean" in the ledger.

```bash
git add <files changed by the audit> docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "refactor: remove unused code and resources" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** Steps 1–5 produce no unexplained output; build green.

---

### Task 24: Documentation, CHANGELOG, release checklist, release-notes footer

**Purpose:** Spec 15 (installer docs: installing never chooses or moves a vault), 20 (full documentation set), 5.2 (unsigned warning), 17 (exposure documented).

**Files:**
- Modify: `README.md`, `docs/SECURITY.md`, `docs/VAULT_FORMAT.md`, `docs/testing/manual-ui.md`
- Create: `CHANGELOG.md`, `docs/testing/release-checklist.md`, `docs/testing/release-notes-footer.md`

**Interfaces:** `release.yml` (T22) reads `CHANGELOG.md` section `## [<version>]` and `docs/testing/release-notes-footer.md`.

- [x] **Step 1: `CHANGELOG.md`**

```markdown
# Changelog

All notable changes to EncryptDrive. Versions follow MAJOR.MINOR.PATCH; release
candidates are Git tags `vX.Y.Z-rc.N` of the same version.

## [1.0.0] - Unreleased

First stable release.

### Added
- Encrypted vaults on any local, removable, or synced folder, with several
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
```
(T26 replaces `Unreleased` with the release date.)

- [x] **Step 2: `docs/testing/release-notes-footer.md`**

```markdown
### Downloads

- `EncryptDrive-<version>-Setup.exe` — installer for the current Windows user.
- `EncryptDrive-<version>-Windows-Portable.zip` — unzip anywhere (also a USB
  drive) and run `EncryptDrive\EncryptDrive.exe`.
- `SHA256SUMS.txt` — checksums of both files.

Neither edition needs Java. Installing or uninstalling EncryptDrive never
creates, moves, or deletes a vault: you choose vault locations inside the app.

### Verify your download

In PowerShell, in the download folder:

    Get-FileHash .\EncryptDrive-<version>-Setup.exe -Algorithm SHA256

The hash must equal the line for that file in `SHA256SUMS.txt` (letter case
does not matter). `certutil -hashfile <file> SHA256` works too.

### Unknown publisher warning

This release is not code-signed. Windows SmartScreen may say "Windows
protected your PC" and show an unknown publisher. Check the SHA-256 hash first;
then choose **More info → Run anyway**.
```

- [x] **Step 3: `README.md`** — rewrite these sections (keep logo, screenshots, author):
  - **Download and install:** the two editions; installer steps (unknown-publisher note, choose folder, Start Menu, optional desktop shortcut); portable steps; "Installing EncryptDrive does not choose, create, or move a vault; uninstalling it never deletes a vault."; checksum verification (point to the footer text).
  - **Create or open a vault:** unchanged meaning; vault password ≥ 12; no recovery warning kept verbatim.
  - **Accounts:** password ≥ 12 characters; accounts created by pre-release builds with shorter passwords still log in and should change their password.
  - **Files:** Import (Files…, Folder… — links/junctions skipped), Export (plaintext warning; not into the vault folder), New Folder, Rename, Move, Search (Enter to search, Clear to return; searches only your own files), Trash/Restore/Permanently Delete/Empty Trash.
  - **USB drives and OneDrive:** portable edition on the stick next to the vault; close the vault before ejecting; OneDrive single-writer rule in bold: "Never open the same synced vault on two computers at the same time. Close EncryptDrive and wait for sync to finish before switching computers."
  - **Versions and tags:** `1.0.0-SNAPSHOT` = development; `v1.0.0-rc.N` = release candidates (prerelease, for testing); `v1.0.0` = stable.
  - **Building:** `mvn javafx:run`, `mvn -B clean verify`, `scripts/build-release.ps1`, `scripts/verify-package.ps1 -Launch -Install`, WiX 3.14 requirement, the opt-in 1 GiB test command.
  - **Project structure:** add `packaging/windows/` and the two scripts.

- [x] **Step 4: `docs/SECURITY.md`** — update:
  - **Passwords:** both minimums 12; not trimmed; pre-release accounts keep working until changed.
  - **Password change (new subsection):** rewraps the same UMK; the active registry and all three registry backups are replaced, so the old password opens no retained generation; files are not re-encrypted; limitation: anyone who already extracted the UMK (memory compromise) keeps access; the backups are replaced before the live registry, so an interrupted change leaves the old password valid and must be repeated; copies of the vault kept outside EncryptDrive (OneDrive version history, your own backups, an attacker's earlier copy) still open with the old password. Keep the OneDrive version-history caveat explicit. State the size limit exactly as the spec defines it: one account's encrypted manifest may not exceed 64 MiB. That bound is the only contractual limit. If an entry count is mentioned at all, it must be worded as a rough estimate that depends on name lengths ("on the order of 90,000 files and folders with typical names"), never as a guaranteed or enforced number.
  - **Vault password change:** unchanged semantics (no `vault.json` backups).
  - **Integrity and recovery:** replace the paragraph "Registry backups can still contain an account key wrapped under that account's previous password…" with the new behaviour; state the size limits (256 KiB / 16 MiB / 64 MiB) and that oversize or malformed metadata is treated as damage.
  - **Permanent deletion (new subsection):** entries leave the manifest and all backups first; blobs are queued in the encrypted manifest and removed afterwards; retried after login, before/after later deletions and on Empty Trash; failure can leave orphaned ciphertext, never metadata that points at deleted data; SSD/flash secure erase is not guaranteed.
  - **Sessions:** Close, Log Out and Close Vault are blocked while a file operation runs; closing wipes user and vault keys and releases the lock.
  - **Import, export:** folder import never follows links/junctions and refuses folders that contain the vault; export refuses destinations inside the vault; an interrupted export can leave a `<name>.<digits>.part` plaintext file next to the chosen destination — delete it.
  - **Distribution:** installer vs portable; per-user install; uninstall keeps vaults; unsigned artifacts and SmartScreen; SHA-256 verification; JavaFX writes native libraries to `%USERPROFILE%\.openjfx\cache` on each computer where it runs (the portable edition leaves this trace on the host).
  - **History (new subsection):** an early development build stored a plaintext user list (`data/users.json`, with password hashes and salts) in this repository's history; current builds never use it and it is not packaged; outcome of H2 (rewritten on <date> / still in history); treat any password used for those development accounts as compromised.

- [x] **Step 5: `docs/VAULT_FORMAT.md`** — add:
  - Manifest member `pendingDeletions` (optional, array of `{ "blobId": "<uuid>", "queuedAt": "<instant>" }`), its meaning, and that format version stays `1` (older readers ignore it; absent means empty).
  - Size limits table (vault.json 256 KiB; users.enc and each backup 16 MiB; each manifest and backup 64 MiB) applied before reading and refused on writing.
  - Strictness: `vault.json` and envelopes require every member with its exact JSON type; decrypted registries/manifests must be well-formed (canonical ids, one root, reachable tree, complete file fields).
  - Backups: normal saves rotate three generations; password changes (registry) and permanent deletions (manifest) replace all generations with the new state.
  - Lifecycle of a permanent delete (9 steps) in one short list.

- [x] **Step 6: `docs/testing/manual-ui.md`** — add checklist items for Rename, Move (picker, invalid moves), Import Folder (progress, empty folders, junction skipped), Search (case-insensitive, location column, double-click, Clear), Empty Trash (count in confirmation), close guard (X / Alt+F4 / Log Out / Close Vault blocked during a long import with the exact message), Vault Settings version row, 12-character messages; extend the acceptance matrix with the new automated tests (`PermanentDeleteTest`, `RenameTest`, `MoveTest`, `FolderImportTest`, `SearchTest`, `CloseGuardTest`, `StreamingMemoryTest`, `MetadataLoadCountTest`) and point manual hardware rows to `release-checklist.md`.

- [x] **Step 7: `docs/testing/release-checklist.md`** — sections, in order:
  1. Release states (copy the table from the master plan).
  2. Gate commands (exact): fresh worktree verify; 1 GiB test (PowerShell form `mvn -B test "-Dtest=LargeFileStreamingTest" "-Dencryptdrive.largeFileCheck=true" "-DargLine=-Xmx256m"`); `build-release.ps1 -Release -Channel rc.N`; `verify-package.ps1 -Channel rc.N -Launch -Install`; hygiene commands from T25.
  3. Tagging: annotated tag commands for `v1.0.0-rc.N` and `v1.0.0` (from T28/T30), "never move a tag", RC fix loop (T29).
  4. Manual matrix: the H3, H4, H5, H6 checklists from this plan, verbatim, each with an evidence column.
  5. Final artifact smoke (T31) and publishing (T32).
  6. Release notes: the GitHub release body is the CHANGELOG section plus `release-notes-footer.md` (generated by `release.yml`); before publishing, replace `<version>` in the footer text if the draft shows it literally, and confirm the asset list.

- [x] **Step 8: Consistency checks**

```bash
git grep -nE "at least 8|8 characters|package-windows|verify-portable-package|1\.0-SNAPSHOT" -- README.md docs ':!docs/superpowers'
```
Expected: no output. Check every relative link in the changed docs resolves: `git grep -oE "\]\(([^)#]+)" -- README.md docs/*.md docs/testing/*.md` and test each path exists.

- [x] **Step 9: Verify + commit**

Run: `mvn -B clean verify` → BUILD SUCCESS.

```bash
git add README.md CHANGELOG.md docs/SECURITY.md docs/VAULT_FORMAT.md docs/testing/manual-ui.md docs/testing/release-checklist.md docs/testing/release-notes-footer.md docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "docs: document v1.0.0 editions, security semantics, and release process" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** every topic in spec 20 is covered; no stale numbers or script names; links resolve; build green. IMPLEMENTATION_COMPLETE can be evaluated in T25.

---

### Task 25: Gates A–C on the branch in the existing checkout (no commit of its own)

**Purpose:** Spec 23 Gates A–C before integration; superpowers:verification-before-completion and superpowers:requesting-code-review for the whole release branch.

- [x] **Step 1: Clean verify and package gates (existing checkout per user's preference)**

```powershell
mvn -B clean verify
mvn -B test "-Dtest=LargeFileStreamingTest" "-Dencryptdrive.largeFileCheck=true" "-DargLine=-Xmx256m"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1 -SkipTests
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-package.ps1 -Launch -Install
```
Expected: BUILD SUCCESS (0 failures); `LargeFileStreamingTest` `Tests run: 2, Failures: 0, Skipped: 0`; artifacts verified. Record counts and durations.

**T25 execution record (2026-10-05):** Per the user's prior preference, all commands ran in the existing checkout; no fresh worktree was created. `mvn -B clean verify` passed with 412 tests, 0 failures/errors, and 3 opt-in skips (2:59); the specified 1 GiB test passed 2/2 (66.52 s); `build-release.ps1 -SkipTests` succeeded; and `verify-package.ps1 -Launch` passed without system Java. Local verifier hardening and its nine-case ZIP regression are in `26e3da9`. The current-artifact `-Launch -Install` gate now passes as a standard user: the test token had Medium integrity and enabled `BUILTIN\Users` membership, with no Administrators membership. The unchanged verifier returned 0, reported the installer as `NotSigned`, launched both portable and installed apps without system Java, and completed its install version, Start Menu shortcut, uninstall, and sentinel-vault checks. The tested Setup SHA-256 is `0338018889b3acb9e2c4c486417dbd4aa5b0062eb93026d0087c8ac764392cdb`; the portable ZIP SHA-256 is `64b712bcde93dab60943d4baae2322bf13d080946bb5cddd84ded2210d41f3cd`. Source and staged artifact hashes matched. The temporary account, profile, package staging directory, install, and verifier scratch were removed; no product registration or installer processes remain, and `msiserver` is Stopped / Manual. Earlier sandbox evidence (1719 at `JpFindRelatedProducts`, sandbox identity and Windows 8.1 compatibility view) and an elevated-context stall were environment diagnostics only; the former valid host test needed its process environment set to the standard user's profile and Windows PowerShell 5.1 module path. The exact embedded MSI had also passed direct install/launch/uninstall with sentinel-vault preservation. Step 1 is complete in the existing checkout under the user's worktree preference.

- [x] **Step 2: Repository/security hygiene**

```bash
git status --porcelain --ignored                     # repository ignores: nb-configuration.xml and target/; local .superpowers/ and target_test-classes/ may also appear
git ls-files | grep -iE "\.(pfx|p12|pem|key|cer|jks|keystore)$|users\.json|\.enc$|\.edv$" | grep -vE '^test-vectors/format-v1/.*\.(enc|edv)$'   # expect: no output; public conformance vectors are allowlisted
git grep -nIE 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY' -- .   # expect: no output
git grep -nE 'password[[:space:]]*=[[:space:]]["][^"]+["]' -- . | grep -vF 'src/test/java/com/fabianrodas/security/Argon2KeyDeriverTest.java:48:'   # expect: no output; synthetic fixture allowlisted
git log --all --oneline -- data/users.json           # empty if H2 rewrote; otherwise the documented commits
cat .gitattributes; git ls-files --eol | awk '$2=="w/crlf"' | wc -l   # expect 0
```

**Result:** no tracked secret/artifact candidates outside public Format 1 vectors, no private-key markers, no literal credential assignments found beyond the synthetic test fixture, and 0 tracked CRLF files. H2 history still includes the three documented `data/users.json` commits. The credential scan covers the full tree, with only the exact synthetic test fixture line allowlisted. The local ZIP-layout regression rejects all 9 crafted unsafe entries before extraction; the current real package passes normal verification and portable launch.

- [x] **Step 3: Whole-branch review** — reviewer found one Important import/export path-swap race; TDD regressions failed before and passed after fix `77b3310`, followed by a 412-test clean suite. No Critical finding. The two Minor findings were fixed in local commit `26e3da9` and recorded in `V1_RELEASE_STATE.md`.

- [x] **Step 4: Spec coverage re-check** — all 39 master-plan map rows traced to the 63 branch commits and mapped tests; all 22 mapped release test classes exist. No uncovered implementation row found; later release-gate rows remain pending.

- [x] **Step 5: Ledger** — IMPLEMENTATION_COMPLETE = DONE (Gate A); record Gate B/C evidence gathered so far (final Gate B/C are re-run on `main` in T27).

**Acceptance:** all of the above green with evidence in the ledger.

---

### Task 26: Set the release version `1.0.0`

**Purpose:** Gate C ("version is release-ready"); RC and final tags are built from a non-SNAPSHOT version (`build-release.ps1 -Release`).

**Files:** `pom.xml` (`<version>1.0.0</version>`), `CHANGELOG.md` (`## [1.0.0] - <YYYY-MM-DD>` of the planned RC date), `ReleaseMetadataTest.java`, release checklist, and ledger.

- [x] **Step 1:** set the Maven version and changelog date to `1.0.0` and `2026-10-05`.
- [x] **Step 2:** `mvn -B clean verify` → BUILD SUCCESS: 412 tests, 0 failures/errors, 3 opt-in skips (2:52). The first run exposed `ReleaseMetadataTest` trying to read the new `scripts/tests` directory as a file; filtering the version scan to regular files fixed it, and the focused class passed 3/3.
- [x] **Step 3:** `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1 -DryRun -Release -Channel rc.1` → exit 0 and `installer=target/release/1.0.0/EncryptDrive-1.0.0-rc.1-Setup.exe`.
- [ ] **Step 4: Commit** — include the version update, regression fix, T25 evidence, and release checklist updates.

```bash
git add pom.xml CHANGELOG.md src/test/java/com/fabianrodas/encryptdrive/ReleaseMetadataTest.java docs/superpowers/plans/2026-10-01-encryptdrive-v1.0.0-05-release.md docs/superpowers/plans/V1_RELEASE_STATE.md docs/testing/release-checklist.md
git commit -m "chore: set release version 1.0.0"
```

---

### Task 27: Integrate into `main`

**Purpose:** Spec 6.3 ("release-ready commit is on main"); superpowers:finishing-a-development-branch.

- [ ] **Step 1:** `git checkout main && git merge --ff-only origin/main` (local `main` equals the possibly rewritten `origin/main`).
- [ ] **Step 2:** `git merge --no-ff feature/encryptdrive-1.0 -m "Merge EncryptDrive v1.0.0 release work" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"`
- [ ] **Step 3: Gate B on `main` in the existing checkout** — per the user's no-new-worktree preference, run `mvn -B clean verify`, the specified 1 GiB test, `build-release.ps1 -Release -Channel rc.1`, and `verify-package.ps1 -Channel rc.1 -Launch -Install` after the merge. Record the merge commit id and all results. AUTOMATED_GATES_COMPLETE = DONE.
- [ ] **Step 4:** `git push origin main` (approved at H2; if H2 option 3 was chosen, stop: Git-hosting human gate). Watch the `Build` workflow for the merge commit via the public API (T22 Step 6 command with `branch=main`); both jobs must succeed.
- [ ] **Step 5: Ledger** — REPOSITORY_SECURITY_READY = DONE when Gate C items are all satisfied (H2 resolved, hygiene clean, version `1.0.0`, docs complete); PACKAGING_COMPLETE confirmed on `main`. Commit the ledger on `main`: `git commit -m "docs: record v1.0.0 gate results" -m "Co-Authored-By: ..."` and push.

---

### Task 28: Create `v1.0.0-rc.1`

**Preconditions (all must be DONE in the ledger):** IMPLEMENTATION_COMPLETE, AUTOMATED_GATES_COMPLETE, PACKAGING_COMPLETE, REPOSITORY_SECURITY_READY; the 1 GiB test PASSED on the exact commit being tagged; that commit is on `origin/main`.

- [ ] **Step 1:** identify the commit: the merge commit from T27 (`git rev-parse <merge>`); confirm `git merge-base --is-ancestor <commit> origin/main`.
- [ ] **Step 2:** `git tag -a v1.0.0-rc.1 <commit> -m "EncryptDrive 1.0.0 release candidate 1"` then `git cat-file -t v1.0.0-rc.1` → `tag`.
- [ ] **Step 3:** `git push origin v1.0.0-rc.1`.
- [ ] **Step 4:** watch the `Release` workflow run for the tag:
  ```powershell
  (Invoke-RestMethod "https://api.github.com/repos/Fabianrodas/EncryptDrive/actions/runs?event=push&per_page=5").workflow_runs | Select-Object name, head_branch, status, conclusion, html_url
  ```
  Expected: `Release` / `v1.0.0-rc.1` / `completed` / `success`. (Drafts are invisible to the unauthenticated API; the user confirms the draft prerelease and its three assets at H3.)
- [ ] **Step 5:** ledger: RC_CREATED = DONE (`v1.0.0-rc.1` → `<commit>`); commit + push the ledger on `main`.
- [ ] **Step 6:** stop at Human Gates H3–H6 (all four may be done in parallel by the user).

If the workflow fails: superpowers:systematic-debugging; the fix goes through T29; the failed tag stays as it is (never moved) and the next candidate is `rc.2`.

---

### Human Gate H3: General Windows workflow (spec 22.1)

**Artifact:** from the GitHub draft prerelease `v1.0.0-rc.N` (repository → Releases → the draft): `EncryptDrive-1.0.0-rc.N-Setup.exe` and `SHA256SUMS.txt`.
**Machine:** your normal Windows 10/11 PC (this one is fine). Uninstall any earlier EncryptDrive first.
**Blocks release if any step fails:** yes — MANUAL_VALIDATION_COMPLETE stays BLOCKED.

| # | Step | Expected result |
|---|---|---|
| 1 | Confirm the draft shows 3 assets (Setup.exe, Portable.zip, SHA256SUMS.txt) and is marked pre-release. | As described. |
| 2 | `Get-FileHash .\EncryptDrive-1.0.0-rc.N-Setup.exe -Algorithm SHA256` | Equals the Setup line in `SHA256SUMS.txt`. |
| 3 | Run Setup.exe. | SmartScreen "unknown publisher" (unsigned) → More info → Run anyway → wizard opens, no administrator prompt. |
| 4 | Choose a non-default folder, e.g. `C:\Users\<you>\Apps\EncryptDrive Test`. | Folder chooser accepts it. |
| 5 | On the shortcut page tick Start Menu **and** Desktop; finish. | Installs; both shortcuts exist. |
| 6 | Start → EncryptDrive; close; then the desktop shortcut. | Both open Vault Selection. |
| 7 | Create a vault `Documents\ED-RC-Test` (password ≥ 12; try 11 first). | 11 is refused; 12+ creates the vault and shows the no-recovery warning. |
| 8 | Register `alice` and `bob` (try an 11-character password first). | 11 refused; both accounts created. |
| 9 | As alice: Import Files (2 files), Import Folder (a folder with a subfolder and an **empty** subfolder). | Progress shown; structure incl. empty folder appears; originals untouched. |
| 10 | As alice: Rename a file and a folder; try renaming to an existing name in other letter case. | Renames work; clash refused with a message. |
| 11 | As alice: Move a file into a folder; try moving a folder into its own subfolder. | Move works; invalid move refused. |
| 12 | As alice: search a name in lower case that exists in upper case inside a subfolder; double-click the result; Clear. | Found with LOCATION; double-click opens its folder; Clear returns. |
| 13 | Log out; log in as bob. | bob sees none of alice's files; search finds nothing of alice's. |
| 14 | As alice again: Move to Trash, Restore, Move to Trash again, Empty Trash. | Confirmation states the item count; trash empty afterwards. |
| 15 | During a long import (≥ 1 GB file) press the title-bar ×, then Alt+F4, then try Log Out/Close Vault. | All blocked; message "Please wait for the current file operation to finish before closing EncryptDrive."; sidebar disabled. |
| 16 | Export a file and a folder outside the vault; then try exporting into the vault folder itself. | Exports are byte-identical (compare with `Get-FileHash`); export into the vault is refused. |
| 17 | Profile → change alice's password (try 11 first). Log out, log in with old (fails) and new (works); export a file. | As stated. |
| 18 | Vault Settings → check the APP VERSION row; change the vault password; Close Vault; reopen with old (fails) and new (works). | Version shows `1.0.0`; password behaviour as stated. |
| 19 | Close EncryptDrive; start it again; open the vault; log in. | Everything still there. |
| 20 | Settings → Apps → EncryptDrive → Uninstall. | Uninstalls; Start Menu and desktop shortcuts gone; `Documents\ED-RC-Test` unchanged. |
| 21 | Reinstall with default options; open `Documents\ED-RC-Test`. | Opens; accounts and files intact. |

**Send back:** the table with PASS/FAIL per row (+ notes), the hash output from row 2, screenshots of any failure, and the Windows version (`winver`).

---

### Human Gate H4: Physical USB (spec 22.2)

**Artifact:** `EncryptDrive-1.0.0-rc.N-Windows-Portable.zip` (and the installed build from H3 on a second machine if available).
**Hardware:** a real USB flash drive or external disk; two Windows machines if possible (second may be any supported Windows PC).
**Blocks release:** yes.

| # | Step | Expected result |
|---|---|---|
| 1 | Unzip the portable ZIP to the USB drive (`E:\EncryptDrive\`). | `E:\EncryptDrive\EncryptDrive.exe` exists. |
| 2 | Run it from the USB drive; create vault `E:\USB-Vault`; register an account; import a few files. | Works. |
| 3 | Export a file to the desktop. | Byte-identical. |
| 4 | Close Vault, close EncryptDrive, "Safely remove" the drive. | Eject succeeds (no file in use). |
| 5 | Plug into the second machine; run `E:\EncryptDrive\EncryptDrive.exe`; open `E:\USB-Vault`. | Opens; files export correctly. |
| 6 | Start importing a large file (≥ 1 GB) and pull the USB drive out mid-way. | The app shows an error (no crash/hang > 30 s); closing is possible after the operation fails. |
| 7 | Reconnect; open the vault again. | Opens (possibly with the "restored from an automatic backup" notice); no entry for the interrupted file; all earlier files export correctly. |
| 8 | Repeat 6–7 with an export to the USB drive instead. | Same; no partial `.part` file remains in the vault. |

**Send back:** PASS/FAIL table, drive type/file system (`(Get-Volume E).FileSystem`), and screenshots of any failure.

---

### Human Gate H5: Real OneDrive synchronization (spec 22.3)

**Artifact:** installed or portable RC build on **two** computers signed into the **same** OneDrive account.
**Blocks release:** yes.

| # | Step | Expected result |
|---|---|---|
| 1 | Computer A: create vault `OneDrive\ED-Sync-Vault`; register an account; import files and a folder (also import a folder that lives in OneDrive, to confirm placeholders import). | Works. |
| 2 | Close EncryptDrive; wait until OneDrive shows "Up to date". | Synced. |
| 3 | In the OneDrive web UI, browse `ED-Sync-Vault`. | Only `.encryptdrive\…` (vault.json, users.enc, manifests, backups, lock) and `storage\blobs\…\*.edv`; no file or folder names from inside the vault. |
| 4 | Computer B: wait for sync; open the vault; log in; export a file. | Same accounts and files; export byte-identical. |
| 5 | Computer B: rename, move and add a file; close EncryptDrive; wait for sync. | Synced. |
| 6 | Computer A: open the vault. | Sees B's changes. |
| 7 | Read README "USB drives and OneDrive". | States clearly that the same vault must never be open on two computers at once. |
| 8 | (Observation) Note any OneDrive "file in use"/conflict copies or EncryptDrive write errors during steps 1–6. | None expected; any occurrence is reported. |

**Send back:** PASS/FAIL table, a screenshot of the OneDrive web listing (row 3), and any errors/conflict files seen.

---

### Human Gate H6: Clean Windows without Java (spec 22.4)

**Environment:** a Windows install with no Java (check `where java` prints nothing and no `JAVA_HOME`). Easiest on Windows 11 Pro: enable **Windows Sandbox** (Windows Features → Windows Sandbox; administrator; reboot), start it, and copy both artifacts into it. A spare PC or VM also works.
**Blocks release:** yes.

| # | Step | Expected result |
|---|---|---|
| 1 | `where java` and `echo %JAVA_HOME%` in cmd. | No Java found; variable empty. |
| 2 | Run Setup.exe; install with defaults. | Installs (SmartScreen note as in H3). |
| 3 | Start EncryptDrive from the Start Menu. | Vault Selection appears. |
| 4 | Unzip the portable ZIP to `C:\Portable`; run `C:\Portable\EncryptDrive\EncryptDrive.exe`. | Vault Selection appears. |
| 5 | Create a vault in `C:\Vaults\Clean`; register; import a file; export it. | Byte-identical export. |
| 6 | Close; uninstall from Settings → Apps. | Uninstall succeeds; `C:\Vaults\Clean` unchanged. |

**Send back:** PASS/FAIL table, the output of row 1, and the environment used (Sandbox/VM/PC + Windows version).

**Resume point after H3–H6:** if every row of every gate passed on the same RC → MANUAL_VALIDATION_COMPLETE = DONE → T30. Any failure → T29.

---

### Task 29: Release-candidate fix loop (only when a gate fails)

- [ ] **Step 1:** superpowers:systematic-debugging on the reported failure; reproduce with a test where at all possible.
- [ ] **Step 2:** `git checkout -b release-fix/rc.<N+1>-<topic> main`; TDD fix; `mvn -B clean verify`; commit `fix: <what>`.
- [ ] **Step 3:** merge into `main` (`--no-ff`), push, re-run T27 Step 3 (Gate B on `main`) and the 1 GiB test on the new `main` commit.
- [ ] **Step 4:** T28 with `v1.0.0-rc.<N+1>` on the new commit (the old tag stays untouched).
- [ ] **Step 5:** repeat **all** of H3–H6 on the new RC (any code fix resets Gate E; spec 23).

---

### Task 30: Create `v1.0.0` on the accepted RC commit

**Preconditions:** MANUAL_VALIDATION_COMPLETE = DONE for `v1.0.0-rc.N`; no source change since that RC (docs-only ledger commits on `main` are not part of the tagged commit).

- [ ] **Step 1:** `git rev-parse v1.0.0-rc.N^{commit}` → `<accepted>`.
- [ ] **Step 2:** `git tag -a v1.0.0 <accepted> -m "EncryptDrive 1.0.0"`; `git cat-file -t v1.0.0` → `tag`; `git rev-parse v1.0.0^{commit}` equals `<accepted>`.
- [ ] **Step 3:** `git push origin v1.0.0`; watch the `Release` run (T28 Step 4 command) → `success`. The workflow creates a **draft stable** release with `EncryptDrive-1.0.0-Setup.exe`, `EncryptDrive-1.0.0-Windows-Portable.zip`, `SHA256SUMS.txt`.
- [ ] **Step 4:** ledger FINAL_TAG_CREATED = DONE; commit + push the ledger on `main`.

---

### Task 31: Final artifact smoke (spec 22.5)

**Conditional human gate (GitHub auth):** draft assets can only be downloaded by a signed-in maintainer. Ask the user to download the three final assets from the `v1.0.0` draft into one folder (e.g. `C:\Users\<you>\Downloads\ED-1.0.0`) and tell you the path.

- [ ] **Step 1:** from the repository root on the `v1.0.0` commit (`git checkout v1.0.0` in a worktree so `pom.xml` says `1.0.0`):
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-package.ps1 -ArtifactDir "C:\Users\<you>\Downloads\ED-1.0.0" -Launch -Install
  ```
  Expected: checksums match, portable launches without Java, silent install/launch/uninstall pass, sentinel vault untouched → `Release artifacts verified`.
- [ ] **Step 2:** ledger FINAL_ARTIFACT_SMOKE_COMPLETE = DONE with the output.

---

### Task 32: Publish the release

**Human action (GitHub auth):** in the `v1.0.0` draft, check the notes (CHANGELOG section + footer; replace a literal `<version>` with `1.0.0` if shown), confirm the three assets, untick "pre-release", and click **Publish release**. Then send back the release URL.

- [ ] **Step 1:** confirm via `Invoke-RestMethod https://api.github.com/repos/Fabianrodas/EncryptDrive/releases/latest` → `tag_name` = `v1.0.0`, 3 assets.

---

### Task 33: Archive the v1.0.0 planning documents

**Purpose:** Spec 16.3 (archive active planning docs at release).

- [ ] **Step 1:**
```bash
git checkout main && git pull --ff-only
mkdir -p docs/superpowers/archive/v1.0.0
git mv docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md docs/superpowers/archive/v1.0.0/
git mv docs/superpowers/plans/2026-10-01-encryptdrive-v1.0.0-*.md docs/superpowers/archive/v1.0.0/
git mv docs/superpowers/plans/V1_RELEASE_STATE.md docs/superpowers/archive/v1.0.0/
```
- [ ] **Step 2:** final ledger edit: RELEASE_READY = DONE with the release URL.
- [ ] **Step 3:** `git grep -n "docs/superpowers/plans/\|docs/superpowers/specs/2026" -- ':!docs/superpowers/archive'` → no output (fix links if any).
- [ ] **Step 4:** commit + push:
```bash
git add docs/superpowers
git commit -m "docs: archive the v1.0.0 planning documents" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
git push origin main
```

**Acceptance (Definition of Done, spec 24):** every state in the ledger is DONE; `v1.0.0` (annotated) points to the accepted RC commit on `main`; the published release has both editions and checksums; planning documents archived.
