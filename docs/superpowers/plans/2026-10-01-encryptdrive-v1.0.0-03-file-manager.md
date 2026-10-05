# EncryptDrive v1.0.0 — Plan 03: File Manager Completion

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all vault metadata work off the JavaFX thread, then add rename, move, recursive folder import, search and Empty Trash, refuse plaintext export into the vault, and add regression guards for streaming memory and metadata reads.

**Architecture at Phase 03 completion:** Every controller call into `FileService` goes through `Background` (`run` for changes — counted as busy so the close guard applies; `read` for read-only loads — not counted). Tree rules live in `ManifestService` (`requireMovable`, `rename`, `move`), orchestration in `FileService`. The original folder-import implementation reused the per-file `importFile` transaction; Phase 03A T19A supersedes that choice with bounded batches for recursive imports. Normal single-file imports remain immediate. Rename/move/search never touch blobs.

**Tech Stack:** JavaFX 21 (`Task`, `TreeView`, `MenuButton`), Java NIO, JUnit 6, `cmd /c mklink /J` for junction tests on Windows.

**Spec:** `docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md` sections 9.4, 13, 14, 15, 21.5.

## Global Constraints

See the master plan. Rename and move are metadata-only. No search index, no plaintext cache, no preview. JavaFX `Task.updateProgress/updateMessage` already coalesce UI updates (one pending `runLater` at a time), which satisfies spec 14.1's throttling; do not add another throttle. Every commit ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

---

### Task 12: Vault metadata work off the JavaFX thread; session-bound FileService

**Purpose:** The dedicated task for JavaFX-thread I/O. Spec 14.1: no manifest decrypt, read or write and no other filesystem/crypto work on the JavaFX Application Thread — folder listing and navigation, folder creation, Move to Trash, Trash loading, Restore, Permanent Delete, export-target resolution and Overview statistics all move behind `Background`. Also: metadata not re-read within one operation, 9.4 (retry pending deletions after login), and dangerous failure mode #5 (stale session).

