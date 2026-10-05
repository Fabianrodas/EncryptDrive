# EncryptDrive v1.0.0 — Release State

Updated after every task. Git history and test output are the record; this is the index.

## Position

| Key | Value |
|---|---|
| branch | `feature/encryptdrive-1.0` (unpushed) |
| maven version | `1.0.0-SNAPSHOT` (becomes `1.0.0` in T26) |
| current phase | 03A - performance, Format 1, and portability |
| current task | T19C in progress; T19A and T19B verified and committed; Phase 03 complete; T20 blocked on `FORMAT_V1_READY` |
| last completed commit | T19B (this commit; see HEAD) |
| tests last run | `mvn -B clean verify` (2026-10-05, 2 min 25 s) |
| test result | BUILD SUCCESS - 400 run, 0 failures, 0 errors, 3 skipped (opt-in LargeFileStreamingTest x2 and FolderImportBenchmarkTest) |

## Release states

| State | Status |
|---|---|
| FORMAT_V1_READY | IN PROGRESS - T19A and T19B are verified; T19C remains |
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
| folder-import benchmark (1,000 x 4 KiB) | 9,909 ms, 16 manifest saves, on 2026-10-05 after T19A; historical local pre-batch result was 48,467 ms (2026-10-04), so this run is 79.6% faster. The old run's environment was not recorded; compare directionally. |
| folder-import benchmark (10,000 x 4 KiB) | 157,926 ms, 157 manifest saves, on 2026-10-05 after T19A; no local pre-batch 10,000-file artifact was found. |
| folder-import benchmark with 10,000 existing manifest entries | 1,000 incoming x 4 KiB: 26,740 ms / 16 saves; 10,000 incoming x 4 KiB: 137,763 ms / 157 saves. |
| folder-import benchmark environment | Windows build 10.0.26200.9457, NTFS, Java 21.0.2 64-bit HotSpot, Intel64 Family 6 Model 154 Stepping 3, 16 logical processors. CPU marketing name was unavailable in the sandbox. The handoff-reported scaling (1,000 about 36 s through 8,000 about 910 s) has no local benchmark artifact in this checkout. |
| Format 1 conformance vectors | PASS - 2026-10-05; public fixtures under `test-vectors/format-v1/`, independent Argon2id/AES generation, repository/blob/name tests pass |
| host-path persistence and compatibility contract | TODO - T19C; `docs/COMPATIBILITY.md` does not exist yet |
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

1. `FORMAT_V1_READY`: T19C host-path persistence/compatibility tests and final full verification; T19A benchmarks and T19B vectors are recorded above.
2. H2 (later): history-rewrite decision and push permission.
3. H3–H6 (later): manual matrix on the accepted RC.
