# EncryptDrive 1.0.0 release checklist

Use this checklist for the release candidate and stable release. Record the
commit, command output, asset hashes, and human-gate evidence in
`docs/superpowers/plans/V1_RELEASE_STATE.md`. No tag may be created until the
tagging preconditions below are DONE. Never move or replace a tag.

## Release states

| State | Becomes DONE when |
|---|---|
| `FORMAT_V1_READY` | T19A–T19C complete; full suite and all Format 1 vector/portability tests pass; the 1,000- and 10,000-file benchmarks confirm batch scaling; working tree is clean. T20 is blocked until this is DONE. |
| `IMPLEMENTATION_COMPLETE` | T1–T24 (including T6A) committed; T25 audit finds no release-blocking issue (Gate A). |
| `AUTOMATED_GATES_COMPLETE` | On the `main` merge commit: fresh-worktree `mvn -B clean verify` green, 1 GiB test PASS, portable and installer builds plus `verify-package.ps1 -Launch -Install` PASS (Gate B). |
| `PACKAGING_COMPLETE` | T20–T22 done and the CI `package` job green on GitHub. |
| `REPOSITORY_SECURITY_READY` | H2 resolved; no plaintext DB in tree or packages; no secrets; `.gitattributes` and `.gitignore` clean; version `1.0.0`; docs and changelog complete (Gate C). |
| `RC_CREATED` | Annotated `v1.0.0-rc.N` pushed on `main` and the release workflow produced a draft prerelease with both artifacts and checksums (Gate D). |
| `MANUAL_VALIDATION_COMPLETE` | H3–H6 all PASS on the same RC (Gate E). |
| `FINAL_TAG_CREATED` | Annotated `v1.0.0` pushed on the accepted RC commit (Gate F). |
| `FINAL_ARTIFACT_SMOKE_COMPLETE` | Final draft artifacts installed and launched, and the portable edition launched once (spec 22.5). |
| `RELEASE_READY` | Draft published; planning documents archived. |

Do not create `v1.0.0` before `MANUAL_VALIDATION_COMPLETE` is DONE. Do not
create any tag before `IMPLEMENTATION_COMPLETE`, `AUTOMATED_GATES_COMPLETE`,
`PACKAGING_COMPLETE`, and `REPOSITORY_SECURITY_READY` are DONE and the release
commit is on `main`.

## Gate commands

### Fresh worktree verification

```powershell
git worktree add ..\EncryptDrive-gate HEAD
Push-Location ..\EncryptDrive-gate
mvn -B clean verify
mvn -B test "-Dtest=LargeFileStreamingTest" "-Dencryptdrive.largeFileCheck=true" "-DargLine=-Xmx256m"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1 -SkipTests
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-package.ps1 -Launch -Install
Pop-Location
git worktree remove ..\EncryptDrive-gate --force
```

Expected: build succeeds with zero failures; the 1 GiB test reports 2 tests,
0 failures, and 0 skipped; both artifacts verify. Record counts and durations.

### Release candidate package