**Files:**
- Modify: `src/main/java/com/fabianrodas/encryptdrive/Background.java` (`read`)
- Modify: `src/main/java/com/fabianrodas/services/FileService.java` (`FolderView`, `folderView`, `load` under `MANIFEST_LOCK`, `forCurrentSession`, `stats` counts journal, remove `pathTo`)
- Modify: `src/main/java/com/fabianrodas/services/SessionService.java` (`copyUserMasterKey(UserSessionIdentity)`)
- Modify: `src/main/java/com/fabianrodas/models/WorkspaceStats.java` (`pendingDeletions`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/FilesController.java`, `TrashController.java`, `OverviewController.java`
- Create: `src/test/java/com/fabianrodas/services/CountingManifests.java`, `src/test/java/com/fabianrodas/services/FolderViewTest.java`
- Create: `src/test/java/com/fabianrodas/services/ManifestLockProbe.java`, `src/test/java/com/fabianrodas/encryptdrive/BackgroundTest.java`, `src/test/java/com/fabianrodas/encryptdrive/FxThreadBoundaryTest.java`
- Modify tests: `SessionServiceTest.java`, `FileServiceTest.java` (stats), `PermanentDeleteTest.java` (stats), `UiFlowTest.java`

**Interfaces:**
- Consumes: `FileService.resumePendingDeletions()` (T10), `Formats.CLEANUP_PENDING` (T10).
- Produces: `Background.read(Callable<T>, Consumer<T>, Consumer<Throwable>)`; `record FileService.FolderView(UUID folderId, List<ManifestEntry> path, List<ManifestEntry> children)`; `FolderView FileService.folderView(UUID)`; `SessionService.copyUserMasterKey(UserSessionIdentity)`; `WorkspaceStats.pendingDeletions()`; `static String FilesController.describe(Throwable)`; private `FilesController.change(Callable<T>, Consumer<T>)` (used by T13–T17); test helper `CountingManifests`.

**Security:** reads are serialized with writes in-process (`MANIFEST_LOCK` around every manifest load), so a read never overlaps a replace. A `FileService` from a previous session refuses the new session's key.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/fabianrodas/services/CountingManifests.java`:

```java
package com.fabianrodas.services;

import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts manifest decryptions, to catch operations that re-read metadata. */
final class CountingManifests extends ManifestRepository {

    final AtomicInteger loads = new AtomicInteger();

    CountingManifests(VaultContext vault) {
        super(vault);
    }

    @Override
    public UserManifest load(UUID userId, UUID manifestId, byte[] userMasterKey) throws VaultStorageException {
        loads.incrementAndGet();
        return super.load(userId, manifestId, userMasterKey);
    }
}
```

`src/test/java/com/fabianrodas/services/FolderViewTest.java`:

```java
package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fabianrodas.models.ManifestEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderViewTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account alice;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.register("alice");
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void pathAndSortedChildrenComeFromOneManifestRead() throws Exception {
        CountingManifests counting = new CountingManifests(vault.vault);
        FileService files = vault.files(alice, counting);
        UUID root = files.rootFolderId();
        ManifestEntry docs = files.createFolder("Docs", root);
        vault.importText(files, "b.txt", docs.getEntryId());
        files.createFolder("a-folder", docs.getEntryId());
        files.createFolder("Z-folder", docs.getEntryId());
        counting.loads.set(0);

        FileService.FolderView view = files.folderView(docs.getEntryId());

        assertEquals(1, counting.loads.get());
        assertEquals(docs.getEntryId(), view.folderId());
        assertEquals(List.of(root, docs.getEntryId()), ids(view.path()));
        assertEquals(List.of("a-folder", "Z-folder", "b.txt"), names(view.children()));
    }

    @Test
    void missingOrTrashedFoldersShowTheRoot() throws Exception {
        FileService files = vault.files(alice);
        UUID root = files.rootFolderId();
        ManifestEntry docs = files.createFolder("Docs", root);
        files.moveToTrash(docs.getEntryId());

        assertEquals(root, files.folderView(docs.getEntryId()).folderId());
        assertEquals(root, files.folderView(UUID.randomUUID()).folderId());
        assertEquals(root, files.folderView(null).folderId());
        assertEquals(List.of(root), ids(files.folderView(null).path()));
    }

    private static List<UUID> ids(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getEntryId).toList();
    }

    private static List<String> names(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getName).toList();
    }
}
```

`src/test/java/com/fabianrodas/encryptdrive/BackgroundTest.java` (the background boundary itself):

```java
package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BackgroundTest {

    @BeforeAll
    static void startJavaFx() {
        assumeTrue(FxTestSupport.start(), "JavaFX needs a desktop session");
    }

    @Test
    void readRunsOffTheFxThreadReportsOnItAndIsNotBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> workOnFx = new CompletableFuture<>();
        CompletableFuture<Boolean> callbackOnFx = new CompletableFuture<>();

        FxTestSupport.onFxThread(() -> {
            Background.read(() -> {
                workOnFx.complete(Platform.isFxApplicationThread());
                release.await();
                return "value";
            }, value -> callbackOnFx.complete(Platform.isFxApplicationThread()), failure -> callbackOnFx.complete(false));
            return null;
        });

        assertFalse(workOnFx.get(10, TimeUnit.SECONDS));
        assertFalse(FxTestSupport.onFxThread(Background::isBusy));
        release.countDown();
        assertTrue(callbackOnFx.get(10, TimeUnit.SECONDS));
    }

    @Test
    void runIsBusyUntilItFinishesAndFailuresArriveOnTheFxThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> failure = new CompletableFuture<>();

        FxTestSupport.onFxThread(() -> {
            Background.run(() -> {
                release.await();
                throw new IllegalStateException("boom");
            }, value -> failure.complete("unexpected success"), error -> failure.complete(
                    Platform.isFxApplicationThread() ? error.getMessage() : "failure reported off the FX thread"));
            return null;
        });

        assertTrue(FxTestSupport.onFxThread(Background::isBusy));
        release.countDown();
        assertEquals("boom", failure.get(10, TimeUnit.SECONDS));
        FxTestSupport.waitUntil(() -> !Background.isBusy());
    }
}
```

`src/test/java/com/fabianrodas/services/ManifestLockProbe.java`:

```java
package com.fabianrodas.services;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;

/**
 * Holds FileService's manifest lock from a helper thread, so every manifest
 * load and save blocks until close(). A UI flow that touches the manifest
 * on the JavaFX thread then freezes that thread, which tests can observe.
 */
public final class ManifestLockProbe implements AutoCloseable {

    private final CountDownLatch release = new CountDownLatch(1);

    public ManifestLockProbe() throws Exception {
        Field field = FileService.class.getDeclaredField("MANIFEST_LOCK");
        field.setAccessible(true);
        Object lock = field.get(null);
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                held.countDown();

                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "manifest lock probe");
        holder.setDaemon(true);
        holder.start();
        held.await();
    }

    @Override
    public void close() {
        release.countDown();
    }
}
```

`src/test/java/com/fabianrodas/encryptdrive/FxThreadBoundaryTest.java` (regression for every affected UI flow):

```java
package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserLoginResult;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.services.AuthService;
import com.fabianrodas.services.FileService;
import com.fabianrodas.services.ManifestLockProbe;
import com.fabianrodas.services.SessionService;
import com.fabianrodas.services.VaultService;
import com.fabianrodas.services.VaultSessionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/*
 * Spec 14.1: no manifest decrypt, read or write on the JavaFX thread. Each
 * test blocks all manifest access, drives one UI flow, and requires the
 * JavaFX thread to stay responsive; the flow then completes once unblocked.
 */
class FxThreadBoundaryTest {

    @TempDir
    Path tempDir;

    private FileService files;

    @BeforeAll
    static void startJavaFx() {
        assumeTrue(FxTestSupport.start(), "JavaFX needs a desktop session");
    }

    @BeforeEach
    void openVaultAndSignIn() throws Exception {
        VaultContext vault = new VaultService().createVault(
                tempDir.resolve("vault"), "correct vault password".toCharArray());
        AuthService auth = new AuthService(vault);
        auth.register("Example User", "ExampleUser", "example password".toCharArray());
        UserLoginResult login = auth.login("ExampleUser", "example password".toCharArray());
        SessionService.start(login.identity(), login.userMasterKey());
        files = FileService.forCurrentSession();
        files.importFile(Files.writeString(tempDir.resolve("a.txt"), "a"), files.rootFolderId());
    }

    @AfterEach
    void closeVault() throws Exception {
        FxTestSupport.waitUntil(() -> !Background.isBusy());
        VaultSessionService.closeVault();
    }

    @ParameterizedTest
    @ValueSource(strings = {"#overviewNavButton", "#filesNavButton", "#trashNavButton"})
    void openingAViewNeverLoadsTheManifestOnTheFxThread(String navigation) throws Exception {
        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            Scene scene = FxTestSupport.showScreen("dashboard");   // Overview loads its statistics
            fire(scene, navigation);
            assertFxThreadResponsive();
        }
    }

    @Test
    void movingAFolderToTheTrashWritesTheManifestOffTheFxThread() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        Scene scene = showFiles();

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            FxTestSupport.onFxThread(() -> {
                TableView<ManifestEntry> table = table(scene);
                table.getSelectionModel().select(table.getItems().stream()
                        .filter(entry -> entry.getEntryId().equals(docs.getEntryId())).findFirst().orElseThrow());
                return null;
            });
            fire(scene, "#trashButton");
            assertFxThreadResponsive();
        }

        FxTestSupport.waitUntil(() -> files.listTrash().size() == 1);
    }

    @Test
    void creatingAFolderWritesTheManifestOffTheFxThread() throws Exception {
        Scene scene = showFiles();

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            FxTestSupport.fireAndAnswer(scene, "#newFolderButton", popup -> {
                ((TextField) popup.getScene().getRoot().lookup(".popup-input")).setText("Made in background");
                FxTestSupport.clickButton(popup, "Create");
            });
            assertFxThreadResponsive();
        }

        FxTestSupport.waitUntil(() -> files.listChildren(files.rootFolderId()).stream()
                .anyMatch(entry -> entry.getName().equals("Made in background")));
    }

    @Test
    void restoringFromTheTrashWritesTheManifestOffTheFxThread() throws Exception {
        files.moveToTrash(files.listChildren(files.rootFolderId()).get(0).getEntryId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        fire(scene, "#trashNavButton");
        FxTestSupport.waitUntil(() -> !table(scene).getItems().isEmpty());
        FxTestSupport.onFxThread(() -> {
            table(scene).getSelectionModel().select(0);
            return null;
        });

        try (ManifestLockProbe blocked = new ManifestLockProbe()) {
            fire(scene, "#restoreButton");
            assertFxThreadResponsive();
        }

        FxTestSupport.waitUntil(() -> files.listTrash().isEmpty());
    }

    private Scene showFiles() throws Exception {
        Scene scene = FxTestSupport.showScreen("dashboard");
        fire(scene, "#filesNavButton");
        FxTestSupport.waitUntil(() -> !table(scene).getItems().isEmpty());
        return scene;
    }

    @SuppressWarnings("unchecked")
    private static TableView<ManifestEntry> table(Scene scene) {
        return (TableView<ManifestEntry>) scene.getRoot().lookup("#table");
    }

    /** Fires without waiting: a handler that blocks the FX thread must not block the test thread too. */
    private static void fire(Scene scene, String selector) {
        Platform.runLater(() -> ((ButtonBase) scene.getRoot().lookup(selector)).fire());
    }

    /** The FX thread must answer within two seconds although every manifest access is blocked. */
    private static void assertFxThreadResponsive() throws Exception {
        CompletableFuture<Boolean> answered = new CompletableFuture<>();
        Platform.runLater(() -> answered.complete(true));

        try {
            assertTrue(answered.get(2, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            throw new AssertionError("the JavaFX thread is blocked on manifest work", e);
        }
    }
}
```
(`showScreen("dashboard")` inside the probe must itself not block: it runs on the FX thread through `onFxThread`, whose 30-second timeout fails the test if the Overview still reads statistics synchronously. The probe is closed by try-with-resources even when the assertion fails, so a blocked FX thread is always released. `table(scene)` looks up `#table` of whichever workspace view is showing.)

Add to `SessionServiceTest`:

```java
    @Test
    void keyCopyForAnotherIdentityIsRefused() {
        UserSessionIdentity alice = new UserSessionIdentity(UUID.randomUUID(), "Alice", "alice", UUID.randomUUID());
        UserSessionIdentity bob = new UserSessionIdentity(UUID.randomUUID(), "Bob", "bob", UUID.randomUUID());
        SessionService.start(bob, SensitiveBytes.copyOf(new byte[32]));

        assertThrows(IllegalStateException.class, () -> SessionService.copyUserMasterKey(alice));
        SessionService.copyUserMasterKey(bob).close();
    }
```
and update the existing `copiesAreIndependentOfTheSessionKey` to call `SessionService.copyUserMasterKey(identity)` with the identity it started.

Add to `PermanentDeleteTest.failedCleanupSaveLeavesARetryThatTreatsGoneBlobsAsDone`, right after the first assertion: `assertEquals(1, files.stats().pendingDeletions());` and at the end `assertEquals(0, files.stats().pendingDeletions());`.

Update `FileServiceTest`: `new WorkspaceStats(a, b, c, d)` → `new WorkspaceStats(a, b, c, d, 0)` (two places).

Add to `UiFlowTest`:

```java
    @Test
    void overviewRetriesDeletionsInterruptedEarlier() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry orphan = files.importFile(Files.writeString(tempDir.resolve("orphan.txt"), "x"), files.rootFolderId());
        UserSessionIdentity identity = SessionService.identity();
        byte[] key = userMasterKey.copy();
        ManifestRepository manifests = new ManifestRepository(vault);
        // As if a permanent delete stopped after its manifest commit: entry gone, blob queued.
        UserManifest manifest = manifests.load(identity.userId(), identity.manifestId(), key);
        manifest.getEntries().removeIf(entry -> entry.getEntryId().equals(orphan.getEntryId()));
        manifest.getPendingDeletions().add(new PendingDeletion(orphan.getBlobId(), Instant.now().toString()));
        manifests.save(manifest, identity.manifestId(), key);
        Path blob = new BlobRepository().blobPath(vault.root(), orphan.getBlobId());

        FxTestSupport.showScreen("dashboard");

        FxTestSupport.waitUntil(() -> !Files.exists(blob));
        assertTrue(manifests.load(identity.userId(), identity.manifestId(), key).getPendingDeletions().isEmpty());
    }
```
(imports: `UserSessionIdentity`, `UserManifest`, `PendingDeletion`, `ManifestRepository`, `BlobRepository`, `java.time.Instant`).

In `UiFlowTest.permanentDeleteHappensOnlyAfterExplicitConfirmation`, before each `selectFirstRow(scene)` add `waitForRows(scene);` with

```java
    private static void waitForRows(Scene scene) throws Exception {
        FxTestSupport.waitUntil(() -> !((TableView<?>) scene.getRoot().lookup("#table")).getItems().isEmpty());
    }
```

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=FolderViewTest,SessionServiceTest,BackgroundTest,FxThreadBoundaryTest,UiFlowTest#overviewRetriesDeletionsInterruptedEarlier"`
Expected: compile errors (`folderView`, `FolderView`, `copyUserMasterKey(UserSessionIdentity)`, `pendingDeletions()`, `Background.read`); the UI test then times out because nothing retries the journal. With only stubs in place, `FxThreadBoundaryTest`'s write flows (move to trash, create folder, restore) FAIL with "the JavaFX thread is blocked on manifest work", because `moveToTrash`, `createFolder` and `restore` still run inside the button handlers. The read flows (`openingAViewNeverLoadsTheManifestOnTheFxThread`) only become meaningful once every manifest load takes `MANIFEST_LOCK`: apply the `load` locking from Step 3 first and run them before changing the controllers, to see them fail for the right reason.

- [ ] **Step 3: Service changes**

`WorkspaceStats` becomes:
```java
public record WorkspaceStats(
        int activeFileCount,
        long activePlainBytes,
        long encryptedBytes,
        int trashCount,
        int pendingDeletions
) {
}
```
(Javadoc: "`pendingDeletions` counts blobs still queued for removal.") `FileService.stats()` returns `new WorkspaceStats(activeFiles, activeBytes, encryptedBytes, trash, manifest.getPendingDeletions().size())` (load the manifest into a local `manifest` first).

`SessionService` — replace the no-arg `copyUserMasterKey()`:
```java
    /** A copy of the UMK that the caller must close; refused if another account signed in meanwhile. */
    public static synchronized SensitiveBytes copyUserMasterKey(UserSessionIdentity expected) {
        requireActive();

        if (!identity.equals(expected)) {
            throw new IllegalStateException("A different account is signed in.");
        }

        return SensitiveBytes.wrap(userMasterKey.copy());
    }
```

`FileService`:
```java
    /** Files of the user signed in to the open vault; refuses to run for a later session. */
    public static FileService forCurrentSession() {
        UserSessionIdentity identity = SessionService.identity();

        return new FileService(
                VaultSessionService.current(),
                identity,
                () -> SessionService.copyUserMasterKey(identity)
        );
    }

    /** A folder's breadcrumb path (root first) and its active children, from one manifest read. */
    public record FolderView(UUID folderId, List<ManifestEntry> path, List<ManifestEntry> children) {
    }

    /**
     * The view of {@code folderId}, or of the root when it is null or no longer
     * an active folder (for example because it was moved to the trash).
     */
    public FolderView folderView(UUID folderId) throws FileServiceException {
        return withUserMasterKey(key -> {
            UserManifest manifest = load(key);
            ManifestService rules = new ManifestService(manifest);
            ManifestEntry folder = folderId == null ? null : rules.find(folderId);

            if (folder == null
                    || folder.getDeletedAt() != null
                    || folder.getKind() != ManifestEntryKind.FOLDER) {
                folder = rules.find(manifest.getRootFolderId());
            }

            return new FolderView(
                    folder.getEntryId(),
                    path(rules, folder),
                    rules.listChildren(folder.getEntryId(), false).stream().sorted(FOLDERS_THEN_NAME).toList()
            );
        });
    }

    /** Folders from the root down to {@code folder}. */
    private static List<ManifestEntry> path(ManifestService rules, ManifestEntry folder) {
        LinkedList<ManifestEntry> path = new LinkedList<>();

        for (ManifestEntry entry = folder;
                entry != null && path.size() <= MAX_FOLDER_DEPTH;
                entry = entry.getParentId() == null ? null : rules.find(entry.getParentId())) {
            path.addFirst(entry);
        }

        return path;
    }
```
Make `rootFolderId()` return `folderView(null).folderId()`. Delete `pathTo` (its only caller was `FilesController.refresh`). Wrap the body of `load(byte[] key)` in `synchronized (MANIFEST_LOCK) { ... }` with the comment `// Never read while this process is replacing the manifest.`

`Background`:
```java
    /**
     * Like {@link #run}, for read-only work: it is not counted as busy, so it
     * neither locks navigation nor blocks closing.
     */
    static <T> void read(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        launch(task(work, onSuccess, onFailure));
    }
```
and refactor: `run` = `start(task(work, onSuccess, onFailure))`; `start(task)` counts then calls `launch(task)`; `launch` creates the daemon thread; `task(...)` builds the `Task` with the success/failure handlers (move the existing anonymous `Task` there).

- [ ] **Step 4: Controllers**

`FilesController` — add `import java.util.concurrent.Callable;` and the field `private int viewRequest;` (with comment `/** Results of older folder loads are dropped when they arrive late. */`). Replace `initialize`'s tail, `newFolder`, the directory branch of `export`, `moveToTrash`, `runWithProgress`, `setBusy`, `refresh`; add `change`, `show(FolderView)`, `showProgress`, `describe(Throwable)`:

```java
        // end of initialize()
        if (!SessionService.isActive()) {
            updateActions();
            return;
        }

        files = FileService.forCurrentSession();
        refresh();
    }

    @FXML
    private void newFolder() {
        Optional<String> name = DialogFactory.prompt(
                window(), "New folder", "Enter a name for the new folder.", "", "Create"
        );

        if (name.isPresent()) {
            UUID parent = currentFolderId;
            change(() -> files.createFolder(name.get(), parent), folder -> showSuccess("Folder created."));
        }
    }

    @FXML
    private void moveToTrash() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        if (selected.isEmpty()) {
            return;
        }

        change(() -> {
            for (ManifestEntry entry : selected) {
                files.moveToTrash(entry.getEntryId());
            }
            return null;
        }, done -> showSuccess(selected.size() == 1
                ? "\"" + selected.get(0).getName() + "\" moved to the trash."
                : selected.size() + " items moved to the trash."));
    }

    static String describe(Throwable failure) {
        return failure instanceof FileServiceException error
                ? describe(error)
                : "The operation could not be completed.";
    }

    /** Runs a vault change in the background with the view locked, then reloads the folder. */
    private <T> void change(Callable<T> work, Consumer<T> onDone) {
        setBusy(true);
        Background.run(work, result -> {
            setBusy(false);
            refresh();
            onDone.accept(result);
        }, failure -> {
            setBusy(false);
            refresh();
            showError(describe(failure));
        });
    }

    private <T> void runWithProgress(Task<T> task, Consumer<T> onDone) {
        setBusy(true);
        showProgress(true);
        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(event -> {
            showProgress(false);
            setBusy(false);
            refresh();
            onDone.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            showProgress(false);
            setBusy(false);
            refresh();
            showError(describe(task.getException()));
        });

        Background.start(task);
    }

    private void showProgress(boolean visible) {
        if (!visible) {
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();
        }

        progressBox.setVisible(visible);
        progressBox.setManaged(visible);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        table.setDisable(busy);
        breadcrumbBar.setDisable(busy);
        updateActions();
    }

    private void refresh() {
        if (files == null) {
            return;
        }

        int request = ++viewRequest;
        UUID folderId = currentFolderId;

        Background.read(() -> files.folderView(folderId), view -> {
            if (request == viewRequest) {
                show(view);
            }
        }, failure -> {
            if (request == viewRequest) {
                showError(describe(failure));
            }
        });
    }

    private void show(FileService.FolderView view) {
        currentFolderId = view.folderId();
        table.getItems().setAll(view.children());
        renderBreadcrumbs(view.path());

        if (RecoveryService.takeRecoveryNotice()) {
            feedbackLabel.getStyleClass().remove("success");
            feedbackLabel.getStyleClass().add("notice");
            feedbackLabel.setText(Formats.RECOVERY_NOTICE);
        }

        updateActions();
    }
```
In `export()`, replace the synchronous `files.exportTargets(...)` block (directory branch) with a background read whose success handler performs the existing "replace existing items?" confirmation and calls `exportTo(selected, targets)`:

```java
            List<UUID> ids = selected.stream().map(ManifestEntry::getEntryId).toList();

            Background.read(() -> files.exportTargets(ids, directory.toPath()), targets -> {
                long existing = targets.stream().filter(Files::exists).count();

                if (existing == 0 || DialogFactory.confirm(
                        window(),
                        "Replace existing items?",
                        (existing == 1 ? "1 item with the same name already exists"
                                : existing + " items with the same names already exist")
                                + " in that folder. Replace them with the decrypted copies?",
                        "Replace"
                )) {
                    exportTo(selected, targets);
                }
            }, failure -> showError(describe(failure)));
            return;
```
(the single-file branch keeps calling `exportTo(selected, targets)` directly; remove the old `try { targets = files.exportTargets(...) }` block). Remove the now-unused `currentFolderId = files.rootFolderId();` try block from `initialize`.

`TrashController` — replace selection listener + `updateActions` with bindings and move work into the background:

```java
        // end of initialize()
        if (!SessionService.isActive()) {
            restoreButton.setDisable(true);
            deleteButton.setDisable(true);
            return;
        }

        files = FileService.forCurrentSession();
        BooleanBinding unavailable = Bindings.isEmpty(table.getSelectionModel().getSelectedItems())
                .or(Background.busyProperty());
        restoreButton.disableProperty().bind(unavailable);
        deleteButton.disableProperty().bind(unavailable);
        table.disableProperty().bind(Background.busyProperty());
        refresh();
    }

    @FXML
    private void restore() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        Background.run(() -> {
            for (ManifestEntry entry : selected) {
                files.restore(entry.getEntryId());
            }
            return null;
        }, done -> {
            refresh();
            showSuccess(selected.size() == 1
                    ? "\"" + selected.get(0).getName() + "\" was restored."
                    : selected.size() + " items were restored.");
        }, failure -> {
            refresh();
            showError(FilesController.describe(failure));
        });
    }
```
In `permanentlyDelete()`, after confirmation:
```java
        List<UUID> ids = selected.stream().map(ManifestEntry::getEntryId).toList();

        Background.run(() -> files.permanentlyDelete(ids), pending -> {
            refresh();
            showSuccess((selected.size() == 1
                    ? "\"" + selected.get(0).getName() + "\" was deleted permanently."
                    : selected.size() + " items were deleted permanently.")
                    + (pending > 0 ? " " + Formats.CLEANUP_PENDING : ""));
        }, failure -> {
            refresh();
            showError(FilesController.describe(failure));
        });
```
`refresh()`:
```java
    private void refresh() {
        Background.read(files::listTrash, items -> {
            table.getItems().setAll(items);

            if (RecoveryService.takeRecoveryNotice()) {
                feedbackLabel.getStyleClass().remove("success");
                feedbackLabel.getStyleClass().add("notice");
                feedbackLabel.setText(Formats.RECOVERY_NOTICE);
            }
        }, failure -> showError(FilesController.describe(failure)));
    }
```
Delete `updateActions()` and the selection listener; add `private Window window()` like `FilesController` if needed (imports `javafx.beans.binding.Bindings`, `javafx.beans.binding.BooleanBinding`, `java.util.UUID`).

`OverviewController` — add field `private FileService files;`; in `initialize` replace the synchronous `stats()` block with `files = FileService.forCurrentSession(); loadStats();` and add:

```java
    private void loadStats() {
        Background.read(files::stats, stats -> {
            fileCountLabel.setText(String.valueOf(stats.activeFileCount()));
            plainSizeLabel.setText(Formats.bytes(stats.activePlainBytes()));
            encryptedSizeLabel.setText(Formats.bytes(stats.encryptedBytes()));
            trashCountLabel.setText(String.valueOf(stats.trashCount()));
            show(emptyStateCard, stats.activeFileCount() == 0);

            if (RecoveryService.takeRecoveryNotice()) {
                showFeedback(Formats.RECOVERY_NOTICE);
            }

            if (stats.pendingDeletions() > 0) {
                resumePendingDeletions();
            }
        }, failure -> showFeedback("Your encrypted file list could not be read."));
    }

    /** Spec 9.4: deletions that stopped earlier are retried after login. */
    private void resumePendingDeletions() {
        Background.run(files::resumePendingDeletions, remaining -> {
            if (remaining > 0) {
                showFeedback(Formats.CLEANUP_PENDING);
            }

            loadStats();
        }, failure -> showFeedback(Formats.CLEANUP_PENDING));
    }

    private void showFeedback(String message) {
        feedbackLabel.setText(message);

        if (!feedbackLabel.getStyleClass().contains("notice")) {
            feedbackLabel.getStyleClass().add("notice");
        }

        show(feedbackLabel, true);
    }
```
(`resumePendingDeletions` is counted as busy because it writes; it only runs when the journal is non-empty, so normal Overview loads do not flicker the sidebar.)

- [ ] **Step 5: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=FolderViewTest,SessionServiceTest,BackgroundTest,FxThreadBoundaryTest,PermanentDeleteTest,FileServiceTest,UiFlowTest,UiLayoutTest,CloseGuardTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 6: Prove controllers no longer call FileService on the FX thread**

Run: `git grep -nE "files\.[a-zA-Z]+\(" src/main/java/com/fabianrodas/encryptdrive`
Expected: every hit is inside a lambda passed to `Background.run`/`Background.read`/`change`, or inside a `Task.call()`. Review each line.

- [ ] **Step 7: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/encryptdrive src/main/java/com/fabianrodas/services/FileService.java src/main/java/com/fabianrodas/services/SessionService.java src/main/java/com/fabianrodas/models/WorkspaceStats.java src/test/java/com/fabianrodas docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "perf: load and change vault metadata off the JavaFX thread" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** Files/Trash/Overview do all FileService work off the FX thread, proven per flow by `FxThreadBoundaryTest` (view loading, folder listing, folder creation, Move to Trash, Restore, statistics) and at the boundary by `BackgroundTest`; a folder view decrypts the manifest once; Overview retries a non-empty journal; a stale FileService cannot use another account's key; build green.

---

### Task 13: Rename files and folders

**Purpose:** Spec 13.1, 15, 21.5.

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/ManifestService.java` (`requireMovable`, `requireAvailableName(..., ignored)`, `rename`)
- Modify: `src/main/java/com/fabianrodas/services/FileService.java` (`rename`; `moveToTrash` uses `requireMovable`)
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/files.fxml` (Rename button)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/FilesController.java` (`rename`, `updateActions`)
- Create: `src/test/java/com/fabianrodas/services/RenameTest.java`
- Modify: `src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java`

**Interfaces:**
- Produces: `ManifestEntry ManifestService.requireMovable(UUID)`; `String ManifestService.requireAvailableName(UUID, String, ManifestEntry ignored)`; `void ManifestService.rename(UUID, String)`; `void FileService.rename(UUID, String)`; FXML id `renameButton`.

**Security:** metadata-only; AAD binds blobs to `entryId`, never to names, so the blob stays valid.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/fabianrodas/services/RenameTest.java`:

```java
package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.ManifestEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class RenameTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private FileService files;
    private UUID root;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        files = vault.files(vault.register("alice"));
        root = files.rootFolderId();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void renamingAFileLeavesItsBlobUntouched() throws Exception {
        byte[] content = new byte[100_000];
        new SecureRandom().nextBytes(content);
        ManifestEntry file = files.importFile(vault.source("report.pdf", content), root);
        Path blob = vault.blob(file);
        byte[] blobBefore = Files.readAllBytes(blob);
        FileTime modifiedBefore = Files.getLastModifiedTime(blob);

        files.rename(file.getEntryId(), "summary");

        assertEquals(List.of("summary"), names(files.listChildren(root)));
        assertArrayEquals(blobBefore, Files.readAllBytes(blob));
        assertEquals(modifiedBefore, Files.getLastModifiedTime(blob));
        Path out = tempDir.resolve("out.bin");
        files.exportEntry(file.getEntryId(), out);
        assertArrayEquals(content, Files.readAllBytes(out));
    }

    @Test
    void renamingAFolderKeepsItsChildren() throws Exception {
        ManifestEntry folder = files.createFolder("Docs", root);
        vault.importText(files, "a.txt", folder.getEntryId());

        files.rename(folder.getEntryId(), "Papers");

        assertEquals(List.of("Papers"), names(files.listChildren(root)));
        assertEquals(List.of("a.txt"), names(files.listChildren(folder.getEntryId())));
    }

    @Test
    void namesAreStrippedAndValidated() throws Exception {
        ManifestEntry file = vault.importText(files, "notes.txt", root);

        files.rename(file.getEntryId(), "  todo.txt  ");
        assertEquals(List.of("todo.txt"), names(files.listChildren(root)));

        for (String invalid : new String[]{"", "   ", ".", "..", "a\0b", "x".repeat(256)}) {
            assertReason(FileServiceException.Reason.INVALID_NAME, () -> files.rename(file.getEntryId(), invalid));
        }
    }

    @Test
    void siblingNamesStayUniqueIgnoringCase() throws Exception {
        ManifestEntry a = vault.importText(files, "a.txt", root);
        ManifestEntry b = vault.importText(files, "b.txt", root);

        assertReason(FileServiceException.Reason.DUPLICATE_NAME, () -> files.rename(b.getEntryId(), "A.TXT"));
        files.rename(a.getEntryId(), "A.txt");

        assertEquals(List.of("A.txt", "b.txt"), names(files.listChildren(root)));
    }

    @Test
    void theSameNameInAnotherFolderIsAllowed() throws Exception {
        ManifestEntry folder = files.createFolder("Docs", root);
        vault.importText(files, "a.txt", root);
        ManifestEntry inner = vault.importText(files, "b.txt", folder.getEntryId());

        files.rename(inner.getEntryId(), "a.txt");

        assertEquals(List.of("a.txt"), names(files.listChildren(folder.getEntryId())));
    }

    @Test
    void theRootAndTrashedEntriesCannotBeRenamed() throws Exception {
        ManifestEntry file = vault.importText(files, "a.txt", root);
        files.moveToTrash(file.getEntryId());

        assertReason(FileServiceException.Reason.PROTECTED, () -> files.rename(root, "Mine"));
        assertReason(FileServiceException.Reason.NOT_FOUND, () -> files.rename(file.getEntryId(), "b.txt"));
    }

    private static List<String> names(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getName).toList();
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }
}
```

Add to `UiFlowTest`:

```java
    @Test
    void renameThroughThePrompt() throws Exception {
        FileService files = FileService.forCurrentSession();
        files.importFile(Files.writeString(tempDir.resolve("old.txt"), "x"), files.rootFolderId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        selectFirstRow(scene);

        FxTestSupport.fireAndAnswer(scene, "#renameButton", popup -> {
            ((TextField) popup.getScene().getRoot().lookup(".popup-input")).setText("new.txt");
            FxTestSupport.clickButton(popup, "Rename");
        });

        FxTestSupport.waitUntil(() -> files.listChildren(files.rootFolderId()).get(0).getName().equals("new.txt"));
    }
```
(import `javafx.scene.control.TextField`).

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=RenameTest"`
Expected: compile error — `FileService.rename` missing.

- [ ] **Step 3: Implement the rules (`ManifestService`)**

```java
    /** The entry, if it is active and not the root (which is never renamed, moved or trashed). */
    public ManifestEntry requireMovable(UUID entryId) throws FileServiceException {
        if (entryId.equals(manifest.getRootFolderId())) {
            throw new FileServiceException(FileServiceException.Reason.PROTECTED);
        }

        ManifestEntry entry = find(entryId);

        if (entry == null || entry.getDeletedAt() != null) {
            throw new FileServiceException(FileServiceException.Reason.NOT_FOUND);
        }

        return entry;
    }

    /** Renames an active entry in place. Only the name changes; file content is never touched. */
    public void rename(UUID entryId, String newName) throws FileServiceException {
        ManifestEntry entry = requireMovable(entryId);
        entry.setName(requireAvailableName(entry.getParentId(), newName, entry));
    }
```
Change the existing `requireAvailableName(UUID, String)` into a delegate `return requireAvailableName(parentId, name, null);` and rename its body into the new overload with the sibling check `if (sibling != ignored && sibling.getName().equalsIgnoreCase(candidate))`. Javadoc of the overload: "Like the two-argument form, not counting {@code ignored} — the entry being renamed, so a case-only rename is allowed."

- [ ] **Step 4: `FileService`**

```java
    /** Renames an active file or folder; metadata only, the blob is untouched. */
    public void rename(UUID entryId, String newName) throws FileServiceException {
        modify(manifest -> {
            new ManifestService(manifest).rename(entryId, newName);
            return null;
        });
    }
```
In `moveToTrash`, replace the root/NOT_FOUND checks with `ManifestEntry entry = rules.requireMovable(entryId);`.

- [ ] **Step 5: UI**

`files.fxml`, after the Export button:
```xml
                <Button fx:id="renameButton"
                        disable="true"
                        minWidth="-Infinity"
                        mnemonicParsing="false"
                        onAction="#rename"
                        prefHeight="36.0"
                        styleClass="secondary-button"
                        text="Rename" />
```
`FilesController`: field `@FXML private Button renameButton;`, and

```java
    @FXML
    private void rename() {
        List<ManifestEntry> selected = table.getSelectionModel().getSelectedItems();

        if (selected.size() != 1) {
            return;
        }

        ManifestEntry entry = selected.get(0);
        Optional<String> name = DialogFactory.prompt(
                window(), "Rename", "Enter a new name for \"" + entry.getName() + "\".", entry.getName(), "Rename"
        );

        if (name.isPresent() && !name.get().equals(entry.getName())) {
            change(() -> {
                files.rename(entry.getEntryId(), name.get());
                return null;
            }, done -> showSuccess("Renamed to \"" + name.get().strip() + "\"."));
        }
    }
```
In `updateActions` add `renameButton.setDisable(unavailable || table.getSelectionModel().getSelectedItems().size() != 1);`.

- [ ] **Step 6: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=RenameTest,ManifestServiceTest,FileServiceTest,UiFlowTest,UiLayoutTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS (Files view still fits 1000×600).

- [ ] **Step 7: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/services/ManifestService.java src/main/java/com/fabianrodas/services/FileService.java src/main/resources/com/fabianrodas/encryptdrive/files.fxml src/main/java/com/fabianrodas/encryptdrive/FilesController.java src/test/java/com/fabianrodas/services/RenameTest.java src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "feat: rename files and folders" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** file and folder rename; empty/invalid/duplicate (case-insensitive) rejected; case-only self-rename allowed; root and trashed entries refused; blob bytes and mtime unchanged; build green.

---

### Task 14: Move files and folders (multi-item, destination picker)

**Purpose:** Spec 13.2, 15 ("move action with destination picker within the vault tree"), 21.5.

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/FileServiceException.java` (`INVALID_MOVE`)
- Modify: `src/main/java/com/fabianrodas/services/ManifestService.java` (`move`, `isSelfOrAncestor`, `nameTaken`)
- Modify: `src/main/java/com/fabianrodas/services/FileService.java` (`move`, `activeFolders`)
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/confirmation-popup.fxml` (`folderTree`)
- Modify: `src/main/resources/com/fabianrodas/css/confirmation-popup.css` (`.popup-tree`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/ConfirmationPopupController.java` (`setFolderChoice`, `getChosenFolder`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/DialogFactory.java` (`chooseFolder`)
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/files.fxml` (Move button)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/FilesController.java` (`move`, `folderTree`, `describe`)
- Create: `src/test/java/com/fabianrodas/services/MoveTest.java`, `src/test/java/com/fabianrodas/encryptdrive/FilesControllerTest.java`
- Modify: `src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java`

**Interfaces:**
- Produces: `FileServiceException.Reason.INVALID_MOVE`; `void ManifestService.move(List<UUID>, UUID)`; `void FileService.move(List<UUID>, UUID)`; `List<ManifestEntry> FileService.activeFolders()`; `Optional<ManifestEntry> DialogFactory.chooseFolder(Window, String, String, TreeItem<ManifestEntry>, String)`; `ConfirmationPopupController.setFolderChoice(TreeItem<ManifestEntry>)`, `getChosenFolder()`; `static TreeItem<ManifestEntry> FilesController.folderTree(List<ManifestEntry>, Set<UUID>)`; FXML ids `moveButton`, `folderTree`.

**Security:** metadata-only; everything is validated before any `setParentId`, so a refused move changes nothing.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/fabianrodas/services/MoveTest.java`:

```java
package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.ManifestEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class MoveTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account alice;
    private FileService files;
    private UUID root;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.register("alice");
        files = vault.files(alice);
        root = files.rootFolderId();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void movesAFileWithoutTouchingItsBlob() throws Exception {
        ManifestEntry target = files.createFolder("Target", root);
        ManifestEntry file = vault.importText(files, "a.txt", root);
        byte[] blob = Files.readAllBytes(vault.blob(file));

        files.move(List.of(file.getEntryId()), target.getEntryId());

        assertEquals(List.of("Target"), names(files.listChildren(root)));
        assertEquals(List.of("a.txt"), names(files.listChildren(target.getEntryId())));
        assertArrayEquals(blob, Files.readAllBytes(vault.blob(file)));
        Path out = tempDir.resolve("a-out.txt");
        files.exportEntry(file.getEntryId(), out);
        assertEquals("a.txt", Files.readString(out));
    }

    @Test
    void movesAFolderWithItsWholeSubtree() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", root);
        ManifestEntry year = files.createFolder("2025", docs.getEntryId());
        vault.importText(files, "a.txt", year.getEntryId());
        ManifestEntry archive = files.createFolder("Archive", root);

        files.move(List.of(docs.getEntryId()), archive.getEntryId());

        assertEquals(List.of("Docs"), names(files.listChildren(archive.getEntryId())));
        assertEquals(List.of("a.txt"), names(files.listChildren(year.getEntryId())));
        vault.manifest(alice);   // still a valid tree (load validates it)
    }

    @Test
    void movesSeveralEntriesAtOnce() throws Exception {
        ManifestEntry target = files.createFolder("Target", root);
        ManifestEntry a = vault.importText(files, "a.txt", root);
        ManifestEntry b = files.createFolder("B", root);

        files.move(List.of(a.getEntryId(), b.getEntryId()), target.getEntryId());

        assertEquals(List.of("B", "a.txt"), names(files.listChildren(target.getEntryId())));
    }

    @Test
    void aFolderCannotMoveIntoItselfOrBelowItself() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", root);
        ManifestEntry inner = files.createFolder("Inner", docs.getEntryId());

        assertReason(FileServiceException.Reason.INVALID_MOVE, () -> files.move(List.of(docs.getEntryId()), docs.getEntryId()));
        assertReason(FileServiceException.Reason.INVALID_MOVE, () -> files.move(List.of(docs.getEntryId()), inner.getEntryId()));

        assertEquals(List.of("Docs"), names(files.listChildren(root)));
    }

    @Test
    void theRootCannotBeMoved() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", root);

        assertReason(FileServiceException.Reason.PROTECTED, () -> files.move(List.of(root), docs.getEntryId()));
    }

    @Test
    void aNameConflictInTheDestinationMovesNothing() throws Exception {
        ManifestEntry target = files.createFolder("Target", root);
        vault.importText(files, "CLASH.TXT", target.getEntryId());
        ManifestEntry ok = vault.importText(files, "ok.txt", root);
        ManifestEntry clash = vault.importText(files, "clash.txt", root);

        assertReason(FileServiceException.Reason.DUPLICATE_NAME,
                () -> files.move(List.of(ok.getEntryId(), clash.getEntryId()), target.getEntryId()));

        assertEquals(List.of("Target", "clash.txt", "ok.txt"), names(files.listChildren(root)));
    }

    @Test
    void twoMovedEntriesWithTheSameNameMoveNothing() throws Exception {
        ManifestEntry a = files.createFolder("A", root);
        ManifestEntry b = files.createFolder("B", root);
        ManifestEntry target = files.createFolder("Target", root);
        ManifestEntry x1 = vault.importText(files, "x.txt", a.getEntryId());
        ManifestEntry x2 = vault.importText(files, "X.txt", b.getEntryId());

        assertReason(FileServiceException.Reason.DUPLICATE_NAME,
                () -> files.move(List.of(x1.getEntryId(), x2.getEntryId()), target.getEntryId()));

        assertEquals(List.of(), files.listChildren(target.getEntryId()));
    }

    @Test
    void theDestinationMustBeAnActiveFolder() throws Exception {
        ManifestEntry file = vault.importText(files, "a.txt", root);
        ManifestEntry other = vault.importText(files, "b.txt", root);
        ManifestEntry trashed = files.createFolder("Old", root);
        files.moveToTrash(trashed.getEntryId());

        assertReason(FileServiceException.Reason.NOT_A_FOLDER, () -> files.move(List.of(file.getEntryId()), other.getEntryId()));
        assertReason(FileServiceException.Reason.NOT_FOUND, () -> files.move(List.of(file.getEntryId()), trashed.getEntryId()));
        assertReason(FileServiceException.Reason.NOT_FOUND, () -> files.move(List.of(file.getEntryId()), UUID.randomUUID()));
    }

    @Test
    void trashedEntriesCannotBeMoved() throws Exception {
        ManifestEntry target = files.createFolder("Target", root);
        ManifestEntry file = vault.importText(files, "a.txt", root);
        files.moveToTrash(file.getEntryId());

        assertReason(FileServiceException.Reason.NOT_FOUND, () -> files.move(List.of(file.getEntryId()), target.getEntryId()));
    }

    @Test
    void movingIntoTheCurrentFolderChangesNothing() throws Exception {
        ManifestEntry file = vault.importText(files, "a.txt", root);

        files.move(List.of(file.getEntryId()), root);

        assertEquals(List.of("a.txt"), names(files.listChildren(root)));
    }

    private static List<String> names(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getName).toList();
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }
}
```

`src/test/java/com/fabianrodas/encryptdrive/FilesControllerTest.java`:

```java
package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;

