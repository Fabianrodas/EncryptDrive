# EncryptDrive v1.0.0 Phase 03A: Performance, Format 1, and Portability

> **For agentic workers:** Use `superpowers:executing-plans` and `superpowers:test-driven-development`. Execute T19A, T19B, and T19C in order, as separate commits.

**Goal:** Make recursive imports scale by bounded manifest batches, freeze Vault Format 1 as a reproducible cross-language protocol, and document/test the host-independent compatibility contract before packaging.

**Architecture:** Keep Java as the official reference implementation and keep the future Rust CLI out of this release. Recursive folder import loads one manifest for the operation and commits at most 64 logical additions at a time, with finalized blobs preceding metadata. Format vectors exercise the existing crypto and persistence behavior; portability coverage checks decrypted persistent models and logical names.

**Tech Stack:** Java 21, JavaFX 21, Maven, JUnit, Gson, Bouncy Castle, AES-256-GCM, Argon2id.

**Spec:** `docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md`, amended by the Phase 03A requirements in this plan; see also `docs/VAULT_FORMAT.md` and `docs/COMPATIBILITY.md`.

## Global Constraints

- Maximum 64 logical manifest mutations per recursive-import save; files and folders, including empty folders, count.
- Normal single-file import keeps its existing immediate save behavior.
- A file is eligible for a manifest save only after streaming encryption authenticates and its blob is atomically finalized.
- A failed batch is invisible in the live manifest; clean its newly created blobs best-effort. Earlier successful batches stay valid. An orphan ciphertext is acceptable; metadata pointing at a missing blob is not.
- Preserve duplicates, invalid names, unreadable files, skipped links/reparse points, nesting, empty folders, progress, and intentional partial success.
- Do not add plaintext journals or caches. Filesystem and crypto work stays off the JavaFX Application Thread.
- Format 1 must be language- and operating-system-independent. Java Desktop remains the reference implementation; no Rust, JNI, or FFI is added.
- Desktop, future CLI, and vault-format versions have independent lifecycles.
- Do not choose a project license. License selection remains a human gate before public stable release.
- T20 packaging cannot start until `FORMAT_V1_READY = DONE` in `V1_RELEASE_STATE.md`.

## Review Focus

- A failed save at the first or a later batch boundary must not expose staged entries or delete blobs from earlier commits.
- Failure to delete an uncommitted blob may leave an orphan, but no persisted entry may refer to it.
- A tree with many empty directories must trigger the same bounded batch policy as a file-heavy tree.
- Names and collision behavior must be precisely reproducible without assuming the host filesystem's rules.
- Public vectors must be independently reproducible and must not depend on Java serialization internals as their expected values.

---

