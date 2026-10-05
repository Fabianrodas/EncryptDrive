# EncryptDrive v1.0.0 — Release State

Updated after every task. Git history and test output are the record; this is the index.

## Position

| Key | Value |
|---|---|
| branch | `feature/encryptdrive-1.0` (unpushed) |
| maven version | `1.0.0-SNAPSHOT` (becomes `1.0.0` in T26) |
| current phase | 04 - Windows packaging and CI |
| current task | T22 Step 6: H2 history and push gate; `FORMAT_V1_READY` is DONE |
| last completed commit | T22 review-fix commit `9f8aa01` (`build: clean up failed package smoke checks`) |
| tests last run | T22 `mvn -B clean verify` (2026-10-05, 4 min 8 s); final verifier changes then passed `ReleaseMetadataTest`, PowerShell parsing, and portable launch check |
| test result | BUILD SUCCESS - 406 run, 0 failures, 0 errors, 3 skipped (opt-in LargeFileStreamingTest x2 and FolderImportBenchmarkTest) |

## Release states

| State | Status |
|---|---|
| FORMAT_V1_READY | DONE - T19A, T19B, and T19C verified and committed; all format and portability tests pass; clean full verification passed; benchmarks recorded; working tree clean after this commit |
| IMPLEMENTATION_COMPLETE | TODO |
| AUTOMATED_GATES_COMPLETE | TODO |
| PACKAGING_COMPLETE | TODO - local T20/T21 build and end-to-end checks pass; mark DONE only after both CI jobs pass for the pushed branch head |
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
| portable build | PASS 2026-10-05: versioned ZIP is 35,807,920 bytes after T21 normalized ZIP entry names; app-image contains `app`, `runtime`, and `EncryptDrive.exe`; `EncryptDrive.cfg` names the app module and contains no machine JDK or user path |
| installer build | PASS 2026-10-05: versioned setup EXE is 37,786,112 bytes, produced by `jpackage` with WiX 3.14.1.8722; .NET Framework 3.5 is present |
| package checksums | Final T21 build `SHA256SUMS.txt`: setup `97f2c4ee32d94fea402bba4a0dce94b861b72a3b989b930b1f3bc83ac8e8679f`; portable `73fbdedb3b1285d7ed29eb4ac1bad6307db9b05e8b44036cbf23675d032b1d33` |
| release package verifier | T21 E2E evidence (pre-review build), PASS 2026-10-05: tampered setup checksum and packaged `users.json` each failed; standard-user `verify-package.ps1 -Launch -Install` completed; portable and installed app launches worked without system Java; installer version, Start Menu shortcut, uninstall, and sentinel vault preservation all verified; installer signature is `NotSigned` |
| T22 local artifact rebuild | `build-release.ps1 -SkipTests` succeeded; current setup SHA-256 `de9fa88e8ea56c576beb527b0411351cf9ad0c529da79562d5c012e07a3cb710`, portable ZIP `67cc38a2f84fea1282e2c17c5a9b189fcf9af6a3dd6aba1408405039a6dc4e94`; `verify-package.ps1 -Launch` passed and removed its scratch directory |
| T22 installer retry | Review-fix `-Launch -Install` did not complete on this host: sandboxed MSI returned 1603 with HKLM rollback-key access denied; unsandboxed MSI stalled without a log. Both exact test processes were stopped; no EncryptDrive uninstall entry or verifier scratch folder remained; `msiserver` returned to Stopped / Manual. T21's earlier full install/uninstall verification remains recorded above. |
| H2 local refs | `main` and cached `origin/main` both point to `0f913d25b82fea4a1d41605325fbe9e140654cf9`; `feature/encryptdrive-1.0` points to `9f8aa01c9e5c1cf405148e8a47378858021a6211`. `data/users.json` exists at both `main` refs and not on the feature tip; no `v*` tags are present locally. No remote fetch was performed for this check. |
| WiX 3.14.1 binaries ZIP SHA-256 | `6AC824E1642D6F7277D0ED7EA09411A508F6116BA6FAE0AA5F2C7DAA2FF43D31` (official `wix314-binaries.zip`; candle/light 3.14.1.8722 verified locally) |
| folder-import benchmark (1,000 x 4 KiB) | 9,909 ms, 16 manifest saves, on 2026-10-05 after T19A; historical local pre-batch result was 48,467 ms (2026-10-04), so this run is 79.6% faster. The old run's environment was not recorded; compare directionally. |
| folder-import benchmark (10,000 x 4 KiB) | 157,926 ms, 157 manifest saves, on 2026-10-05 after T19A; no local pre-batch 10,000-file artifact was found. |
| folder-import benchmark with 10,000 existing manifest entries | 1,000 incoming x 4 KiB: 26,740 ms / 16 saves; 10,000 incoming x 4 KiB: 137,763 ms / 157 saves. |
| folder-import benchmark environment | Windows build 10.0.26200.9457, NTFS, Java 21.0.2 64-bit HotSpot, Intel64 Family 6 Model 154 Stepping 3, 16 logical processors. CPU marketing name was unavailable in the sandbox. The handoff-reported scaling (1,000 about 36 s through 8,000 about 910 s) has no local benchmark artifact in this checkout. |
| Format 1 conformance vectors | PASS - 2026-10-05; public fixtures under `test-vectors/format-v1/`, independent Argon2id/AES generation, repository/blob/name tests pass |
| host-path persistence and compatibility contract | PASS - T19C; `docs/COMPATIBILITY.md`, `Format1PathPersistenceTest`, and logical-name tests pass on Windows 11 / NTFS / Java 21.0.2; no persistent model serializes a host `Path`; no format ruling required |
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

1. H2 (later): history-rewrite decision and push permission.
2. H3–H6 (later): manual matrix on the accepted RC.