class FilesControllerTest {

    @Test
    void moveDestinationsLeaveOutTheMovedFoldersAndEverythingInThem() {
        ManifestEntry root = folder(null, "/");
        ManifestEntry a = folder(root, "A");
        ManifestEntry b = folder(a, "B");
        ManifestEntry c = folder(root, "C");

        TreeItem<ManifestEntry> tree = FilesController.folderTree(List.of(root, a, b, c), Set.of(a.getEntryId()));

        assertSame(root, tree.getValue());
        assertEquals(List.of(c), tree.getChildren().stream().map(TreeItem::getValue).toList());
    }

    private static ManifestEntry folder(ManifestEntry parent, String name) {
        return new ManifestEntry(UUID.randomUUID(), ManifestEntryKind.FOLDER,
                parent == null ? null : parent.getEntryId(), name, "2026-10-01T00:00:00Z");
    }
}
```

Add to `UiFlowTest`:

```java
    @Test
    void moveThroughTheFolderPicker() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry target = files.createFolder("Target", files.rootFolderId());
        files.importFile(Files.writeString(tempDir.resolve("moved.txt"), "x"), files.rootFolderId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);
        FxTestSupport.onFxThread(() -> {
            ((TableView<?>) scene.getRoot().lookup("#table")).getSelectionModel().select(1);
            return null;
        });

        FxTestSupport.fireAndAnswer(scene, "#moveButton", popup -> {
            TreeView<?> tree = (TreeView<?>) popup.getScene().getRoot().lookup("#folderTree");
            tree.getSelectionModel().select(1);
            FxTestSupport.clickButton(popup, "Move here");
        });

        FxTestSupport.waitUntil(() -> files.listChildren(target.getEntryId()).size() == 1);
    }
