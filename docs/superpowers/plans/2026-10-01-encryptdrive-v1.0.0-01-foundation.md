# EncryptDrive v1.0.0 — Plan 01: Foundation and Versioning

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the repository a clean base for release work: adopt the approved spec and plan chain, normalize line endings, remove local junk, and make the Maven version (`1.0.0-SNAPSHOT`) the single application version visible in the UI.

**Architecture:** Configuration and one generated resource. `version.properties` is filtered by Maven and read by `App.version()`; Vault Settings displays it. The existing packaging script is minimally updated so it keeps working until Plan 04 replaces it.

**Tech Stack:** Git, Maven resource filtering, JavaFX FXML, JUnit 6.

**Spec:** `docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md` (sections 6.1, 6.2, 15, 16, 17).

## Global Constraints

See the master plan, section "Global Constraints". Every commit ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

---

### Task 1: Adopt the v1.0.0 spec and plan chain; archive the superseded roadmap

**Purpose:** Exactly one active plan chain (spec 16.3). The completed roadmap spec/plan move to an archive; Git history keeps the rest.

**Files:**
- Add (currently untracked): `docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md`
- Add: `docs/superpowers/plans/2026-10-01-encryptdrive-v1.0.0-master-plan.md` and the five `2026-10-01-encryptdrive-v1.0.0-0N-*.md` child plans
- Add: `docs/superpowers/plans/V1_RELEASE_STATE.md`
- Move: `docs/superpowers/plans/EncryptDrive_Complete_Implementation_Plan.md` → `docs/superpowers/archive/v1.0-roadmap/EncryptDrive_Complete_Implementation_Plan.md`
- Move: `docs/superpowers/specs/EncryptDrive_Complete_Design_Spec.md` → `docs/superpowers/archive/v1.0-roadmap/EncryptDrive_Complete_Design_Spec.md`

**Interfaces:** none.

**Security:** documentation only.

- [ ] **Step 1: Check nothing else references the archived files**

Run: `git grep -n "EncryptDrive_Complete_" -- ':!docs/superpowers/archive' ':!docs/superpowers/plans/2026-10-01-*'`
Expected: no output (README/docs do not link the old roadmap). If a hit appears, update that link to the archive path in this task.

- [ ] **Step 2: Move the superseded documents**

```bash
mkdir -p docs/superpowers/archive/v1.0-roadmap
git mv docs/superpowers/plans/EncryptDrive_Complete_Implementation_Plan.md docs/superpowers/archive/v1.0-roadmap/
git mv docs/superpowers/specs/EncryptDrive_Complete_Design_Spec.md docs/superpowers/archive/v1.0-roadmap/
```

- [ ] **Step 3: Update the ledger** — set `current phase: 01-foundation`, `current task: T1`, `tests last run: mvn -B clean verify`.

- [ ] **Step 4: Verify the build is unaffected**

Run: `mvn -B clean verify`
Expected: BUILD SUCCESS, `Tests run: 166, Failures: 0, Errors: 0, Skipped: 1`.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md docs/superpowers/plans/ docs/superpowers/archive/
git commit -m "docs: adopt the v1.0.0 release spec and plan" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** `docs/superpowers/plans/` contains only the v1.0.0 master plan, its five child plans and the ledger; the archive holds the two roadmap documents; build green.

---

### Task 2: Normalize line endings, tidy ignore rules, remove local junk

**Purpose:** Spec 2.1 #10–11, 16.1–16.2, 17 (first step). Stop CRLF/LF false modifications; make `.gitignore` consistent; delete the local plaintext dev DB and extension output.

**Files:**
- Create: `.gitattributes`
- Modify: `.gitignore`
- Delete (untracked, no commit content): `data/users.json`, `data/`, `.github/java-upgrade/`

**Interfaces:** none.

**Security:** `data/users.json` contains a plaintext dev user list with password hashes and salts; deleting the local copy is required before packaging. Confirm first that it is untracked on this branch.

- [ ] **Step 1: Prove `data/users.json` is untracked here and absent from build output**

```bash
git ls-files data/ ; echo "tracked-count=$(git ls-files data/ | wc -l)"
git check-ignore -v data/users.json
ls target 2>/dev/null | grep -i users || echo "not in target"
```
Expected: `tracked-count=0`; `.gitignore:...:data/	data/users.json`; `not in target`.

- [ ] **Step 2: Delete the local junk**

```bash
rm -rf data .github/java-upgrade
git status --ignored --porcelain
```
Expected: only `!! nb-configuration.xml` and `!! target/` remain ignored (NetBeans local settings are kept on purpose — spec 16.1 forbids making NetBeans worse).