Run from a clean checkout of the release commit with WiX 3.14 on `PATH`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1 -Release -Channel rc.N
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-package.ps1 -Channel rc.N -Launch -Install
```

Replace `N` with the candidate number. Record package names, hashes, sizes,
signature status, and verifier output.

### Repository and security hygiene

Run the following from the repository root. Review each result against its
expected output before tagging:

```bash
git status --porcelain --ignored                     # only nb-configuration.xml and target/ ignored
git ls-files | grep -iE "\.(pfx|p12|pem|key|cer|jks|keystore)$|users\.json|\.enc$|\.edv$"   # expect: no output
git grep -nIE "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|password\s*=\s*\"[^\"]+\"" -- . ':!docs' ':!src/test'   # expect: no output
git log --all --oneline -- data/users.json           # empty after H2 rewrite; otherwise document the retained history
cat .gitattributes; git ls-files --eol | awk '$2=="w/crlf"' | wc -l   # expect: 0
```

## Tagging and RC fix loop

Create an annotated RC tag only after all tag preconditions are DONE and the
accepted release commit is on `main`:

```bash
git tag -a v1.0.0-rc.N <commit> -m "EncryptDrive 1.0.0 release candidate N"
git cat-file -t v1.0.0-rc.N                 # expect: tag
git rev-parse v1.0.0-rc.N^{commit}          # confirm the intended commit
git push origin v1.0.0-rc.N
```

Never move or replace a tag. If any H3–H6 check fails, diagnose the failure,
make a fix on a new `release-fix/rc.<N+1>-<topic>` branch, repeat the automated
gates, and create `v1.0.0-rc.N+1` on the new accepted commit. The old RC tag
stays in place, and all H3–H6 checks must be repeated on the new RC.

After every human gate passes on the same RC, the stable tag points to that
accepted RC commit:

```bash
git rev-parse v1.0.0-rc.N^{commit}          # record as <accepted>
git tag -a v1.0.0 <accepted> -m "EncryptDrive 1.0.0"
git cat-file -t v1.0.0                     # expect: tag
git rev-parse v1.0.0^{commit}              # must equal <accepted>
git push origin v1.0.0
```

## Human Gate H3: General Windows workflow

**Artifact:** from the GitHub draft prerelease `v1.0.0-rc.N` (repository →
Releases → the draft): `EncryptDrive-1.0.0-rc.N-Setup.exe` and `SHA256SUMS.txt`.
**Machine:** your normal Windows 10/11 PC. Uninstall any earlier EncryptDrive
first. **Blocks release if any step fails:** yes.

| # | Step | Expected result | Evidence |
|---:|---|---|---|
| 1 | Confirm the draft shows 3 assets (Setup.exe, Portable.zip, SHA256SUMS.txt) and is marked pre-release. | As described. | |
| 2 | `Get-FileHash .\EncryptDrive-1.0.0-rc.N-Setup.exe -Algorithm SHA256` | Equals the Setup line in `SHA256SUMS.txt`. | |
| 3 | Run Setup.exe. | SmartScreen “unknown publisher” (unsigned) → More info → Run anyway → wizard opens, no administrator prompt. | |
| 4 | Choose a non-default folder, e.g. `C:\Users\<you>\Apps\EncryptDrive Test`. | Folder chooser accepts it. | |
| 5 | On the shortcut page tick Start Menu **and** Desktop; finish. | Installs; both shortcuts exist. | |
| 6 | Start → EncryptDrive; close; then the desktop shortcut. | Both open Vault Selection. | |
| 7 | Create a vault `Documents\ED-RC-Test` (password ≥ 12; try 11 first). | 11 is refused; 12+ creates the vault and shows the no-recovery warning. | |
| 8 | Register `alice` and `bob` (try an 11-character password first). | 11 refused; both accounts created. | |
| 9 | As alice: Import Files (2 files), Import Folder (a folder with a subfolder and an **empty** subfolder). | Progress shown; structure including the empty folder appears; originals untouched. | |
| 10 | As alice: Rename a file and a folder; try renaming to an existing name in other letter case. | Renames work; clash refused with a message. | |
| 11 | As alice: Move a file into a folder; try moving a folder into its own subfolder. | Move works; invalid move refused. | |
| 12 | As alice: search a name in lower case that exists in upper case inside a subfolder; double-click the result; Clear. | Found with LOCATION; double-click opens its folder; Clear returns. | |
| 13 | Log out; log in as bob. | bob sees none of alice's files; search finds nothing of alice's. | |
| 14 | As alice again: Move to Trash, Restore, Move to Trash again, Empty Trash. | Confirmation states the item count; Trash empty afterwards. | |
| 15 | During a long import (≥ 1 GB file) press the title-bar ×, then Alt+F4, then try Log Out/Close Vault. | All blocked; message “Please wait for the current file operation to finish before closing EncryptDrive.”; sidebar disabled. | |
| 16 | Export a file and a folder outside the vault; then try exporting into the vault folder itself. | Exports are byte-identical (compare with `Get-FileHash`); export into the vault is refused. | |
| 17 | Profile → change alice's password (try 11 first). Log out, log in with old (fails) and new (works); export a file. | As stated. | |
| 18 | Vault Settings → check the APP VERSION row; change the vault password; Close Vault; reopen with old (fails) and new (works). | Version shows `1.0.0`; password behaviour as stated. | |
| 19 | Close EncryptDrive; start it again; open the vault; log in. | Everything still there. | |
| 20 | Settings → Apps → EncryptDrive → Uninstall. | Uninstalls; Start Menu and desktop shortcuts gone; `Documents\ED-RC-Test` unchanged. | |
| 21 | Reinstall with default options; open `Documents\ED-RC-Test`. | Opens; accounts and files intact. | |

Send back the PASS/FAIL table with notes, the hash output from row 2,
screenshots of any failure, and the Windows version (`winver`).

## Human Gate H4: Physical USB

**Artifact:** `EncryptDrive-1.0.0-rc.N-Windows-Portable.zip` (and the installed
build from H3 on a second machine if available). **Hardware:** a real USB flash
drive or external disk; two Windows machines if possible. **Blocks release:**
yes.

| # | Step | Expected result | Evidence |
|---:|---|---|---|
| 1 | Unzip the portable ZIP to the USB drive (`E:\EncryptDrive\`). | `E:\EncryptDrive\EncryptDrive.exe` exists. | |
| 2 | Run it from the USB drive; create vault `E:\USB-Vault`; register an account; import a few files. | Works. | |
| 3 | Export a file to the desktop. | Byte-identical. | |
| 4 | Close Vault, close EncryptDrive, “Safely remove” the drive. | Eject succeeds (no file in use). | |
| 5 | Plug into the second machine; run `E:\EncryptDrive\EncryptDrive.exe`; open `E:\USB-Vault`. | Opens; files export correctly. | |
| 6 | Start importing a large file (≥ 1 GB) and pull the USB drive out mid-way. | The app shows an error (no crash/hang > 30 s); closing is possible after the operation fails. | |
| 7 | Reconnect; open the vault again. | Opens (possibly with the “restored from an automatic backup” notice); no entry for the interrupted file; all earlier files export correctly. | |
| 8 | Repeat 6–7 with an export to the USB drive instead. | Same; no partial `.part` file remains in the vault. | |

Send back the PASS/FAIL table, drive type and file system
(`(Get-Volume E).FileSystem`), and screenshots of any failure.

## Human Gate H5: Real OneDrive synchronization

**Artifact:** installed or portable RC build on **two** computers signed into
the **same** OneDrive account. **Blocks release:** yes.

| # | Step | Expected result | Evidence |
|---:|---|---|---|
| 1 | Computer A: create vault `OneDrive\ED-Sync-Vault`; register an account; import files and a folder (also import a folder that lives in OneDrive, to confirm placeholders import). | Works. | |
| 2 | Close EncryptDrive; wait until OneDrive shows “Up to date”. | Synced. | |
| 3 | In the OneDrive web UI, browse `ED-Sync-Vault`. | Only `.encryptdrive\…` (vault.json, users.enc, manifests, backups, lock) and `storage\blobs\…\*.edv`; no file or folder names from inside the vault. | |
| 4 | Computer B: wait for sync; open the vault; log in; export a file. | Same accounts and files; export byte-identical. | |
| 5 | Computer B: rename, move and add a file; close EncryptDrive; wait for sync. | Synced. | |
| 6 | Computer A: open the vault. | Sees B's changes. | |
| 7 | Read README “USB drives and OneDrive”. | States clearly that the same vault must never be open on two computers at once. | |
| 8 | (Observation) Note any OneDrive “file in use”/conflict copies or EncryptDrive write errors during steps 1–6. | None expected; any occurrence is reported. | |

Send back the PASS/FAIL table, a screenshot of the OneDrive web listing (row 3),
and any errors or conflict files seen.

## Human Gate H6: Clean Windows without Java

**Environment:** a Windows install with no Java (check `where java` prints
nothing and no `JAVA_HOME`). Easiest on Windows 11 Pro: enable Windows Sandbox
(Windows Features → Windows Sandbox; administrator; reboot), start it, and copy
both artifacts into it. A spare PC or VM also works. **Blocks release:** yes.

| # | Step | Expected result | Evidence |
|---:|---|---|---|
| 1 | `where java` and `echo %JAVA_HOME%` in cmd. | No Java found; variable empty. | |
| 2 | Run Setup.exe; install with defaults. | Installs (SmartScreen note as in H3). | |
| 3 | Start EncryptDrive from the Start Menu. | Vault Selection appears. | |
| 4 | Unzip the portable ZIP to `C:\Portable`; run `C:\Portable\EncryptDrive\EncryptDrive.exe`. | Vault Selection appears. | |
| 5 | Create a vault in `C:\Vaults\Clean`; register; import a file; export it. | Byte-identical export. | |
| 6 | Close; uninstall from Settings → Apps. | Uninstall succeeds; `C:\Vaults\Clean` unchanged. | |

Send back the PASS/FAIL table, output of row 1, and the environment used
(Sandbox, VM, or PC plus Windows version).

## Final artifact smoke and publishing

After `v1.0.0` is tagged and the workflow creates a draft stable release, download
the three assets into one folder. From a worktree at the `v1.0.0` commit, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-package.ps1 -ArtifactDir "<download-folder>" -Launch -Install
```

Expected: checksums match; the portable edition launches without Java; silent
install, launch, and uninstall pass; the sentinel vault is unchanged. Record
the output and mark `FINAL_ARTIFACT_SMOKE_COMPLETE` DONE.

### Release notes and publish

The `release.yml` workflow generates the GitHub release body from the
CHANGELOG section for the version plus `docs/testing/release-notes-footer.md`.
Before publishing the draft, replace a literal `<version>` with `1.0.0` if
shown, and confirm the three assets are Setup.exe, Portable.zip, and
`SHA256SUMS.txt`. Untick “pre-release” and publish. Then verify the latest
release:

```powershell
(Invoke-RestMethod https://api.github.com/repos/Fabianrodas/EncryptDrive/releases/latest).tag_name
```

It must be `v1.0.0`, with three assets. Record the release URL and mark
`RELEASE_READY` DONE after the planning documents are archived.