```
(import `javafx.scene.control.TreeView`).

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=MoveTest,FilesControllerTest"`
Expected: compile errors (`FileService.move`, `INVALID_MOVE`, `FilesController.folderTree`).

- [ ] **Step 3: Rules and service**

`FileServiceException.Reason`: add `INVALID_MOVE` after `NOT_A_FOLDER`.

`ManifestService`:

```java
    /**
     * Moves active entries into an active folder. Everything is checked before
     * anything changes: the root, moves of a folder into itself or below it,
     * and names already used in the destination are refused.
     */
    public void move(List<UUID> entryIds, UUID destinationId) throws FileServiceException {
        ManifestEntry destination = find(destinationId);

        if (destination == null || destination.getDeletedAt() != null) {
            throw new FileServiceException(FileServiceException.Reason.NOT_FOUND);
        }

        if (destination.getKind() != ManifestEntryKind.FOLDER) {
            throw new FileServiceException(FileServiceException.Reason.NOT_A_FOLDER);
        }

        List<ManifestEntry> moving = new ArrayList<>();

        for (UUID entryId : new LinkedHashSet<>(entryIds)) {
            ManifestEntry entry = requireMovable(entryId);

            if (isSelfOrAncestor(entry, destination)) {
                throw new FileServiceException(FileServiceException.Reason.INVALID_MOVE);
            }

            if (destinationId.equals(entry.getParentId())) {
                continue;
            }

            if (nameTaken(destinationId, entry.getName())
                    || moving.stream().anyMatch(other -> other.getName().equalsIgnoreCase(entry.getName()))) {
                throw new FileServiceException(FileServiceException.Reason.DUPLICATE_NAME);
            }

            moving.add(entry);
        }

        for (ManifestEntry entry : moving) {
            entry.setParentId(destinationId);
        }
    }

    /** True if {@code candidate} is {@code folder} itself or one of its ancestors. */
    private boolean isSelfOrAncestor(ManifestEntry candidate, ManifestEntry folder) {
        int steps = 0;

        for (ManifestEntry current = folder;
                current != null && steps++ <= manifest.getEntries().size();
                current = current.getParentId() == null ? null : find(current.getParentId())) {
            if (current == candidate) {
                return true;
            }
        }

        return false;
    }

    private boolean nameTaken(UUID folderId, String name) {
        return listChildren(folderId, false).stream()
                .anyMatch(sibling -> sibling.getName().equalsIgnoreCase(name));
    }
```
(imports `java.util.LinkedHashSet`).

`FileService`:

```java
    /** Moves active entries into an active folder; metadata only, blobs are untouched. */
    public void move(List<UUID> entryIds, UUID destinationFolderId) throws FileServiceException {
        modify(manifest -> {
            new ManifestService(manifest).move(entryIds, destinationFolderId);
            return null;
        });
    }

    /** Every active folder, the root included, for choosing a move destination. */
    public List<ManifestEntry> activeFolders() throws FileServiceException {
        return withUserMasterKey(key -> load(key).getEntries().stream()
                .filter(entry -> entry.getKind() == ManifestEntryKind.FOLDER && entry.getDeletedAt() == null)
                .toList());
    }
```

- [ ] **Step 4: Folder picker popup**

`confirmation-popup.fxml` (import `javafx.scene.control.TreeView`), after `inputField`:
```xml
                <TreeView fx:id="folderTree"
                          managed="false"
                          maxWidth="360.0"
                          prefHeight="220.0"
                          styleClass="popup-tree"
                          visible="false" />
```
`confirmation-popup.css`: append
```css
.popup-tree {
    -fx-background-radius: 8;
    -fx-border-radius: 8;
}
```
(match the existing `.popup-input` border colour variable/literal used in that file).

`ConfirmationPopupController`:
```java
    @FXML
    private TreeView<ManifestEntry> folderTree;

    /** Shows a folder tree; Confirm stays disabled until a folder is selected. */
    void setFolderChoice(TreeItem<ManifestEntry> root) {
        iconLabel.setText("→");
        iconBadge.getStyleClass().add("info");
        folderTree.setRoot(root);
        folderTree.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(ManifestEntry folder, boolean empty) {
                super.updateItem(folder, empty);
                setText(empty || folder == null ? null
                        : folder.getParentId() == null ? "My files" : folder.getName());
            }
        });
        folderTree.setVisible(true);
        folderTree.setManaged(true);
        confirmButton.disableProperty().bind(folderTree.getSelectionModel().selectedItemProperty().isNull());
    }

    ManifestEntry getChosenFolder() {
        TreeItem<ManifestEntry> item = folderTree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }
```
`DialogFactory`:
```java
    /** Returns the folder the user picked, or empty when cancelled. */
    static Optional<ManifestEntry> chooseFolder(
            Window owner,
            String title,
            String message,
            TreeItem<ManifestEntry> root,
            String confirmText
    ) {
        ConfirmationPopupController popup = open(title, message, confirmText);

        if (popup == null) {
            return Optional.empty();
        }

        popup.setFolderChoice(root);
        stage(owner, popup).showAndWait();
        return popup.isConfirmed() ? Optional.ofNullable(popup.getChosenFolder()) : Optional.empty();
    }
```

- [ ] **Step 5: Files view**

`files.fxml`, after Rename:
```xml
                <Button fx:id="moveButton"
                        disable="true"
                        minWidth="-Infinity"
                        mnemonicParsing="false"
                        onAction="#move"
                        prefHeight="36.0"
                        styleClass="secondary-button"
                        text="Move" />
```
`FilesController`: field `@FXML private Button moveButton;`; `updateActions` adds `moveButton.setDisable(unavailable || nothingSelected);`; `describe` adds `case INVALID_MOVE -> "A folder cannot be moved into itself or one of its subfolders.";`; and:

```java
    @FXML
    private void move() {
        List<ManifestEntry> selected = List.copyOf(table.getSelectionModel().getSelectedItems());

        if (selected.isEmpty()) {
            return;
        }

        Set<UUID> moving = selected.stream().map(ManifestEntry::getEntryId).collect(Collectors.toSet());

        Background.read(files::activeFolders, folders -> {
            String what = selected.size() == 1 ? "\"" + selected.get(0).getName() + "\"" : selected.size() + " items";

            DialogFactory.chooseFolder(
                    window(), "Move", "Choose the folder to move " + what + " into.",
                    folderTree(folders, moving), "Move here"
            ).ifPresent(folder -> change(() -> {
                files.move(List.copyOf(moving), folder.getEntryId());
                return null;
            }, done -> showSuccess(what + (selected.size() == 1 ? " was" : " were") + " moved to "
                    + (folder.getParentId() == null ? "My files" : "\"" + folder.getName() + "\"") + ".")));
        }, failure -> showError(describe(failure)));
    }

    /** Active folders as a tree, leaving out the folders being moved and everything inside them. */
    static TreeItem<ManifestEntry> folderTree(List<ManifestEntry> folders, Set<UUID> moving) {
        Map<UUID, List<ManifestEntry>> children = folders.stream()
                .filter(folder -> folder.getParentId() != null)
                .collect(Collectors.groupingBy(ManifestEntry::getParentId));
        ManifestEntry root = folders.stream()
                .filter(folder -> folder.getParentId() == null)
                .findFirst()
                .orElseThrow();

        return treeItem(root, children, moving);
    }

    // ponytail: recursion depth equals folder depth; fine below thousands of levels.
    private static TreeItem<ManifestEntry> treeItem(
            ManifestEntry folder,
            Map<UUID, List<ManifestEntry>> children,
            Set<UUID> moving
    ) {
        TreeItem<ManifestEntry> item = new TreeItem<>(folder);
        item.setExpanded(true);

        children.getOrDefault(folder.getEntryId(), List.of()).stream()
                .filter(child -> !moving.contains(child.getEntryId()))
                .sorted(Comparator.comparing(ManifestEntry::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(child -> item.getChildren().add(treeItem(child, children, moving)));

        return item;
    }
```
(imports `java.util.Comparator`, `java.util.Map`, `java.util.Set`, `java.util.stream.Collectors`, `javafx.scene.control.TreeItem`).