### Task T19A: Batch recursive folder-import manifest commits

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/FileService.java`
- Modify: `src/test/java/com/fabianrodas/services/FolderImportTest.java`
- Modify: `src/test/java/com/fabianrodas/services/MetadataLoadCountTest.java`
- Modify: `src/test/java/com/fabianrodas/services/FolderImportBenchmarkTest.java`
- Modify: `src/test/java/com/fabianrodas/services/CountingManifests.java` only if existing test-scoped save instrumentation needs a reset/accessor
- Modify: `docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md` sections 13.3 and 14.2-14.3
- Modify: `docs/superpowers/plans/V1_RELEASE_STATE.md`

**Interfaces:** Keep the public `FileService.importFolder(Path, UUID, ImportProgress)` and `FolderImport` signatures unchanged. Single-file `importFile` remains outside the batch mechanism. Use the existing `ManifestRepository.save` and test-scoped repository instrumentation unless investigation proves a safer existing seam.

- [x] **Step 1: Add failing behavioral tests before production edits**

Add coverage proving:

1. 64 logical mutations produce one manifest save.
2. 65 logical mutations produce two saves.
3. 130 logical mutations produce three saves.
4. A directory-only tree with at least 130 empty-folder mutations also scales by batches.
5. Failure of the first save exposes no staged entries and cleans the new batch's blobs best-effort.
6. Failure of a later save leaves the earlier batch visible and the failed batch invisible.
7. If blob cleanup fails, orphan ciphertext may remain but the manifest never references it.
8. A fresh service after an interrupted later batch sees only earlier committed batches.
9. Nested folders and empty folders remain correct across a batch boundary.
10. Progress still finishes at the expected file count and byte count.
11. Single-file import still commits immediately and keeps its existing behavior.
12. Recursive import decrypts the manifest once and manifest saves scale by `ceil(logical mutations / 64)`, not file count.

Use real temporary source trees, `TestVault`, `ManifestRepository`, and `BlobRepository`; count saves through test-scoped instrumentation. Add boundary tests with folder entries included in the mutation count. Do not test a private constant directly.

- [x] **Step 2: Run the focused tests and confirm the expected red**

Run: `mvn -B -q test "-Dtest=FolderImportTest,MetadataLoadCountTest"`

Expected: the new boundary/save-count and one-load assertions fail against the current per-entry implementation; existing import behavior tests continue to compile.

- [x] **Step 3: Implement bounded batching in `FileService`**

For one recursive import operation, acquire one user-key copy, load one manifest, and use one in-memory manifest while serializing the operation against other manifest changes. Append folder/file entries to a pending batch; flush when the batch reaches 64 and once at the end. Count only successfully persisted batches in `foldersCreated` and `filesImported`.

For each file, preserve the existing file-key generation, streaming AES-GCM authentication, and atomic blob commit. Add its manifest entry only after `writeBlob` succeeds. Save batch metadata with the repository's ordinary atomic `save` behavior so a failed current-manifest write leaves the prior live manifest usable; `saveCheckpoint` is intended for destructive deletion backup reseeding and writes the live manifest before reseeding backups.

On save failure, remove only entries added since the last successful batch, delete only blobs created by that batch best-effort, report the storage/limit failure, and stop. Do not roll back prior saved batches. Keep the normal `importFile` path unchanged.

- [x] **Step 4: Run focused and related regression tests**

Run: `mvn -B -q test "-Dtest=FolderImportTest,MetadataLoadCountTest,FileServiceTest,ManifestRepositoryTest,CorruptionIntegrationTest"`

Expected: all focused tests pass, including the requested failure/recovery cases and existing link, duplicate, progress, and authenticated-blob behavior.

- [x] **Step 5: Run performance measurements after correctness is green**

Run `FolderImportBenchmarkTest` for 1,000 x 4 KiB and 10,000 x 4 KiB. Also run 10,000 x 4 KiB with `encryptdrive.perfExisting` set to a representative large manifest if the benchmark still supports it. Record runtime, manifest-save count, operating system/filesystem/JDK/CPU, and comparison with the available pre-batch evidence. Do not add a wall-clock CI threshold. Do not rerun a prior pathological 10,000-file baseline if a valid local result is available.

- [x] **Step 6: Run full verification, review the diff, update the ledger, and commit**

Run: `mvn -B clean verify` (BUILD SUCCESS, zero failures/errors; opt-in tests may be skipped).

Review crash safety, orphan cleanup, partial-import counters, mutation boundaries, and single-file behavior. Update the ledger with the benchmark evidence and T19A result. Commit as `perf: batch recursive folder-import manifest commits` using the repository's current commit trailer convention.

**Acceptance:** Every recursive-import save contains at most 64 logical mutations; the manifest is loaded once; test and benchmark save counts grow by batches; all prior committed batches remain readable; full verification passes.

---

### Task T19B: Freeze Vault Format 1 and publish conformance vectors

**Files:**
- Modify: `docs/VAULT_FORMAT.md`
- Create: `test-vectors/format-v1/` public fixtures and their usage/readme
- Create: focused Java tests under `src/test/java/com/fabianrodas/` to consume the fixtures
- Modify: `docs/superpowers/plans/V1_RELEASE_STATE.md`

**Interfaces:** No production crypto change unless an approved security requirement conflicts with the current behavior. If a conflict is found, write its ruling in the ledger before a TDD change.

- [x] **Step 1: Inventory the current persisted bytes and add failing conformance tests**

Tests must consume exact public fixtures for Argon2id, UTF-8 AAD bytes, AES-256-GCM, wrapped keys, encrypted users/registry payload, encrypted manifest payload, encrypted file blob layout, authentication tampering, and logical-name/collision examples.

- [x] **Step 2: Verify that the fixtures fail for the expected missing/incorrect protocol assertions**

Run the new vector test class alone. Expected: compile/test failures identify the absent fixtures or uncovered protocol behavior, not malformed test setup.

- [x] **Step 3: Specify Format 1 byte behavior**

Document format-version meaning; UTF-8 and password-byte rules; exact AAD construction; UUID text; timestamp form; Base64 alphabet/padding; JSON encoding, required and unknown fields; integer bounds; nonce/tag lengths; encrypted payload and wrapped-key layouts; Argon2id version/parameters/salt/output; RMK/UMK/FDEK roles; users and manifest structures; blob layout; backups; pending deletions; and corruption/authentication failures. A Rust implementation must not need Java source to reproduce any byte-level rule.

- [x] **Step 4: Add deterministic public fixtures and independent verification**

Use fixed keys, salts, and nonces only in public tests. Verify expected values through a second implementation/library path where practical. Record how each vector was derived; expected data must not be generated from the Java production helper under test. Do not add deterministic randomness to production.

- [x] **Step 5: Run vector, crypto, persistence, and full-suite tests; review and commit**

Run the vector test and related crypto/repository tests, then `mvn -B clean verify`. Commit separately as `test: freeze Vault Format 1 with public conformance vectors` using the repository's current trailer convention.

**Acceptance:** An independent implementation can reproduce the documented byte protocol; Java tests consume the exact vectors; tampering fails authentication; no real credentials or vault data appear in fixtures.

---

### Task T19C: Audit portability and freeze compatibility contract

**Files:**
- Create or modify: `docs/COMPATIBILITY.md`
- Modify: `docs/VAULT_FORMAT.md` if the final logical-name contract belongs there
- Create: persistence portability tests under `src/test/java/com/fabianrodas/`
- Modify: `docs/superpowers/plans/V1_RELEASE_STATE.md`

**Interfaces:** Logical hierarchy uses IDs such as `entryId`, `parentId`, and `blobId`; no runtime `Path` may be serialized. The compatibility document must say Java Desktop 1.x uses Format 1, the future Rust CLI is planned and versioned independently, and no unreleased OS/CLI target is currently supported.

- [ ] **Step 1: Audit every persistent model and add a failing sentinel-path test**

Exercise vault/import paths with unique host-path sentinel components. Inspect decrypted registry and manifest models/JSON and prove the absolute host paths and sentinels are absent. Searching ciphertext alone does not satisfy this test.

- [ ] **Step 2: Inspect current logical-name and collision semantics**

Record the implemented behavior for Unicode normalization, case collisions, `/`, `\\`, `.`, `..`, controls, empty strings, trimming, trailing spaces/dots, Windows reserved names, and duplicate siblings. Do not silently substitute host-filesystem rules. If an incompatible behavior requires a Format 1 ruling, record the smallest ruling in the ledger and implement it test-first.

- [ ] **Step 3: Write the compatibility contract**

Distinguish EncryptDrive Desktop (Java reference implementation, v1.0.0 initially Windows), Vault Format 1 (independent version lifecycle), and the planned future Rust CLI (independent release lifecycle). State intended cross-version compatibility without claiming macOS, Linux, or CLI releases.

- [ ] **Step 4: Run portability, logical-name, conformance, and full-suite tests; review and commit**

Run related tests and `mvn -B clean verify`. Commit separately as `docs/test: freeze cross-platform Vault Format 1 compatibility` using the repository's current trailer convention.

**Acceptance:** Host paths remain runtime-only; the logical-name rules are explicit and test-backed; compatibility/version lifecycles are independent; full verification passes.

---

## Phase 03A completion gate

Set `FORMAT_V1_READY = DONE` only after T19A, T19B, and T19C are complete; all Format 1 and portability tests pass; `mvn -B clean verify` passes; the 1,000- and 10,000-file benchmark demonstrates batch scaling; benchmark evidence and any rulings are in the ledger; and the working tree is clean. T20 packaging starts only after this gate is satisfied.
