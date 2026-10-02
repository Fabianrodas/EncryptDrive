# EncryptDrive v1.0.0 — Release State

Updated after every task. Git history and test output are the record; this is the index.

## Position

| Key | Value |
|---|---|
| branch | `feature/encryptdrive-1.0` (unpushed) |
| maven version | `1.0.0-SNAPSHOT` (becomes `1.0.0` in T26) |
| current phase | 01-foundation done; next 02-storage-security |
| current task | T11 done (next: T12) |
| last completed commit | `df05109 fix: make permanent deletion crash-safe` |
| tests last run | `mvn -B clean verify` (2026-10-02, T11, 4 min 58 s) |
| test result | BUILD SUCCESS — 262 run, 0 failures, 0 errors, 1 skipped (opt-in LargeFileStreamingTest) |

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
| 1 GiB streaming test (`-Xmx256m`) | not run in this workstream |
| portable build | old app-image script only (jar name and app version now derived from the pom; replaced in T20) |
| installer build | TODO — WiX not installed locally (.NET Framework 3.5 present; no-admin binaries planned in T20) |
| WiX 3.14 binaries SHA-256 | (record at T20 Step 1) |
| folder-import benchmark (1,000 × 4 KiB) | (record at T19) |
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