- [ ] **Step 6: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=MoveTest,FilesControllerTest,ManifestServiceTest,UiFlowTest,UiLayoutTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 7: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/services src/main/resources/com/fabianrodas src/main/java/com/fabianrodas/encryptdrive src/test/java/com/fabianrodas/services/MoveTest.java src/test/java/com/fabianrodas/encryptdrive/FilesControllerTest.java src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "feat: move files and folders" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** valid moves (single, multiple, subtree) succeed without touching blobs; cycle, root, destination conflict, intra-selection conflict, invalid destination and trashed entries refused with nothing changed; picker excludes the moved folders; build green.

---

### Task 15: Recursive folder import without following links

**Purpose:** Spec 13.3, 15, 21.5 and dangerous failure mode #1 (import overlapping the vault).

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/FileServiceException.java` (`INSIDE_VAULT`)
- Modify: `src/main/java/com/fabianrodas/services/StreamingFileCryptoService.java` (`SourceReadException`)
- Create: `src/main/java/com/fabianrodas/services/SourceTree.java`
- Modify: `src/main/java/com/fabianrodas/services/FileService.java` (`ImportFailure`, `FolderImport`, `ImportProgress`, `importFolder`, `FolderImportRun`, `requireOutsideVault`, `canonical`, `writeBlob` classification)
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/files.fxml` (Import menu)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/FilesController.java` (`importMenu`, `importFolder`, `describe(Reason)`)
- Create: `src/test/java/com/fabianrodas/services/FolderImportTest.java`
- Modify: `src/test/java/com/fabianrodas/services/FileServiceTest.java` (locked source classification)

**Interfaces:**
- Produces: `FileServiceException.Reason.INSIDE_VAULT`; `StreamingFileCryptoService.SourceReadException`; `record FileService.ImportFailure(String path, FileServiceException.Reason reason)`; `record FileService.FolderImport(int foldersCreated, int filesImported, int linksSkipped, List<ImportFailure> failures, boolean stopped)`; `interface FileService.ImportProgress`; `FolderImport FileService.importFolder(Path, UUID, ImportProgress)`; private `void FileService.requireOutsideVault(Path, boolean refuseAncestors)` (used by T16); `static String FilesController.describe(FileServiceException.Reason)`; FXML ids `importMenu`, `importFilesItem`, `importFolderItem`.

**Security:** never follows links out of the tree; refuses sources that contain or lie inside the vault; never modifies the source; blobs of failed files are deleted by the existing `importFile` rollback and never referenced.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/fabianrodas/services/FolderImportTest.java`:

```java
package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class FolderImportTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private TestVault.Account alice;
    private FileService files;
    private UUID root;
    private Path source;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.register("alice");
        files = vault.files(alice);
        root = files.rootFolderId();
        source = Files.createDirectories(tempDir.resolve("input").resolve("Photos"));
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void importsTheHierarchyIncludingEmptyFolders() throws Exception {
        write("a.txt", "a");
        write("2024/b.txt", "b");
        write("2024/summer/c.txt", "c");
        Files.createDirectories(source.resolve("empty"));

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(4, result.foldersCreated());
        assertEquals(3, result.filesImported());
        assertEquals(List.of(), result.failures());
        assertEquals(Map.of(
                "Photos", "FOLDER", "Photos/a.txt", "FILE", "Photos/2024", "FOLDER",
                "Photos/2024/b.txt", "FILE", "Photos/2024/summer", "FOLDER",
                "Photos/2024/summer/c.txt", "FILE", "Photos/empty", "FOLDER"
        ), tree());
    }

    @Test
    void importedFilesExportByteForByte() throws Exception {
        write("2024/summer/c.txt", "summer content");

        files.importFolder(source, root, (a, b, c, d) -> { });

        ManifestEntry photos = child(root, "Photos");
        ManifestEntry summer = child(child(photos.getEntryId(), "2024").getEntryId(), "summer");
        Path out = tempDir.resolve("c-out.txt");
        files.exportEntry(child(summer.getEntryId(), "c.txt").getEntryId(), out);
        assertEquals("summer content", Files.readString(out));
    }

    @Test
    void theSourceTreeIsNeverModified() throws Exception {
        write("a.txt", "a");
        write("2024/b.txt", "b");
        Map<String, String> before = snapshot(source);

        files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(before, snapshot(source));
    }

    @Test
    void aTakenFolderNameAbortsBeforeAnythingIsImported() throws Exception {
        files.createFolder("photos", root);
        write("a.txt", "a");

        assertReason(FileServiceException.Reason.DUPLICATE_NAME,
                () -> files.importFolder(source, root, (a, b, c, d) -> { }));

        assertEquals(List.of("photos"), names(files.listChildren(root)));
        assertEquals(List.of(), vault.blobFiles());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void junctionsInsideTheSourceAreNotFollowed() throws Exception {
        write("a.txt", "a");
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "outside the tree");
        junction(source.resolve("link"), outside);

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(1, result.linksSkipped());
        assertEquals(Map.of("Photos", "FOLDER", "Photos/a.txt", "FILE"), tree());
        assertTrue(Files.exists(outside.resolve("secret.txt")));
    }

    @Test
    void symbolicLinksInsideTheSourceAreNotFollowed() throws Exception {
        write("a.txt", "a");
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside the tree");

        try {
            Files.createSymbolicLink(source.resolve("link.txt"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "creating symbolic links needs Developer Mode or administrator rights");
        }

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(1, result.linksSkipped());
        assertEquals(Map.of("Photos", "FOLDER", "Photos/a.txt", "FILE"), tree());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLockedFileIsReportedAndTheRestIsImported() throws Exception {
        write("a.txt", "a");
        Path locked = write("b.txt", "b");

        try (FileChannel channel = FileChannel.open(locked, StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

            assertEquals(1, result.filesImported());
            assertFalse(result.stopped());
            assertEquals(List.of(new FileService.ImportFailure(
                    Path.of("Photos", "b.txt").toString(), FileServiceException.Reason.SOURCE_UNREADABLE)),
                    result.failures());
        }

        assertEquals(1, vault.blobFiles().size());
    }

    @Test
    void aSourceThatContainsTheVaultIsRefused() throws Exception {
        assertReason(FileServiceException.Reason.INSIDE_VAULT,
                () -> files.importFolder(vault.vault.root().getParent(), root, (a, b, c, d) -> { }));
    }

    @Test
    void aSourceInsideTheVaultIsRefused() throws Exception {
        assertReason(FileServiceException.Reason.INSIDE_VAULT,
                () -> files.importFolder(vault.vault.root().resolve("storage"), root, (a, b, c, d) -> { }));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void namesThatClashInsideTheTreeSkipOnlyTheClashingItem() throws Exception {
        // NTFS keeps "key" and "\u212Aey" (Kelvin sign) apart; EncryptDrive compares names ignoring case and treats them as equal.
        write("key.txt", "plain k");
        write("\u212Aey.txt", "kelvin k");

        FileService.FolderImport result = files.importFolder(source, root, (a, b, c, d) -> { });

        assertEquals(1, result.filesImported());
        assertEquals(1, result.failures().size());
        assertEquals(FileServiceException.Reason.DUPLICATE_NAME, result.failures().get(0).reason());
        assertFalse(result.stopped());
    }

    @Test
    void progressReachesEveryFileAndByte() throws Exception {
        write("a.txt", "12345");
        write("sub/b.txt", "123");
        List<long[]> updates = new ArrayList<>();

        files.importFolder(source, root, (done, total, bytes, totalBytes) ->
                updates.add(new long[]{done, total, bytes, totalBytes}));

        long[] last = updates.get(updates.size() - 1);
        assertEquals(List.of(2L, 2L, 8L, 8L), List.of(last[0], last[1], last[2], last[3]));
    }

    @Test
    void aVaultWriteFailureStopsTheImportAndKeepsWhatWasCommitted() throws Exception {
        write("a.txt", "a");
        write("b.txt", "b");
        write("c.txt", "c");
        ManifestRepository failsFourthSave = new ManifestRepository(vault.vault) {
            private int saves;

            @Override
            public void save(UserManifest manifest, UUID manifestId, byte[] key) throws VaultStorageException {
                if (++saves == 4) {
                    throw new VaultStorageException(VaultStorageException.Reason.IO);
                }

                super.save(manifest, manifestId, key);
            }
        };

        FileService.FolderImport result = vault.files(alice, failsFourthSave)
                .importFolder(source, root, (a, b, c, d) -> { });

        assertTrue(result.stopped());
        assertEquals(2, result.filesImported());
        assertEquals(FileServiceException.Reason.STORAGE, result.failures().get(0).reason());
        assertEquals(Map.of("Photos", "FOLDER", "Photos/a.txt", "FILE", "Photos/b.txt", "FILE"), tree());
        assertEquals(2, vault.blobFiles().size());
    }

    // ------------------------------------------------------------ helpers

    private Path write(String relative, String content) throws IOException {
        Path file = source.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, UTF_8);
    }

    private static void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
    }

    /** Logical paths below the root mapped to their kind. */
    private Map<String, String> tree() throws Exception {
        Map<String, String> result = new TreeMap<>();
        collect(root, "", result);
        return result;
    }

    private void collect(UUID folderId, String prefix, Map<String, String> result) throws Exception {
        for (ManifestEntry entry : files.listChildren(folderId)) {
            String path = prefix + entry.getName();
            result.put(path, entry.getKind().name());

            if (entry.getKind().name().equals("FOLDER")) {
                collect(entry.getEntryId(), path + "/", result);
            }
        }
    }

    private ManifestEntry child(UUID folderId, String name) throws Exception {
        return files.listChildren(folderId).stream()
                .filter(entry -> entry.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> result = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.toList()) {
                result.put(dir.relativize(path).toString(),
                        Files.size(path) + "@" + Files.getLastModifiedTime(path));
            }
        }

        return result;
    }

    private static List<String> names(List<ManifestEntry> entries) {
        return entries.stream().map(ManifestEntry::getName).toList();
    }

    private static void assertReason(FileServiceException.Reason reason, Executable action) {
        assertEquals(reason, assertThrows(FileServiceException.class, action).getReason());
    }
}
```

Add to `FileServiceTest`:

```java
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLockedSourceIsUnreadableNotAStorageFailure() throws Exception {
        Path locked = source("locked.txt", "data".getBytes(UTF_8));
        FileService files = files(alice);

        try (FileChannel channel = FileChannel.open(locked, StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            assertReason(FileServiceException.Reason.SOURCE_UNREADABLE,
                    () -> files.importFile(locked, files.rootFolderId()));
        }

        assertEquals(List.of(), blobFiles());
    }
```
(imports `java.nio.channels.FileLock`, `org.junit.jupiter.api.condition.EnabledOnOs`, `org.junit.jupiter.api.condition.OS`).

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=FolderImportTest,FileServiceTest#aLockedSourceIsUnreadableNotAStorageFailure"`
Expected: compile errors (`importFolder`, `FolderImport`, `INSIDE_VAULT`); after they compile, `aLockedSourceIsUnreadableNotAStorageFailure` fails with `STORAGE`.

- [ ] **Step 3: Classify source-read failures**

In `StreamingFileCryptoService` add:

```java
    /** Reading the input failed, as opposed to writing the output. */
    public static final class SourceReadException extends IOException {

        SourceReadException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
```
and in `stream(...)` open and read the input through helpers that wrap failures:

```java
        InputStream source = open(input);

        try (source;
                FileChannel target = FileChannel.open(
                        outputPart,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                )) {

            int read;

            while ((read = read(source, in)) != -1) {
                consumed += read;
                write(target, out, cipher.processBytes(in, 0, read, out, 0));
                progress.accept(consumed);
            }
            // unchanged from here
```
```java
    private static InputStream open(Path input) throws SourceReadException {
        try {
            return Files.newInputStream(input);
        } catch (IOException e) {
            throw new SourceReadException(e);
        }
    }

    private static int read(InputStream source, byte[] buffer) throws SourceReadException {
        try {
            return source.read(buffer);
        } catch (IOException e) {
            throw new SourceReadException(e);
        }
    }
```
In `FileService.writeBlob` add before the generic `catch (IOException e)`:
```java
        } catch (StreamingFileCryptoService.SourceReadException e) {
            deleteQuietly(part);
            throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE, e);
```

- [ ] **Step 4: `SourceTree`**

`src/main/java/com/fabianrodas/services/SourceTree.java`:

