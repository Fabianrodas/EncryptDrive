# EncryptDrive v1.0.0 — Release State

Updated after every task. Git history and test output are the record; this is the index.

## Position

| Key | Value |
|---|---|
| branch | `feature/encryptdrive-1.0` (unpushed) |
| maven version | `1.0.0` |
| current phase | 05 - Cleanup, documentation, release gates, and tagging |
| current task | T27: integrate into `main` after H2 is resolved; T25 Gates A–C and T26 are complete |
| last completed commit | T26 version release commit `b55d570` |
| tests last run | T26 `mvn -B clean verify` (2026-10-05, 2 min 52 s) |
| test result | BUILD SUCCESS - 412 tests, 0 failures, 0 errors, 3 skipped (opt-in LargeFileStreamingTest x2 and FolderImportBenchmarkTest) |

## Release states

| State | Status |
|---|---|
| FORMAT_V1_READY | DONE - T19A, T19B, and T19C verified and committed; all format and portability tests pass; clean full verification passed; benchmarks recorded; working tree clean after this commit |
| IMPLEMENTATION_COMPLETE | DONE - T1–T24 committed; T25 branch audit, hygiene, review, spec coverage, clean verification, large-file check, package rebuild, and current-artifact Setup.exe install/uninstall gate passed. Existing checkout used per the user's preference. |
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
| H2 local refs | `main` and cached `origin/main` both point to `0f913d25b82fea4a1d41605325fbe9e140654cf9`; `feature/encryptdrive-1.0` points to `26e3da9`. `data/users.json` exists at both `main` refs and not on the feature tip; no `v*` tags are present locally. No remote fetch was performed for this check. |
| WiX 3.14.1 binaries ZIP SHA-256 | `6AC824E1642D6F7277D0ED7EA09411A508F6116BA6FAE0AA5F2C7DAA2FF43D31` (official `wix314-binaries.zip`; candle/light 3.14.1.8722 verified locally) |
| folder-import benchmark (1,000 x 4 KiB) | 9,909 ms, 16 manifest saves, on 2026-10-05 after T19A; historical local pre-batch result was 48,467 ms (2026-10-04), so this run is 79.6% faster. The old run's environment was not recorded; compare directionally. |
| folder-import benchmark (10,000 x 4 KiB) | 157,926 ms, 157 manifest saves, on 2026-10-05 after T19A; no local pre-batch 10,000-file artifact was found. |
| folder-import benchmark with 10,000 existing manifest entries | 1,000 incoming x 4 KiB: 26,740 ms / 16 saves; 10,000 incoming x 4 KiB: 137,763 ms / 157 saves. |
| folder-import benchmark environment | Windows build 10.0.26200.9457, NTFS, Java 21.0.2 64-bit HotSpot, Intel64 Family 6 Model 154 Stepping 3, 16 logical processors. CPU marketing name was unavailable in the sandbox. The handoff-reported scaling (1,000 about 36 s through 8,000 about 910 s) has no local benchmark artifact in this checkout. |
| Format 1 conformance vectors | PASS - 2026-10-05; public fixtures under `test-vectors/format-v1/`, independent Argon2id/AES generation, repository/blob/name tests pass |
| dead-code/resource audit (T23) | PASS - all production classes, FXML/CSS resources, and CSS selectors have references; removed only unused `assertNull` static import from `FileServiceTest`; other heuristic member matches are initializer calls, and `FxTestSupport.fireAndAnswerPopup` is used by `UiFlowTest`. Remaining pattern matches are intentional: `users.json` packaging deny-list, `1.0-SNAPSHOT` regression assertion, and `System.exit` in the isolated streaming probe. |
| release documentation (T24) | PASS - README, SECURITY, VAULT_FORMAT, CHANGELOG, manual UI checklist, release checklist, and release-notes footer cover v1.0.0 editions, security semantics, metadata limits, manual gates, and checksums. Stale-text scan found no old password minimums or packaging script names; every relative link in the changed docs resolves. `mvn -B clean verify`: BUILD SUCCESS, 406 tests, 0 failures/errors, 3 opt-in skips. |
| whole-branch review (T25) | Fresh reviewer found one Important import/export path-swap race, fixed in `77b3310` with failing-then-passing regression tests and a clean full-suite run. No Critical finding. Two Minor findings were fixed in the local T25 follow-up: unsafe ZIP components are rejected before extraction and credential scans cover the full tree with one exact synthetic-fixture allowlist. Reviewer scope covered release automation, packaging, storage/security paths, and release docs/state, but not every JavaFX controller, resource, or test line. |
| post-review clean verification (T25) | PASS 2026-10-05 after `77b3310`: `mvn -B clean verify` → BUILD SUCCESS, 412 tests, 0 failures, 0 errors, 3 skipped; 2 min 59 s. |
| post-review 1 GiB test (T25) | PASS 2026-10-05 on the tree committed as `77b3310`: `mvn -B test "-Dtest=LargeFileStreamingTest" "-Dencryptdrive.largeFileCheck=true" "-DargLine=-Xmx256m"` → 2 tests, 0 failures/errors/skips, 66.52 s. |
| T25 package rebuild and portable launch | PASS 2026-10-05: `build-release.ps1 -SkipTests` produced `target/release/1.0.0-SNAPSHOT`; setup SHA-256 `0338018889b3acb9e2c4c486417dbd4aa5b0062eb93026d0087c8ac764392cdb`, portable ZIP `64b712bcde93dab60943d4baae2322bf13d080946bb5cddd84ded2210d41f3cd`; hardened verifier default and `-Launch` passed without system Java; installer signature `NotSigned`. |
| T25 Setup.exe install gate | PASS 2026-10-05 on the current artifact. `verify-package.ps1 -Launch -Install` returned 0 under a temporary local standard account whose token showed Medium integrity and enabled `BUILTIN\Users` membership, with no Administrators membership. Both portable and installed apps launched without system Java; the verifier confirmed install version, Start Menu shortcut, successful uninstall, and unchanged sentinel vault. Tested Setup SHA-256 `0338018889b3acb9e2c4c486417dbd4aa5b0062eb93026d0087c8ac764392cdb`; portable ZIP SHA-256 `64b712bcde93dab60943d4baae2322bf13d080946bb5cddd84ded2210d41f3cd`. Test account, profile, stage, install, and scratch were removed; no product registration or installer processes remain; `msiserver` restored to Stopped / Manual. |
| T25 embedded MSI isolation | PASS 2026-10-05: the exact `main.msi` extracted from the current Setup.exe passed direct silent install, installed-app launch without system Java, and direct uninstall. The sentinel vault hash remained unchanged, install folder was removed, and product registration was absent. This independently validates the payload; the Setup.exe wrapper gate is now separately passed. |
| T25 wrapper diagnosis | `build-release.ps1` and the verifier's `Test-Install` invocation are unchanged from `77b3310`; the child `msiexec` received `/qn`, `INSTALLDIR`, and `/l*v`. The default sandbox run's full MSI log locates failure at `JpFindRelatedProducts`: failure to create the custom-action server's primary token, then error 1719; the sandbox reports `CodexSandboxOffline` and Windows 8.1 compatibility properties. The elevated host-context child stalled before creating an MSI log; starting the service did not change this. Those attempts did not establish host behavior. The final standard-user run passed after setting the test process to that account's profile paths and the standard Windows PowerShell 5.1 module path; the verifier itself was unchanged. The test initially inherited the Codex caller's `TEMP` and PowerShell 7 module path, which prevented it from reaching Setup. |
| T25 repository hygiene | PASS for current tracked tree: no tracked secret/artifact candidates outside allowlisted synthetic Format 1 vectors; no private-key markers; password-assignment candidates are runtime variables or the synthetic Argon2 test fixture; 0 tracked CRLF files. `.superpowers/` and `target_test-classes/` remain ignored local outputs. Three `data/users.json` history commits remain, so H2 is unresolved. |
| T25 spec coverage re-check | Walked all 39 master-plan coverage rows against the 63 commits in `main..HEAD` and mapped tests; no uncovered implementation row found, and all 22 mapped release test classes are present. Release-only state-machine rows remain pending their later gates. |
| T25 reviewer minors | PASS in local commit `26e3da9`: a crafted archive with 9 traversal, dot, empty, alternate-stream, reserved-name, invalid-character, backslash, and outside-root entries is rejected before extraction; extracted content is required to have only the `EncryptDrive` root. Full-tree private-key scan found no markers; full-tree password-assignment scan found only the exact synthetic Argon2 test fixture line, which the runbook allowlists. |
| T26 release version | PASS 2026-10-05 in commit `b55d570`: `pom.xml` is `1.0.0`; `CHANGELOG.md` dates `[1.0.0]` to `2026-10-05`, the planned RC work date. |
| T26 release metadata regression | The first clean run found `ReleaseMetadataTest` attempting `Files.readString` on the `scripts/tests` directory added by T25. Updated the scan to include only regular files; focused `mvn -B -Dtest=ReleaseMetadataTest test` passed 3/3, then the full clean suite passed. |
| T26 clean verification | PASS 2026-10-05: `mvn -B clean verify` → BUILD SUCCESS, 412 tests, 0 failures, 0 errors, 3 opt-in skips; 2 min 52 s. Run in host context because sandbox Maven could not download the pinned plugins. |
| T26 RC dry run | PASS 2026-10-05: `build-release.ps1 -DryRun -Release -Channel rc.1` exited 0; derived installer `target/release/1.0.0/EncryptDrive-1.0.0-rc.1-Setup.exe`, portable ZIP, and checksums under `target/release/1.0.0`. No package was produced. |
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