- [ ] **Step 3: Create `.gitattributes`**

```gitattributes
# Text is stored and checked out with LF on every platform, so Windows
# checkouts (core.autocrlf=true) no longer show false modifications.
* text=auto eol=lf

# Binary assets are never converted.
*.png binary
*.ico binary
*.jar binary
*.zip binary
*.exe binary
```

- [ ] **Step 4: Tidy `.gitignore`**

Make exactly these edits:
1. In the `## NetBeans` section delete the line `nbactions.xml` (the file is tracked on purpose: it drives NetBeans Run/Debug through `javafx:run`).
2. Delete the duplicated trailing section:
   ```
   ##############################
   ## Packaging output
   ##############################
   target/
   dist/
   ```
   (`target/` is already in the Maven section and `dist/` in the NetBeans section.)
3. In the `## Eclipse` section delete the duplicate lines `bin/` and `*.tmp` (both already listed elsewhere); in the `## NetBeans` section delete the duplicate `build/`.
4. Append to the `## EncryptDrive local data` section:
   ```
   .github/java-upgrade/
   ```

- [ ] **Step 5: Renormalize and check the index**

```bash
git add --renormalize .
git status --short
```
Expected: no tracked file is restaged by the renormalize (the index already stores LF); the only entries are `?? .gitattributes` (new, staged in Step 9) and `M  .gitignore`. If any other file shows as modified, stop and inspect it with `git diff --cached --stat` before continuing — a content change here would be a line-ending rewrite that must not be mixed into this commit unnoticed.

- [ ] **Step 6: Refresh the working-tree copies that are still CRLF**

```bash
for f in $(git ls-files --eol | awk '$2=="w/crlf"{print $NF}'); do rm "$f" && git checkout -- "$f"; done
git ls-files --eol | awk '$2=="w/crlf"' | wc -l
git status --short
```
Expected: `0`; status still shows only `.gitattributes` and `.gitignore`.

- [ ] **Step 7: Verify**

Run: `mvn -B clean verify`
Expected: BUILD SUCCESS, 166 tests, 0 failures.

- [ ] **Step 8: Update the ledger** (T2 done; history cleanup status: `local copy deleted; still in history and in origin/main tip — decision at H2`).

- [ ] **Step 9: Commit**

```bash
git add .gitattributes .gitignore docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "chore: normalize line endings and tidy ignore rules" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** no `w/crlf` files; `git status` clean after commit; `data/` and `.github/java-upgrade/` gone; build green.

---

### Task 3: `1.0.0-SNAPSHOT` as the single version, shown in Vault Settings

**Purpose:** Spec 6.1, 6.2, 15 ("application version displayed in Vault Settings"), 21.6 ("application can read/display canonical build version").

**Files:**
- Modify: `pom.xml` (version, resource filtering)
- Create: `src/main/resources/com/fabianrodas/encryptdrive/version.properties`
- Modify: `src/main/java/com/fabianrodas/encryptdrive/App.java` (add `version()`)
- Modify: `src/main/resources/com/fabianrodas/encryptdrive/vault-settings.fxml` (APP VERSION row)
- Modify: `src/main/java/com/fabianrodas/encryptdrive/VaultSettingsController.java`
- Modify: `scripts/package-windows.ps1` (derive jar name and app version from the pom; replaced in T20)
- Test: `src/test/java/com/fabianrodas/encryptdrive/ReleaseMetadataTest.java` (new)
- Test: `src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java` (one test added)

**Interfaces:**
- Produces: `static String App.version()`; `static String ReleaseMetadataTest.pomVersion()` (package-private, reused by T20); FXML id `appVersionLabel`.

**Security:** the version string is non-secret; it is a build-time constant.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/fabianrodas/encryptdrive/ReleaseMetadataTest.java`:

```java
package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/*
 * The Maven project version is the only application version: the app reads
 * it from a filtered resource, and nothing else may hard-code it.
 */
class ReleaseMetadataTest {

    private static final Pattern PROJECT_VERSION = Pattern.compile(
            "<artifactId>EncryptDrive</artifactId>\\s*<version>([^<]+)</version>"
    );

    @Test
    void appReportsTheMavenProjectVersion() throws IOException {
        String version = pomVersion();

        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"), version);
        assertEquals(version, App.version());
    }

    /** The project version from pom.xml (tests run with the project root as working directory). */
    static String pomVersion() throws IOException {
        Matcher matcher = PROJECT_VERSION.matcher(
                Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8)
        );

        if (!matcher.find()) {
            throw new IllegalStateException("pom.xml has no project version");
        }

        return matcher.group(1).trim();
    }
}
```

Add to `UiFlowTest` (uses the existing signed-in setup):