```java
package com.fabianrodas.services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A directory tree as it was when a folder import started. An entry whose
 * real path is not where it was found (a symbolic link, junction or mount
 * point) is counted and never followed, so an import cannot leave the
 * selected tree. Reparse points that do not redirect, such as OneDrive
 * placeholders, are ordinary entries.
 */
final class SourceTree {

    /** A directory with its children, or a regular file with its size. */
    record Node(Path path, String name, boolean directory, long size, List<Node> children) {
    }

    private final Path root;
    private final List<Path> unreadable = new ArrayList<>();
    private Node rootNode;
    private int fileCount;
    private long totalBytes;
    private int linksSkipped;

    private SourceTree(Path root) {
        this.root = root;
    }

    /** Scans the directory the user selected (resolved to its real location). */
    static SourceTree scan(Path source) throws FileServiceException {
        Path real;

        try {
            real = source.toRealPath();
        } catch (IOException e) {
            throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE, e);
        }

        if (!Files.isDirectory(real)) {
            throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE);
        }

        SourceTree tree = new SourceTree(real);
        tree.rootNode = tree.directory(real, nameOf(real));

        if (tree.rootNode == null) {
            throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE);
        }

        return tree;
    }

    Node root() {
        return rootNode;
    }

    int fileCount() {
        return fileCount;
    }

    long totalBytes() {
        return totalBytes;
    }

    int linksSkipped() {
        return linksSkipped;
    }

    /** Entries that could not be read during the scan, as display paths. */
    List<String> unreadable() {
        return unreadable.stream().map(this::display).toList();
    }

    /** "Photos\2024\a.jpg" for an entry of the tree rooted at "Photos". */
    String display(Path path) {
        Path parent = root.getParent();
        return parent == null ? path.toString() : parent.relativize(path).toString();
    }

    // ponytail: recursion depth equals folder depth; fine below thousands of levels.
    private Node directory(Path directory, String name) {
        List<Path> entries;

        try (Stream<Path> listing = Files.list(directory)) {
            entries = listing.sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            unreadable.add(directory);
            return null;
        }

        List<Node> children = new ArrayList<>();

        for (Path entry : entries) {
            Node child = node(entry);

            if (child != null) {
                children.add(child);
            }
        }

        return new Node(directory, name, true, 0, children);
    }

    private Node node(Path entry) {
        BasicFileAttributes attributes;

        try {
            attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            if (attributes.isSymbolicLink() || !entry.toRealPath().equals(entry)) {
                linksSkipped++;
                return null;
            }
        } catch (IOException e) {
            unreadable.add(entry);
            return null;
        }

        String name = entry.getFileName().toString();

        if (attributes.isDirectory()) {
            return directory(entry, name);
        }

        fileCount++;
        totalBytes += attributes.size();
        return new Node(entry, name, false, attributes.size(), List.of());
    }

    private static String nameOf(Path real) {
        Path name = real.getFileName();
        // A drive root such as D:\ has no file name; use its letter.
        return name != null ? name.toString() : real.toString().replaceAll("[:\\\\/]", "");
    }
}
```

- [ ] **Step 5: `FileService.importFolder`**

`FileServiceException.Reason`: add `INSIDE_VAULT` after `SOURCE_UNREADABLE`.

Add to `FileService` (imports `java.nio.file.Path` already present):

```java
    /** A file or folder of a folder import that was not imported, and why. */
    public record ImportFailure(String path, FileServiceException.Reason reason) {
    }

    /** What a folder import did; {@code stopped} means the vault could not be written and the rest was skipped. */
    public record FolderImport(
            int foldersCreated,
            int filesImported,
            int linksSkipped,
            List<ImportFailure> failures,
            boolean stopped
    ) {
    }

    /** Folder-import progress: files finished and source bytes encrypted so far. */
    @FunctionalInterface
    public interface ImportProgress {
        void update(int filesDone, int filesTotal, long bytesDone, long bytesTotal);
    }

    /**
     * Imports the directory tree at {@code source} as a new folder with the
     * same name under {@code parentFolderId}, including empty folders. The
     * tree is scanned first without following links ({@link SourceTree}). A
     * name already used in the destination aborts before anything is
     * imported; a name that clashes inside the tree skips only that item.
     * Folders and files are committed one manifest change at a time, so an
     * interruption leaves a valid, partly imported tree and never a
     * referenced partial blob. The source is never modified.
     */
    public FolderImport importFolder(Path source, UUID parentFolderId, ImportProgress progress)
            throws FileServiceException {

        requireOutsideVault(source, true);
        SourceTree tree = SourceTree.scan(source);
        // Fails before anything is created; checked again when the folder is committed.
        withUserMasterKey(key -> new ManifestService(load(key))
                .requireAvailableName(parentFolderId, tree.root().name()));

        FolderImportRun run = new FolderImportRun(tree, progress);
        run.importDirectory(tree.root(), parentFolderId);
        return run.result();
    }

    /**
     * Imports must not read the vault itself, and exports must not write
     * plaintext into it (it may be a synced folder). With
     * {@code refuseAncestors}, a path that contains the vault is refused too.
     */
    private void requireOutsideVault(Path path, boolean refuseAncestors) throws FileServiceException {
        Path vaultRoot = canonical(vault.root());
        Path candidate = canonical(path);

        if (candidate.startsWith(vaultRoot) || (refuseAncestors && vaultRoot.startsWith(candidate))) {
            throw new FileServiceException(FileServiceException.Reason.INSIDE_VAULT);
        }
    }

    /** The real path of the nearest existing ancestor, with the rest appended. */
    private static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;

        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }

        if (existing == null) {
            return absolute;
        }

        try {
            return existing.toRealPath().resolve(existing.relativize(absolute));
        } catch (IOException e) {
            return absolute;
        }
    }

    /** One folder import; per-item failures are collected instead of thrown. */
    private final class FolderImportRun {

        private final SourceTree tree;
        private final ImportProgress progress;
        private final List<ImportFailure> failures = new ArrayList<>();
        private int folders;
        private int files;
        private int filesDone;
        private long bytesDone;
        private boolean stopped;

        FolderImportRun(SourceTree tree, ImportProgress progress) {
            this.tree = tree;
            this.progress = progress;

            for (String path : tree.unreadable()) {
                failures.add(new ImportFailure(path, FileServiceException.Reason.SOURCE_UNREADABLE));
            }
        }

        void importDirectory(SourceTree.Node directory, UUID parentId) {
            ManifestEntry folder;

            try {
                folder = createFolder(directory.name(), parentId);
                folders++;
            } catch (FileServiceException e) {
                fail(directory, e);
                skip(directory);
                return;
            }

            for (SourceTree.Node child : directory.children()) {
                if (stopped) {
                    return;
                }

                if (child.directory()) {
                    importDirectory(child, folder.getEntryId());
                } else {
                    importOne(child, folder.getEntryId());
                }
            }
        }

        private void importOne(SourceTree.Node file, UUID folderId) {
            long before = bytesDone;

            try {
                importFile(file.path(), folderId, bytes ->
                        progress.update(filesDone, tree.fileCount(), before + bytes, tree.totalBytes()));
                files++;
            } catch (FileServiceException e) {
                fail(file, e);
            }

            filesDone++;
            bytesDone = before + file.size();
            progress.update(filesDone, tree.fileCount(), bytesDone, tree.totalBytes());
        }

        /** Counts the files of a skipped subtree as done, so progress still reaches the total. */
        private void skip(SourceTree.Node directory) {
            for (SourceTree.Node child : directory.children()) {
                if (child.directory()) {
                    skip(child);
                } else {
                    filesDone++;
                    bytesDone += child.size();
                }
            }

            progress.update(filesDone, tree.fileCount(), bytesDone, tree.totalBytes());
        }

        private void fail(SourceTree.Node node, FileServiceException e) {
            failures.add(new ImportFailure(tree.display(node.path()), e.getReason()));

            // A problem with one source item skips that item; anything else
            // means the vault cannot be written, so the import stops.
            stopped |= switch (e.getReason()) {
                case SOURCE_UNREADABLE, DUPLICATE_NAME, INVALID_NAME -> false;
                default -> true;
            };
        }

        FolderImport result() {
            return new FolderImport(folders, files, tree.linksSkipped(), List.copyOf(failures), stopped);
        }
    }
```

- [ ] **Step 6: UI**

`files.fxml` — replace the Import Files `Button` with (imports `javafx.scene.control.MenuButton`, `javafx.scene.control.MenuItem`):
```xml
                <MenuButton fx:id="importMenu"
                            minWidth="-Infinity"
                            mnemonicParsing="false"
                            prefHeight="36.0"
                            styleClass="primary-button"
                            text="Import">
                    <items>
                        <MenuItem fx:id="importFilesItem" mnemonicParsing="false" onAction="#importFiles" text="Files…" />
                        <MenuItem fx:id="importFolderItem" mnemonicParsing="false" onAction="#importFolder" text="Folder…" />
                    </items>
                </MenuButton>
```
`FilesController`: replace `@FXML private Button importButton;` with `@FXML private MenuButton importMenu;` and in `updateActions` `importMenu.setDisable(unavailable);`. Split `describe`:

```java
    static String describe(FileServiceException e) {
        return describe(e.getReason());
    }

    static String describe(FileServiceException.Reason reason) {
        return switch (reason) {
            // existing cases unchanged, plus:
            case INSIDE_VAULT -> "Choose a location outside the vault folder.";
        };
    }
```
Add:

```java
    @FXML
    private void importFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Import a folder into EncryptDrive");
        File chosen = chooser.showDialog(window());

        if (chosen != null) {
            importFolder(chosen.toPath());
        }
    }

    /** Encrypts a copy of the folder tree into the current folder; the source is left untouched. */
    private void importFolder(Path source) {
        UUID target = currentFolderId;

        Task<FileService.FolderImport> task = new Task<>() {
            @Override
            protected FileService.FolderImport call() throws FileServiceException {
                updateMessage("Scanning " + source.getFileName() + "...");

                return files.importFolder(source, target, (done, total, bytes, totalBytes) -> {
                    updateMessage("Encrypting file " + Math.min(done + 1, total) + " of " + total + "...");

                    if (totalBytes > 0) {
                        updateProgress(bytes, totalBytes);
                    } else {
                        updateProgress(done, Math.max(1, total));
                    }
                });
            }
        };

        runWithProgress(task, result -> showFolderImport(source, result));
    }

    private void showFolderImport(Path source, FileService.FolderImport result) {
        String summary = count(result.filesImported(), "file") + " and " + count(result.foldersCreated(), "folder")
                + " imported from \"" + source.getFileName() + "\".";

        if (result.linksSkipped() > 0) {
            summary += " " + count(result.linksSkipped(), "link") + " (shortcuts or junctions) skipped.";
        }

        if (result.failures().isEmpty()) {
            showSuccess(summary);
            return;
        }

        String failed = result.failures().stream()
                .limit(5)
                .map(failure -> failure.path() + " (" + describe(failure.reason()) + ")")
                .collect(Collectors.joining("; "));

        showError(summary
                + (result.stopped() ? " The import stopped early." : "")
                + " Not imported: " + failed
                + (result.failures().size() > 5 ? " and " + (result.failures().size() - 5) + " more." : "."));
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
```

- [ ] **Step 7: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=FolderImportTest,FileServiceTest,StreamingFileCryptoServiceTest,UiLayoutTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS. On Windows the junction, lock and Kelvin tests run; the symlink test is skipped unless Developer Mode is on (record which ran in the ledger).

- [ ] **Step 8: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/services src/main/resources/com/fabianrodas/encryptdrive/files.fxml src/main/java/com/fabianrodas/encryptdrive/FilesController.java src/test/java/com/fabianrodas/services/FolderImportTest.java src/test/java/com/fabianrodas/services/FileServiceTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "feat: import folders recursively without following links" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** hierarchy and empty folders preserved; links/junctions not followed; locked files reported while others import; taken root name aborts before anything; in-tree clashes skip only that item; vault overlap refused; vault write failure stops with committed entries intact and no orphan referenced; source unchanged; build green.

---

### Task 16: Refuse plaintext export into the vault folder

**Purpose:** Dangerous failure mode #1 (export side): plaintext must never be written inside the vault (which may be OneDrive-synced). Spec "no plaintext metadata policy" / 20 (exported plaintext warning).

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/FileService.java` (`exportEntry`, `exportTargets`)
- Test: `src/test/java/com/fabianrodas/services/FileServiceTest.java`

**Interfaces:** consumes `requireOutsideVault(Path, boolean)` (T15), `FileServiceException.Reason.INSIDE_VAULT` (T15).

- [ ] **Step 1: Write the failing test** (add to `FileServiceTest`)

```java
    @Test
    void exportIntoTheVaultIsRefused() throws Exception {
        FileService files = files(alice);
        ManifestEntry entry = files.importFile(source("secret.txt", "plain".getBytes(UTF_8)), files.rootFolderId());

        for (Path target : List.of(
                vault.root().resolve("secret.txt"),
                vault.root().resolve("storage").resolve("blobs").resolve("secret.txt"))) {
            assertReason(FileServiceException.Reason.INSIDE_VAULT, () -> files.exportEntry(entry.getEntryId(), target));
            assertFalse(Files.exists(target));
        }

        assertReason(FileServiceException.Reason.INSIDE_VAULT,
                () -> files.exportTargets(List.of(entry.getEntryId()), vault.root()));

        Path beside = vault.root().resolveSibling("exported.txt");
        files.exportEntry(entry.getEntryId(), beside);
        assertEquals("plain", Files.readString(beside));
    }
```

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=FileServiceTest#exportIntoTheVaultIsRefused"`
Expected: FAIL — the first export succeeds and writes `secret.txt` into the vault.

- [ ] **Step 3: Implement** — first statement of `exportEntry`: `requireOutsideVault(destination, false);`; first statement of `exportTargets`: `requireOutsideVault(directory, false);`. Extend the `exportEntry` Javadoc: "Destinations inside the vault folder are refused."

- [ ] **Step 4: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=FileServiceTest,CorruptionIntegrationTest"` then `mvn -B clean verify`.

- [ ] **Step 5: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/services/FileService.java src/test/java/com/fabianrodas/services/FileServiceTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "fix: refuse to export plaintext into the vault folder" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** exports into the vault root or below are refused before any file is created; exports beside the vault still work; build green.

---

### Task 17: Search active files across folders

