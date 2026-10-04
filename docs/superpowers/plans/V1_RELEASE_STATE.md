# EncryptDrive v1.0.0 — Release State

Updated after every task. Git history and test output are the record; this is the index.

## Position

| Key | Value |
|---|---|
| branch | `feature/encryptdrive-1.0` (unpushed) |
| maven version | `1.0.0-SNAPSHOT` (becomes `1.0.0` in T26) |
| current phase | 03-file-manager |
| current task | T19 done (phase 03 tasks complete; next: phase 03 boundary, then T20) |
| last completed commit | `fad2753 feat: empty the trash through the permanent-delete engine` |
| tests last run | `mvn -B clean verify` (2026-10-04, T19, 4 min 45 s) |
| test result | BUILD SUCCESS — 371 run, 0 failures, 0 errors, 3 skipped (opt-in LargeFileStreamingTest ×2 and FolderImportBenchmarkTest) |

## Release states

| State | Status |
|---|---|
| IMPLEMENTATION_COMPLETE | TODO |
| AUTOMATED_GATES_COMPLETE | TODO |
| PACKAGING_COMPLETE | TODO |
| REPOSITORY_SECURITY_READY | TODO |
| RC_CREATED | TODO |
| MANUAL_VALIDATION_COMPLETE | TODO |
| FINAL_TAG_CREATED | TODO |
| FINAL_ARTIFACT_SMOKE_COMPLETE | TODO |
| RELEASE_READY | TODO |

## Gates and evidence

| Item | Status |
|---|---|
| 1 GiB streaming test (`-Xmx256m`) | PASS 2026-10-04 at fad2753 plus the T19 tests (the T19 commit): `mvn -B test "-Dtest=LargeFileStreamingTest" "-Dencryptdrive.largeFileCheck=true" "-DargLine=-Xmx256m"` → Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, 104.9 s (crypto-only and full vault import/export). Development evidence: run again on the release commit before any RC tag (spec 21.7) |
| portable build | old app-image script only (jar name and app version now derived from the pom; replaced in T20) |
| installer build | TODO — WiX not installed locally (.NET Framework 3.5 present; no-admin binaries planned in T20) |
| WiX 3.14 binaries SHA-256 | (record at T20 Step 1) |
| folder-import benchmark (1,000 × 4 KiB) | 48,467 ms on 2026-10-04 (T19; `-Dencryptdrive.perfCheck=true`), under the 120,000 ms rule: the per-file commit model of spec 14.2 stays, no batching task |
| folder-import link/lock tests (T15) | all ran, none skipped, on Windows 11 / NTFS / JDK 21.0.2: junction (outside the tree and a loop back into it), symbolic link (file and directory — created because the build shell was elevated; Developer Mode is off, so a non-elevated run skips this one test), locked file, Kelvin-sign clash (file and folder), differently-cased vault path. Fix round 1, also all ran: vault reached through the `\\localhost\C$` share alias (reachable here; skips cleanly where the administrative share is not), folder swapped for a junction after the scan, source that is a junction with a missing target |
| history cleanup (`data/users.json`) | local copy deleted; still in history and in origin/main tip — decision at H2 |
| current RC tag | none |
| USB validation (H4) | TODO |
| OneDrive validation (H5) | TODO |
| clean-Windows validation (H6) | TODO |
| installer validation (H3) | TODO |
| portable validation (H4/H6) | TODO |
| final tag | none |

## Remaining blockers

1. H1: plan approved 2026-10-02; execution in progress.
2. H2 (later): history-rewrite decision and push permission.
3. H3–H6 (later): manual matrix on the accepted RC.