```java
    @Test
    void vaultSettingsShowTheApplicationVersion() throws Exception {
        Scene scene = FxTestSupport.showScreen("dashboard");

        click(scene, "#settingsNavButton");

        assertEquals(App.version(), FxTestSupport.onFxThread(
                () -> ((Labeled) scene.getRoot().lookup("#appVersionLabel")).getText()
        ));
    }
```
(import `javafx.scene.control.Labeled`).

- [ ] **Step 2: Run them to confirm they fail**

Run: `mvn -B -q test "-Dtest=ReleaseMetadataTest,UiFlowTest#vaultSettingsShowTheApplicationVersion"`
Expected: compilation failure `cannot find symbol: method version()` in `App`.

- [ ] **Step 3: Set the version and filter only `version.properties`**

In `pom.xml` change `<version>1.0-SNAPSHOT</version>` to `<version>1.0.0-SNAPSHOT</version>` and add inside `<build>` before `<plugins>`:

```xml
        <resources>
            <!-- Only version.properties is filtered: it receives ${project.version}. -->
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
                <includes>
                    <include>com/fabianrodas/encryptdrive/version.properties</include>
                </includes>
            </resource>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>false</filtering>
                <excludes>
                    <exclude>com/fabianrodas/encryptdrive/version.properties</exclude>
                </excludes>
            </resource>
        </resources>
```

Create `src/main/resources/com/fabianrodas/encryptdrive/version.properties`:

```properties
version=${project.version}
```

- [ ] **Step 4: Add `App.version()`**

In `App.java` add imports `java.io.InputStream` and `java.util.Properties`, and:

```java
    /** The Maven project version, filtered into version.properties at build time. */
    static String version() {
        Properties properties = new Properties();

        try (InputStream in = App.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "unknown";
            }

            properties.load(in);
        } catch (IOException e) {
            return "unknown";
        }

        return properties.getProperty("version", "unknown");
    }
```

- [ ] **Step 5: Show it in Vault Settings**

In `vault-settings.fxml`, inside the details `GridPane` after the `CREATED` row, add:

```xml
                        <Label styleClass="detail-label" text="APP VERSION" GridPane.rowIndex="4" />
                        <Label fx:id="appVersionLabel"
                               styleClass="detail-value"
                               GridPane.columnIndex="1"
                               GridPane.rowIndex="4" />
```

In `VaultSettingsController` add the field and set it first in `initialize` (so it shows even without an open vault):

```java
    @FXML
    private Label appVersionLabel;
```

```java
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        appVersionLabel.setText(App.version());

        if (!VaultSessionService.isOpen()) {
            return;
        }
        // existing lines unchanged
```

- [ ] **Step 6: Keep the existing packaging script working**

In `scripts/package-windows.ps1` replace the jar copy and `--app-version` lines:

```powershell
$version = ([xml](Get-Content pom.xml)).project.version
Copy-Item "target/EncryptDrive-$version.jar" "target/modules/" -Force
```
and in the jpackage call:
```powershell
        --app-version ($version -replace '-SNAPSHOT$', '') `
```

- [ ] **Step 7: Run the focused tests**

Run: `mvn -B -q test "-Dtest=ReleaseMetadataTest,UiFlowTest"`
Expected: PASS (UiFlowTest skips cleanly only if no desktop session).

- [ ] **Step 8: Full suite + layout**

Run: `mvn -B clean verify`
Expected: BUILD SUCCESS; `UiLayoutTest` still passes for `#settingsNavButton` at 1000×600 and 1920×1040; `target/classes/com/fabianrodas/encryptdrive/version.properties` contains `version=1.0.0-SNAPSHOT`.

- [ ] **Step 9: Check no other hard-coded application version remains**

Run: `git grep -nE "1\.0-SNAPSHOT|--app-version 1" -- ':!docs/superpowers'`
Expected: no output.

- [ ] **Step 10: Ledger + commit**

Ledger: Maven version `1.0.0-SNAPSHOT`.

```bash
git add pom.xml src/main/resources/com/fabianrodas/encryptdrive/version.properties src/main/java/com/fabianrodas/encryptdrive/App.java src/main/resources/com/fabianrodas/encryptdrive/vault-settings.fxml src/main/java/com/fabianrodas/encryptdrive/VaultSettingsController.java scripts/package-windows.ps1 src/test/java/com/fabianrodas/encryptdrive/ReleaseMetadataTest.java src/test/java/com/fabianrodas/encryptdrive/UiFlowTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "feat: expose the canonical Maven version in the app" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** `App.version()` equals the pom version; Vault Settings shows it; no hard-coded app version outside the pom; build green.