**Purpose:** Spec 13.4, 15, 21.5.

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/FileService.java` (`SearchResult`, `search`)
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/files.fxml` (header row with `searchField`, `clearSearchButton`; `locationColumn`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/FilesController.java` (search mode)
- Create: `src/test/java/com/fabianrodas/services/SearchTest.java`
- Modify: `src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java`

**Interfaces:**
- Produces: `record FileService.SearchResult(ManifestEntry entry, List<String> folders)` (folder names below the root, outermost first); `List<SearchResult> FileService.search(String)`; `static String FilesController.location(List<String>)`; FXML ids `searchField`, `clearSearchButton`, `locationColumn`.

**Security:** only the signed-in account's decrypted in-memory manifest is searched; nothing is written or indexed.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/fabianrodas/services/SearchTest.java`:

```java
package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fabianrodas.models.ManifestEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private FileService alice;
    private UUID root;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        alice = vault.files(vault.register("alice"));
        root = alice.rootFolderId();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void searchIgnoresCase() throws Exception {
        vault.importText(alice, "Budget-2025.xlsx", root);

        assertEquals(List.of("Budget-2025.xlsx"), names(alice.search("budget")));
        assertEquals(List.of("Budget-2025.xlsx"), names(alice.search("GET-20")));
    }

    @Test
    void searchFindsEntriesInEveryFolderWithTheirLocation() throws Exception {
        ManifestEntry docs = alice.createFolder("Docs", root);
        ManifestEntry year = alice.createFolder("2025 report folder", docs.getEntryId());
        vault.importText(alice, "report.pdf", year.getEntryId());
        vault.importText(alice, "report.txt", root);

        List<FileService.SearchResult> results = alice.search("report");

        assertEquals(Map.of(
                "2025 report folder", List.of("Docs"),
                "report.pdf", List.of("Docs", "2025 report folder"),
                "report.txt", List.of()
        ), byName(results));
    }

    @Test
    void searchLeavesOutTheTrashAndTrashedFolders() throws Exception {
        ManifestEntry old = alice.createFolder("Old", root);
        vault.importText(alice, "note-inside.txt", old.getEntryId());
        ManifestEntry loose = vault.importText(alice, "note-loose.txt", root);
        alice.moveToTrash(old.getEntryId());
        alice.moveToTrash(loose.getEntryId());
        vault.importText(alice, "note-kept.txt", root);

        assertEquals(List.of("note-kept.txt"), names(alice.search("note")));
    }

    @Test
    void searchNeverSeesAnotherAccount() throws Exception {
        vault.importText(alice, "alice-secret.txt", root);
        FileService bob = vault.files(vault.register("bob"));

        assertEquals(List.of(), bob.search("secret"));
        assertEquals(List.of(), bob.search("alice"));
    }

    @Test
    void blankQueriesAndTheRootFindNothing() throws Exception {
        vault.importText(alice, "a.txt", root);

        assertEquals(List.of(), alice.search(""));
        assertEquals(List.of(), alice.search("   "));
        assertEquals(List.of(), alice.search("/"));
    }

    @Test
    void searchWritesNothing() throws Exception {
        vault.importText(alice, "a.txt", root);
        Map<String, String> before = snapshot(vault.vault.root());

        alice.search("a");

        assertEquals(before, snapshot(vault.vault.root()));
    }

    private static List<String> names(List<FileService.SearchResult> results) {
        return results.stream().map(result -> result.entry().getName()).toList();
    }

    private static Map<String, List<String>> byName(List<FileService.SearchResult> results) {
        Map<String, List<String>> map = new TreeMap<>();
        results.forEach(result -> map.put(result.entry().getName(), result.folders()));
        return map;
    }

    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> result = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(dir.relativize(path).toString(), Files.size(path) + "@" + Files.getLastModifiedTime(path));
            }
        }

        return result;
    }
}
```

Add to `UiFlowTest`:

```java
    @Test
    void searchShowsMatchesFromEveryFolderAndClearReturns() throws Exception {
        FileService files = FileService.forCurrentSession();
        ManifestEntry docs = files.createFolder("Docs", files.rootFolderId());
        files.importFile(Files.writeString(tempDir.resolve("invoice.pdf"), "x"), docs.getEntryId());
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#filesNavButton");
        waitForRows(scene);

        FxTestSupport.onFxThread(() -> {
            TextField search = (TextField) scene.getRoot().lookup("#searchField");
            search.setText("INVOICE");
            search.fireEvent(new ActionEvent());
            return null;
        });

        FxTestSupport.waitUntil(() -> rows(scene).size() == 1 && rows(scene).get(0).getName().equals("invoice.pdf"));
        click(scene, "#clearSearchButton");
        FxTestSupport.waitUntil(() -> rows(scene).size() == 1 && rows(scene).get(0).getName().equals("Docs"));
    }

    @SuppressWarnings("unchecked")
    private static List<ManifestEntry> rows(Scene scene) {
        return ((TableView<ManifestEntry>) scene.getRoot().lookup("#table")).getItems();
    }
```
(import `javafx.event.ActionEvent`).

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=SearchTest"`
Expected: compile error — `FileService.search` missing.

- [ ] **Step 3: Implement `search`**

```java
    /** A search hit and the folders leading to it (below the root, outermost first). */
    public record SearchResult(ManifestEntry entry, List<String> folders) {
    }

    /**
     * Active entries whose name contains {@code query}, ignoring case,
     * anywhere in the signed-in user's tree. Only the decrypted manifest in
     * memory is used; nothing is indexed or written.
     */
    public List<SearchResult> search(String query) throws FileServiceException {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);

        if (needle.isEmpty()) {
            return List.of();
        }

        return withUserMasterKey(key -> {
            UserManifest manifest = load(key);
            ManifestService rules = new ManifestService(manifest);

            return manifest.getEntries().stream()
                    .filter(entry -> entry.getDeletedAt() == null)
                    .filter(entry -> !entry.getEntryId().equals(manifest.getRootFolderId()))
                    .filter(entry -> entry.getName().toLowerCase(Locale.ROOT).contains(needle))
                    .sorted(FOLDERS_THEN_NAME)
                    .map(entry -> new SearchResult(entry, path(rules, rules.find(entry.getParentId()))
                            .stream().skip(1).map(ManifestEntry::getName).toList()))
                    .toList();
        });
    }
```

- [ ] **Step 4: UI**

`files.fxml` — wrap the title `VBox` in a header `HBox` with the search controls (imports `javafx.scene.control.TextField`):
```xml
        <HBox alignment="CENTER_LEFT" spacing="10.0">
            <children>
                <VBox spacing="5.0" HBox.hgrow="ALWAYS">
                    <children>
                        <Label styleClass="page-title" text="Files" />
                        <Label styleClass="page-subtitle"
                               text="Every file is encrypted with its own key before it is stored." />
                    </children>
                </VBox>

                <TextField fx:id="searchField"
                           onAction="#search"
                           prefWidth="240.0"
                           promptText="Search your files"
                           styleClass="profile-input-field" />

                <Button fx:id="clearSearchButton"
                        managed="false"
                        mnemonicParsing="false"
                        onAction="#clearSearch"
                        prefHeight="36.0"
                        styleClass="secondary-button"
                        text="Clear"
                        visible="false" />
            </children>
        </HBox>
```
and add to the table columns: `<TableColumn fx:id="locationColumn" prefWidth="200.0" sortable="false" text="LOCATION" visible="false" />`.

`FilesController`: fields `@FXML private TextField searchField; @FXML private Button clearSearchButton; @FXML private TableColumn<ManifestEntry, String> locationColumn;`, `private final Map<UUID, String> locations = new HashMap<>(); private boolean searching; private UUID selectAfterLoad;`. In `initialize` add `column(locationColumn, entry -> locations.getOrDefault(entry.getEntryId(), ""));`.

```java
    @FXML
    private void search() {
        String query = searchField.getText().strip();

        if (query.isEmpty()) {
            clearSearch();
            return;
        }

        int request = ++viewRequest;

        Background.read(() -> files.search(query), results -> {
            if (request == viewRequest) {
                showResults(query, results);
            }
        }, failure -> {
            if (request == viewRequest) {
                showError(describe(failure));
            }
        });
    }

    private void showResults(String query, List<FileService.SearchResult> results) {
        searching = true;
        locations.clear();
        results.forEach(result -> locations.put(result.entry().getEntryId(), location(result.folders())));
        table.getItems().setAll(results.stream().map(FileService.SearchResult::entry).toList());
        locationColumn.setVisible(true);
        clearSearchButton.setVisible(true);
        clearSearchButton.setManaged(true);

        Label title = new Label("Search results for \"" + query + "\" (" + results.size() + ")");
        title.getStyleClass().add("breadcrumb-current");
        breadcrumbBar.getChildren().setAll(title);
        updateActions();
    }

    @FXML
    private void clearSearch() {
        searching = false;
        searchField.clear();
        locationColumn.setVisible(false);
        clearSearchButton.setVisible(false);
        clearSearchButton.setManaged(false);
        refresh();
    }

    static String location(List<String> folders) {
        return folders.isEmpty() ? "My files" : "My files › " + String.join(" › ", folders);
    }
```
Change `refresh()` to start with `if (searching) { search(); return; }` (after a change in search mode the results are re-run). Change `open(entry)`: a folder → `exitSearch(); navigateTo(entry.getEntryId());`; a file in search mode → `exitSearch(); selectAfterLoad = entry.getEntryId(); navigateTo(entry.getParentId());` (outside search mode keep the existing Export hint), where `exitSearch()` resets `searching`, clears the field, hides the column and button. In `show(view)` after `setAll`, select and scroll to `selectAfterLoad` if present, then null it. In `updateActions`, also disable `newFolderButton` and `importMenu` while `searching` (no current folder).

- [ ] **Step 5: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=SearchTest,UiFlowTest,UiLayoutTest"` then `mvn -B clean verify`.
Expected: PASS / BUILD SUCCESS (Files header still fits at 1000×600).

- [ ] **Step 6: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/services/FileService.java src/main/resources/com/fabianrodas/encryptdrive/files.fxml src/main/java/com/fabianrodas/encryptdrive/FilesController.java src/test/java/com/fabianrodas/services/SearchTest.java src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "feat: search active files across folders" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** case-insensitive recursive search with location context; trash and other accounts excluded; nothing written; double-click navigates to the folder/parent; Clear returns to the folder view; build green.

---

### Task 18: Empty Trash through the permanent-delete engine

**Purpose:** Spec 13.5, 21.5, 9.4 (Empty Trash retries the journal).

**Files:**
- Modify: `src/main/java/com/fabianrodas/services/FileService.java` (`emptyTrash`)
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/trash.fxml` (`emptyTrashButton`)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/TrashController.java` (`emptyTrash`)
- Test: `src/test/java/com/fabianrodas/services/PermanentDeleteTest.java`, `src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java`

**Interfaces:** consumes private `deletePermanently` (T10); produces `int FileService.emptyTrash()`, FXML id `emptyTrashButton`.

- [ ] **Step 1: Write the failing tests**

Add to `PermanentDeleteTest`:

```java
    @Test
    void emptyTrashDeletesEveryTrashedEntryThroughTheSameEngine() throws Exception {
        ManifestEntry kept = vault.importText(files, "kept.txt", files.rootFolderId());
        ManifestEntry first = trashed("first.txt");
        ManifestEntry folder = files.createFolder("Old", files.rootFolderId());
        ManifestEntry inner = vault.importText(files, "inner.txt", folder.getEntryId());
        files.moveToTrash(folder.getEntryId());
        List<Boolean> blobsPresentAtFirstCheckpoint = new ArrayList<>();
        ManifestRepository watching = new ManifestRepository(vault.vault) {
            @Override
            public void saveCheckpoint(UserManifest manifest, UUID manifestId, byte[] key)
                    throws VaultStorageException {
                if (blobsPresentAtFirstCheckpoint.isEmpty()) {
                    blobsPresentAtFirstCheckpoint.add(Files.exists(vault.blob(first)) && Files.exists(vault.blob(inner)));
                }
                super.saveCheckpoint(manifest, manifestId, key);
            }
        };

        assertEquals(0, vault.files(alice, watching).emptyTrash());

        assertEquals(List.of(true), blobsPresentAtFirstCheckpoint);
        assertEquals(List.of(), files.listTrash());
        assertEquals(List.of(kept.getEntryId()), ids(files.listChildren(files.rootFolderId())));
        assertEquals(List.of(vault.blob(kept)), vault.blobFiles());
        assertBackupsForget(first, true);
        assertBackupsForget(inner, true);
    }

    @Test
    void emptyTrashAlsoRetriesEarlierQueuedBlobs() throws Exception {
        ManifestEntry stuck = trashed("stuck.txt");
        Path obstacle = blockReplacing(vault.blob(stuck));
        assertEquals(1, files.permanentlyDelete(List.of(stuck.getEntryId())));
        deleteRecursively(obstacle);

        assertEquals(0, files.emptyTrash());
        assertTrue(vault.manifest(alice).getPendingDeletions().isEmpty());
    }
```

Add to `UiFlowTest`:

```java
    @Test
    void emptyTrashStatesTheCountAndNeedsConfirmation() throws Exception {
        FileService files = FileService.forCurrentSession();
        for (String name : List.of("a.txt", "b.txt")) {
            files.moveToTrash(files.importFile(Files.writeString(tempDir.resolve(name), name), files.rootFolderId()).getEntryId());
        }
        Scene scene = FxTestSupport.showScreen("dashboard");
        click(scene, "#trashNavButton");
        waitForRows(scene);
        List<String> messages = new ArrayList<>();

        FxTestSupport.fireAndAnswer(scene, "#emptyTrashButton", popup -> {
            messages.add(((Labeled) popup.getScene().getRoot().lookup(".popup-message")).getText());
            FxTestSupport.clickButton(popup, "Cancel");
        });
        FxTestSupport.onFxThread(() -> null);
        assertTrue(messages.get(0).contains("all 2 items"), messages.get(0));
        assertEquals(2, files.listTrash().size());

        FxTestSupport.fireAndAnswer(scene, "#emptyTrashButton", popup -> FxTestSupport.clickButton(popup, "Empty Trash"));
        FxTestSupport.waitUntil(() -> files.listTrash().isEmpty());
    }
```

- [ ] **Step 2: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=PermanentDeleteTest"`
Expected: compile error — `emptyTrash` missing.

- [ ] **Step 3: Implement**

`FileService`:
```java
    /** Permanently deletes everything in the trash through {@link #permanentlyDelete}'s sequence. */
    public int emptyTrash() throws FileServiceException {
        synchronized (MANIFEST_LOCK) {
            return withUserMasterKey(key -> {
                UserManifest manifest = load(key);
                List<UUID> trash = manifest.getEntries().stream()
                        .filter(FileService::isTrashRoot)
                        .map(ManifestEntry::getEntryId)
                        .toList();

                return deletePermanently(manifest, trash, key);
            });
        }
    }
```

`trash.fxml`, before Restore:
```xml
                <Button fx:id="emptyTrashButton"
                        disable="true"
                        minWidth="-Infinity"
                        mnemonicParsing="false"
                        onAction="#emptyTrash"
                        prefHeight="36.0"
                        styleClass="danger-button"
                        text="Empty Trash" />
```
`TrashController`: field `@FXML private Button emptyTrashButton;`; in `initialize` (session branch) `emptyTrashButton.disableProperty().bind(Bindings.isEmpty(table.getItems()).or(Background.busyProperty()));` and in the no-session branch `emptyTrashButton.setDisable(true);`;

```java
    @FXML
    private void emptyTrash() {
        int count = table.getItems().size();

        if (count == 0 || !DialogFactory.confirmDestructive(
                root.getScene() == null ? null : root.getScene().getWindow(),
                "Empty the trash?",
                "Permanently delete all " + count + (count == 1 ? " item" : " items")
                        + " in the trash? Recovery through EncryptDrive will no longer be possible.",
                "Empty Trash"
        )) {
            return;
        }

        Background.run(files::emptyTrash, pending -> {
            refresh();
            showSuccess("The trash was emptied." + (pending > 0 ? " " + Formats.CLEANUP_PENDING : ""));
        }, failure -> {
            refresh();
            showError(FilesController.describe(failure));
        });
    }
```

- [ ] **Step 4: Run focused, related, full**

Run: `mvn -B -q test "-Dtest=PermanentDeleteTest,UiFlowTest,UiLayoutTest"` then `mvn -B clean verify`.

- [ ] **Step 5: Ledger + commit**

```bash
git add src/main/java/com/fabianrodas/services/FileService.java src/main/resources/com/fabianrodas/encryptdrive/trash.fxml src/main/java/com/fabianrodas/encryptdrive/TrashController.java src/test/java/com/fabianrodas/services/PermanentDeleteTest.java src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "feat: empty the trash through the permanent-delete engine" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** confirmation states the exact count; Cancel keeps everything; Empty Trash uses `deletePermanently` (checkpoint before blob removal, journal retried); active files untouched; build green.

---

### Task 19: Streaming-memory and metadata-read regression guards

**Purpose:** Spec 14.1, 14.3, 21.7. Routine CI catches whole-file buffering anywhere in import/export; deterministic counts catch repeated metadata reads; the opt-in 1 GiB release test also covers the full vault path; folder-import throughput is measured once.

**Files:**
- Create: `src/test/java/com/fabianrodas/services/StreamingMemoryProbe.java`
- Create: `src/test/java/com/fabianrodas/services/StreamingMemoryTest.java`
- Create: `src/test/java/com/fabianrodas/services/MetadataLoadCountTest.java`
- Create: `src/test/java/com/fabianrodas/services/FolderImportBenchmarkTest.java`
- Modify: `src/test/java/com/fabianrodas/services/LargeFileStreamingTest.java`

**Interfaces:** consumes `TestVault`, `CountingManifests`; no production change unless a guard fails (then fix the root cause in its own commit).

- [ ] **Step 1: Child-JVM streaming probe**

`StreamingMemoryProbe.java`:

```java
package com.fabianrodas.services;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.security.SensitiveBytes;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/**
 * Runs a file of the given size through FileService import and export in a
 * JVM started with a small heap (see StreamingMemoryTest). Avoids Argon2 so
 * the heap only has to hold streaming buffers. Prints MATCH on success.
 */
public final class StreamingMemoryProbe {

    private StreamingMemoryProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        long size = Long.parseLong(args[1]);
        Path root = dir.resolve("vault");
        Files.createDirectories(root.resolve(".encryptdrive").resolve("manifests"));
        Files.createDirectories(root.resolve("storage").resolve("blobs"));

        byte[] userMasterKey = new byte[32];
        new SecureRandom().nextBytes(userMasterKey);
        byte[] registryKey = new byte[32];
        new SecureRandom().nextBytes(registryKey);
        VaultContext vault = new VaultContext(root, UUID.randomUUID().toString(), 1,
                Instant.now().toString(), SensitiveBytes.wrap(registryKey), () -> { });
        UUID userId = UUID.randomUUID();
        UUID manifestId = UUID.randomUUID();
        new ManifestRepository(vault).save(ManifestService.newManifest(userId), manifestId, userMasterKey);
        FileService files = new FileService(vault, new UserSessionIdentity(userId, "Probe", "probe", manifestId),
                () -> SensitiveBytes.copyOf(userMasterKey));

        Path source = dir.resolve("large.bin");
        Random random = new Random(7);
        byte[] chunk = new byte[1 << 20];

        try (OutputStream out = Files.newOutputStream(source)) {
            for (long written = 0; written < size; written += chunk.length) {
                random.nextBytes(chunk);
                out.write(chunk);
            }
        }

        ManifestEntry entry = files.importFile(source, files.rootFolderId());
        Path exported = dir.resolve("large.out");
        files.exportEntry(entry.getEntryId(), exported);

        System.out.print(Arrays.equals(sha256(source), sha256(exported)) ? "MATCH" : "MISMATCH");
    }

    static byte[] sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }

        return digest.digest();
    }
}
```

`StreamingMemoryTest.java`:

```java
package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Routine guard against whole-file buffering: 128 MiB through import and
 * export in a JVM whose heap is 32 MiB. No wall-clock threshold.
 */