1. H2: history-rewrite decision and permission for integration, push, and tags.
2. H3–H6: manual validation matrix on the accepted RC.

### T25 rulings and review record

- Ruling: T25 requested a fresh worktree; keep using the existing checkout because the user's earlier preference was to stay in this checkout; cost if wrong: a second checkout could reveal issues hidden by local generated state, although clean Maven verification and artifact rebuilds passed here.
- Final: fixed Important path-swap containment finding in `77b3310` — import, folder-import, and export race tests failed before the fix and passed after; full suite 412 tests, 0 failures/errors, 3 opt-in skips.
- Final: fixed Minor ZIP-layout finding in `26e3da9`: unsafe path components are rejected before extraction, and extraction-root contents are constrained to `EncryptDrive`; all 9 crafted regression paths were rejected and the real package passed verification.
- Final: fixed Minor credential-scan finding in the local follow-up: private-key and password checks cover the full tracked tree, with only the exact synthetic Argon2 fixture line allowlisted.

### T26 rulings

- Ruling: date the changelog `2026-10-05` as the planned RC work date because no separate RC date was supplied; cost if the RC schedule moves, the changelog date must be updated before tagging.
- Ruling: omit the plan's stale `Claude Opus 5.5` co-author trailer; this T26 work was performed by Codex and the commit uses the configured repository identity. Cost if wrong: that requested attribution is absent, while the recorded authorship remains accurate.