class StreamingMemoryTest {

    @Test
    void importAndExportStreamWithinASmallHeap(@TempDir Path dir) throws Exception {
        String classpath = Stream.of(
                        Path.of("target", "classes").toAbsolutePath().toString(),
                        Path.of("target", "test-classes").toAbsolutePath().toString(),
                        location(Gson.class),
                        location(GCMBlockCipher.class))
                .collect(Collectors.joining(File.pathSeparator));

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m",
                "-cp", classpath,
                StreamingMemoryProbe.class.getName(),
                dir.toString(),
                String.valueOf(128L << 20)
        ).redirectErrorStream(true).start();

        String output = new String(process.getInputStream().readAllBytes(), UTF_8);

        assertEquals(0, process.waitFor(), output);
        assertTrue(output.endsWith("MATCH"), output);
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
```

- [ ] **Step 2: Metadata read counts**

`MetadataLoadCountTest.java`:

```java
package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fabianrodas.models.ManifestEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/* Spec 14.1: metadata is not re-read more than required within one operation. */
class MetadataLoadCountTest {

    @TempDir
    Path tempDir;

    private TestVault vault;
    private CountingManifests counting;
    private FileService files;
    private UUID root;

    @BeforeEach
    void open() throws Exception {
        vault = new TestVault(tempDir);
        counting = new CountingManifests(vault.vault);
        files = vault.files(vault.register("alice"), counting);
        root = files.rootFolderId();
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    void everyOperationDecryptsTheManifestOnce() throws Exception {
        ManifestEntry docs = files.createFolder("Docs", root);
        ManifestEntry file = vault.importText(files, "a.txt", root);
        ManifestEntry trashed = vault.importText(files, "t.txt", root);
        files.moveToTrash(trashed.getEntryId());
        ManifestEntry toDelete = vault.importText(files, "d.txt", root);
        files.moveToTrash(toDelete.getEntryId());

        Map<String, Executable> operations = new LinkedHashMap<>();
        operations.put("folderView", () -> files.folderView(docs.getEntryId()));
        operations.put("search", () -> files.search("a"));
        operations.put("stats", () -> files.stats());
        operations.put("listTrash", () -> files.listTrash());
        operations.put("activeFolders", () -> files.activeFolders());
        operations.put("createFolder", () -> files.createFolder("New", root));
        operations.put("rename", () -> files.rename(file.getEntryId(), "b.txt"));
        operations.put("move", () -> files.move(List.of(file.getEntryId()), docs.getEntryId()));
        operations.put("restore", () -> files.restore(trashed.getEntryId()));
        operations.put("permanentlyDelete", () -> files.permanentlyDelete(List.of(toDelete.getEntryId())));
        operations.put("resumePendingDeletions", () -> files.resumePendingDeletions());
        operations.put("emptyTrash", () -> files.emptyTrash());
        operations.put("exportEntry", () -> files.exportEntry(file.getEntryId(), tempDir.resolve("out.txt")));

        for (Map.Entry<String, Executable> operation : operations.entrySet()) {
            counting.loads.set(0);
            try {
                operation.getValue().execute();
            } catch (Throwable e) {
                throw new AssertionError(operation.getKey(), e);
            }
            assertEquals(1, counting.loads.get(), operation.getKey());
        }
    }

    @Test
    void folderImportReadsAFixedNumberOfTimesPerItem() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("in").resolve("Tree"));
        Files.createDirectories(source.resolve("sub"));
        Files.writeString(source.resolve("a.txt"), "a");
        Files.writeString(source.resolve("b.txt"), "b");
        Files.writeString(source.resolve("sub").resolve("c.txt"), "c");
        counting.loads.set(0);

        files.importFolder(source, root, (a, b, c, d) -> { });

        // 1 preflight + 1 per folder (2) + 2 per file (3): the per-file transaction model of spec 14.2.
        assertEquals(1 + 2 + 2 * 3, counting.loads.get());
    }
}
```

- [ ] **Step 3: Extend the 1 GiB release test**

In `LargeFileStreamingTest` extract the random-file loop into `private static Path randomFile(Path file, long size)` and add:

```java
    @Test
    void oneGibibyteRoundTripsThroughTheVaultWithinASmallHeap(@TempDir Path dir) throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() <= 300L * 1024 * 1024, "run with -DargLine=-Xmx256m");
        Path source = randomFile(dir.resolve("large.bin"), ONE_GIB);

        try (TestVault vault = new TestVault(dir)) {
            FileService files = vault.files(vault.register("alice"));
            ManifestEntry entry = files.importFile(source, files.rootFolderId());
            Path out = dir.resolve("large.out");
            files.exportEntry(entry.getEntryId(), out);

            assertArrayEquals(sha256(source), sha256(out));
        }
    }
```
(import `com.fabianrodas.models.ManifestEntry`).

- [ ] **Step 4: Opt-in folder-import benchmark**

`FolderImportBenchmarkTest.java`:

```java
package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/*
 * Opt-in measurement (spec 14.2/14.3), never a CI threshold:
 *   mvn -Dtest=FolderImportBenchmarkTest -Dencryptdrive.perfCheck=true test
 */
@EnabledIfSystemProperty(named = "encryptdrive.perfCheck", matches = "true")
class FolderImportBenchmarkTest {

    @Test
    void thousandSmallFiles(@TempDir Path dir) throws Exception {
        Path source = Files.createDirectories(dir.resolve("in").resolve("Bench"));

        for (int i = 0; i < 1000; i++) {
            Files.write(source.resolve("file-" + i + ".bin"), new byte[4096]);
        }

        try (TestVault vault = new TestVault(dir)) {
            FileService files = vault.files(vault.register("alice"));
            long start = System.nanoTime();

            FileService.FolderImport result = files.importFolder(source, files.rootFolderId(), (a, b, c, d) -> { });

            System.out.println("Folder import benchmark: 1000 x 4 KiB in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
            assertEquals(1000, result.filesImported());
        }
    }
}
```

- [ ] **Step 5: Run the guards**

Run: `mvn -B -q test "-Dtest=StreamingMemoryTest,MetadataLoadCountTest"`
Expected: PASS. If a count is higher than 1, find the duplicate `load` in that operation and fix it in a separate commit `perf: read the manifest once in <operation>` with this test as the regression.

Run (PowerShell): `mvn -B test "-Dtest=LargeFileStreamingTest" "-Dencryptdrive.largeFileCheck=true" "-DargLine=-Xmx256m"`
Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`. Record PASS + duration in the ledger.

Run: `mvn -B test "-Dtest=FolderImportBenchmarkTest" "-Dencryptdrive.perfCheck=true"`
Record the printed duration in the ledger. At Phase 03, the 1,000-file result was below the original 120,000 ms threshold, so per-file saves were retained at that time. Phase 03A T19A supersedes that decision based on measured scaling and requires batches of at most 64 logical mutations.

- [ ] **Step 6: Full suite**

Run: `mvn -B clean verify` → BUILD SUCCESS (the large and benchmark tests stay skipped by default).

- [ ] **Step 7: Phase 03 boundary**

1. Fresh worktree `mvn -B clean verify` (as in plan 02).
2. superpowers:requesting-code-review over `<T12 commit>^..HEAD`; fix findings as separate commits with tests.
3. Ledger: phase 03 DONE.

- [ ] **Step 8: Ledger + commit**

```bash
git add src/test/java/com/fabianrodas/services/StreamingMemoryProbe.java src/test/java/com/fabianrodas/services/StreamingMemoryTest.java src/test/java/com/fabianrodas/services/MetadataLoadCountTest.java src/test/java/com/fabianrodas/services/FolderImportBenchmarkTest.java src/test/java/com/fabianrodas/services/LargeFileStreamingTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "test: guard streaming memory use and metadata reads" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** 128 MiB round-trips in a 32 MiB child JVM in routine CI; every operation decrypts the manifest once (folder import has a fixed per-item count); the 1 GiB test (crypto + vault path) PASSES with `-Xmx256m`; benchmark recorded with a decision; build green.
