# EncryptDrive v1.0.0 — Plan 04: Windows Packaging, CI, and History Gate

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce `EncryptDrive-<version>[-rc.N]-Setup.exe`, `EncryptDrive-<version>[-rc.N]-Windows-Portable.zip` and `SHA256SUMS.txt` from the Maven version with one script, verify them end to end with another, run both in CI, draft tagged releases, and resolve the historical `data/users.json` decision before anything is pushed.

**Architecture:** `scripts/build-release.ps1` derives version, Windows numeric version and file names from `pom.xml`, builds one jlinked app-image, zips it as the portable edition, and wraps the same image into a per-user `jpackage --type exe` installer with the stable upgrade UUID. `scripts/verify-package.ps1` checks only the distributable files (it extracts the ZIP it ships). CI runs verify → package → artifact checks; a tag workflow drafts releases.

**Tech Stack:** PowerShell 5.1/7, JDK 21 `jpackage`, WiX 3.14 binaries, `System.IO.Compression`, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-01-encryptdrive-v1.0.0-release-design.md` sections 4, 5, 6.2, 17, 18, 19, 21.6.

## Global Constraints

See the master plan. Upgrade UUID `b1c326c7-6ed8-4052-868d-459a9cc54cd9` never changes. No certificate, key or password in the repository. The installer never touches vaults. Every commit ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

---

### Task 20: WiX toolchain, application icon, `build-release.ps1`

**Purpose:** Spec 4.1, 4.2, 5, 5.1, 5.2, 6.2, 18.1, 18.2, 21.6.

**Files:**
- Create: `packaging/windows/EncryptDrive.ico` (binary, generated once from `logo.png`)
- Create: `scripts/build-release.ps1`
- Delete: `scripts/package-windows.ps1`
- Modify: `README.md` (build section points to the new script; full docs rewrite is T24)
- Modify: `src/test/java/com/fabianrodas/encryptdrive/ReleaseMetadataTest.java`

**Interfaces:**
- Consumes: `ReleaseMetadataTest.pomVersion()` (T3).
- Produces: `scripts/build-release.ps1 [-Channel rc.N] [-Release] [-SkipTests] [-DryRun]`; dry-run output lines `version=`, `windowsVersion=`, `portable=`, `installer=`, `checksums=`; `packaging/windows/EncryptDrive.ico`.

**Security:** signing only when `ENCRYPTDRIVE_SIGN_CERT_SHA1` names a certificate already in the user's store; nothing secret is read from files. The installer is per-user (no admin unless the user picks a protected folder).

- [x] **Step 1: Install WiX 3.14 for this user (no admin) and record its hash**

Installed WiX 3.14.1.8722 from the official `wix314-binaries.zip`; SHA-256 is recorded in `V1_RELEASE_STATE.md`. `candle.exe` and `light.exe` both print the expected version banner, and .NET Framework 3.5 is present.

```powershell
$wix = "$env:USERPROFILE\Tools\wix314"
New-Item -ItemType Directory -Force $wix | Out-Null
Invoke-WebRequest "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" -OutFile "$env:TEMP\wix314-binaries.zip"
(Get-FileHash "$env:TEMP\wix314-binaries.zip" -Algorithm SHA256).Hash
Expand-Archive "$env:TEMP\wix314-binaries.zip" -DestinationPath $wix -Force
$env:PATH = "$wix;$env:PATH"
candle.exe -? | Select-Object -First 1
light.exe -? | Select-Object -First 1
```
Expected: both print a WiX 3.14 banner. Record the SHA-256 in the ledger (T22 pins it in CI). If the URL is gone, take `wix314-binaries.zip` from the newest 3.14.x release on `https://github.com/wixtoolset/wix3/releases`. If `candle.exe` reports a missing .NET Framework 3.5, stop at the **conditional environment gate**: ask the user to enable "Windows Features → .NET Framework 3.5 (includes .NET 2.0 and 3.0)" (administrator), then resume here. PACKAGING_COMPLETE stays BLOCKED meanwhile.

- [x] **Step 2: Generate the icon once (Windows PowerShell 5.1)**

Generated `packaging/windows/EncryptDrive.ico` with 256, 48, 32, and 16 px PNG-compressed entries from `logo.png`; inspected the 256 px entry against the source logo.

```powershell
Add-Type -AssemblyName System.Drawing
$logo = [System.Drawing.Image]::FromFile((Resolve-Path "src/main/resources/com/fabianrodas/images/logo.png"))
$sizes = 256, 48, 32, 16
$images = foreach ($size in $sizes) {
    $bitmap = New-Object System.Drawing.Bitmap $size, $size
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.DrawImage($logo, 0, 0, $size, $size)
    $stream = New-Object System.IO.MemoryStream
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose(); $bitmap.Dispose()
    ,$stream.ToArray()
}
New-Item -ItemType Directory -Force packaging/windows | Out-Null
$file = [System.IO.File]::Create((Join-Path (Get-Location) "packaging/windows/EncryptDrive.ico"))
$writer = New-Object System.IO.BinaryWriter $file
$writer.Write([UInt16]0); $writer.Write([UInt16]1); $writer.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $writer.Write([byte]($sizes[$i] % 256)); $writer.Write([byte]($sizes[$i] % 256))   # 256 is stored as 0
    $writer.Write([byte]0); $writer.Write([byte]0)
    $writer.Write([UInt16]1); $writer.Write([UInt16]32)
    $writer.Write([UInt32]$images[$i].Length); $writer.Write([UInt32]$offset)
    $offset += $images[$i].Length
}
foreach ($image in $images) { $writer.Write($image) }
$writer.Close(); $logo.Dispose()
```
Expected: `packaging/windows/EncryptDrive.ico` exists (PNG-compressed ICO, 4 sizes). Open it in Explorer's preview once to confirm it shows the logo.

- [x] **Step 3: Write the failing tests** (add to `ReleaseMetadataTest`)

```java
    @Test
    void scriptsAndWorkflowsHardCodeNoApplicationVersion() throws IOException {
        String numeric = pomVersion().replace("-SNAPSHOT", "");
        List<Path> files;

        try (Stream<Path> scripts = Files.list(Path.of("scripts"));
                Stream<Path> workflows = Files.list(Path.of(".github", "workflows"))) {
            files = Stream.concat(scripts, workflows).toList();
        }

        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(text.contains(numeric), file + " hard-codes " + numeric);
            assertFalse(text.contains("1.0-SNAPSHOT"), file + " hard-codes 1.0-SNAPSHOT");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void releaseNamesDeriveFromTheProjectVersionAndChannel() throws Exception {
        String version = pomVersion();
        String dir = "target/release/" + version + "/";

        Map<String, String> dev = dryRun();
        assertEquals(version, dev.get("version"));
        assertEquals(version.replace("-SNAPSHOT", ""), dev.get("windowsVersion"));
        assertEquals(dir + "EncryptDrive-" + version + "-Setup.exe", dev.get("installer"));
        assertEquals(dir + "EncryptDrive-" + version + "-Windows-Portable.zip", dev.get("portable"));
        assertEquals(dir + "SHA256SUMS.txt", dev.get("checksums"));

        Map<String, String> rc = dryRun("-Channel", "rc.2");
        assertEquals(dir + "EncryptDrive-" + version + "-rc.2-Setup.exe", rc.get("installer"));
        assertEquals(dir + "EncryptDrive-" + version + "-rc.2-Windows-Portable.zip", rc.get("portable"));

        assertNotEquals(0, buildRelease("-DryRun", "-Channel", "beta").exit());
        assertEquals(version.endsWith("-SNAPSHOT"), buildRelease("-DryRun", "-Release").exit() != 0);
    }

    private record Run(int exit, String output) {
    }

    private static Map<String, String> dryRun(String... arguments) throws Exception {
        List<String> all = new ArrayList<>(List.of("-DryRun"));
        all.addAll(List.of(arguments));
        Run run = buildRelease(all.toArray(String[]::new));
        assertEquals(0, run.exit(), run.output());

        Map<String, String> values = new HashMap<>();
        for (String line : run.output().split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return values;
    }

    private static Run buildRelease(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/build-release.ps1"
        ));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
    }
```
(imports `assertFalse`, `assertNotEquals`, `java.util.*`, `java.util.stream.Stream`, `org.junit.jupiter.api.condition.EnabledOnOs`, `org.junit.jupiter.api.condition.OS`).

- [x] **Step 4: Run to confirm failure**

Run: `mvn -B -q test "-Dtest=ReleaseMetadataTest"`
Observed: `releaseNamesDeriveFromTheProjectVersionAndChannel` failed because `scripts/build-release.ps1` did not exist; the no-hardcoded-version and app-version tests passed.

- [x] **Step 5: Write `scripts/build-release.ps1`**

```powershell
<#
Builds the Windows release artifacts from the Maven project version:

  target/release/<version>/EncryptDrive-<version>[-<channel>]-Windows-Portable.zip
  target/release/<version>/EncryptDrive-<version>[-<channel>]-Setup.exe
  target/release/<version>/SHA256SUMS.txt

  -Channel rc.1  adds the release channel to the file names (release candidates)
  -Release       requires a non-SNAPSHOT project version (tag builds)
  -SkipTests     builds with -DskipTests (CI runs mvn verify as its own step)
  -DryRun        prints the version and artifact paths and builds nothing

Signing is optional: set ENCRYPTDRIVE_SIGN_CERT_SHA1 to the thumbprint of a
code-signing certificate in the current user's certificate store (and
optionally ENCRYPTDRIVE_SIGN_TIMESTAMP_URL) to sign EncryptDrive.exe and the
installer with signtool. Nothing secret is read from the repository.

Requires JDK 21 (jpackage), Maven, and WiX 3.x (candle.exe, light.exe) on PATH.
#>
param(
    [string] $Channel = "",
    [switch] $Release,
    [switch] $SkipTests,
    [switch] $DryRun
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

# Never change: Windows recognizes upgrades of EncryptDrive by this id.
$UpgradeUuid = "b1c326c7-6ed8-4052-868d-459a9cc54cd9"

function Invoke-Checked([string] $what, [scriptblock] $command) {
    & $command
    if ($LASTEXITCODE -ne 0) {
        throw "$what failed with exit code $LASTEXITCODE"
    }
}

function Sign-IfConfigured([string] $file) {
    if (-not $env:ENCRYPTDRIVE_SIGN_CERT_SHA1) {
        return
    }

    $timestamp = if ($env:ENCRYPTDRIVE_SIGN_TIMESTAMP_URL) { $env:ENCRYPTDRIVE_SIGN_TIMESTAMP_URL } else { "http://timestamp.digicert.com" }
    Invoke-Checked "signtool ($file)" {
        signtool.exe sign /sha1 $env:ENCRYPTDRIVE_SIGN_CERT_SHA1 /fd SHA256 /td SHA256 /tr $timestamp $file
    }
}

$version = ([xml](Get-Content pom.xml)).project.version
$numeric = $version -replace '-SNAPSHOT$', ''

if ($numeric -notmatch '^\d+\.\d+\.\d+$') {
    throw "Project version '$version' is not MAJOR.MINOR.PATCH[-SNAPSHOT]"
}

if ($Release -and $version -ne $numeric) {
    throw "Release builds need a non-SNAPSHOT project version (found $version)"
}

if ($Channel -and $Channel -notmatch '^rc\.\d+$') {
    throw "Channel must look like rc.1 (found '$Channel')"
}

$label = if ($Channel) { "$version-$Channel" } else { $version }
$out = "target/release/$version"
$portable = "$out/EncryptDrive-$label-Windows-Portable.zip"
$installer = "$out/EncryptDrive-$label-Setup.exe"

if ($DryRun) {
    "version=$version"
    "windowsVersion=$numeric"
    "portable=$portable"
    "installer=$installer"
    "checksums=$out/SHA256SUMS.txt"
    exit 0
}

# mvn clean removes every earlier build, including old release folders.
$maven = if ($SkipTests) { @("-B", "clean", "package", "-DskipTests") } else { @("-B", "clean", "verify") }
Invoke-Checked "Maven build" { mvn @maven }
Copy-Item "target/EncryptDrive-$version.jar" "target/modules/" -Force

$common = @(
    "--name", "EncryptDrive",
    "--app-version", $numeric,
    "--vendor", "Fabian Rodas",
    "--copyright", "Copyright (c) 2026 Fabian Rodas",
    "--description", "Local-first encrypted file vault"
)

# Bouncy Castle ships as a signed modular JAR, which jlink refuses unless told
# to drop the signature files; EncryptDrive never registers it as a JCE provider.
Invoke-Checked "jpackage app-image" {
    jpackage --type app-image --dest target/dist @common `
        --icon packaging/windows/EncryptDrive.ico `
        --module-path target/modules `
        --module "com.fabianrodas.encryptdrive/com.fabianrodas.encryptdrive.App" `
        --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --ignore-signing-information"
}
Sign-IfConfigured "target/dist/EncryptDrive/EncryptDrive.exe"

New-Item -ItemType Directory -Force $out | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    (Resolve-Path "target/dist/EncryptDrive").Path,
    (Join-Path (Resolve-Path $out).Path (Split-Path $portable -Leaf)),
    [System.IO.Compression.CompressionLevel]::Optimal,
    $true
)

# The installer installs the application only. Vaults are created and opened
# inside EncryptDrive wherever the user chooses; install and uninstall never touch them.
Invoke-Checked "jpackage installer" {
    jpackage --type exe --app-image target/dist/EncryptDrive --dest target/installer @common `
        --win-per-user-install --win-dir-chooser `
        --win-menu --win-menu-group EncryptDrive `
        --win-shortcut --win-shortcut-prompt `
        --win-upgrade-uuid $UpgradeUuid
}
Move-Item "target/installer/EncryptDrive-$numeric.exe" $installer
Sign-IfConfigured $installer

Get-ChildItem $out -File | Where-Object Name -ne "SHA256SUMS.txt" | Sort-Object Name | ForEach-Object {
    "{0} *{1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.Name
} | Set-Content "$out/SHA256SUMS.txt" -Encoding ascii

Write-Host "Release artifacts in $out"
```

- [x] **Step 6: Remove the old script and fix the README build section**

```bash
git rm scripts/package-windows.ps1
```
In `README.md` replace the two `powershell ... package-windows.ps1` / `verify-portable-package.ps1` lines with:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-release.ps1
```
and the sentence about `target/dist/EncryptDrive` with "Artifacts are written to `target/release/<version>/`." (T21 adds the verify line; T24 rewrites the section.)

- [x] **Step 7: Run the tests, then a real build**

Run: `mvn -B -q test "-Dtest=ReleaseMetadataTest"` → PASS.
Run (with WiX on PATH): `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1`
Expected: BUILD SUCCESS, then `target/release/1.0.0-SNAPSHOT/` contains exactly `EncryptDrive-1.0.0-SNAPSHOT-Setup.exe`, `EncryptDrive-1.0.0-SNAPSHOT-Windows-Portable.zip`, `SHA256SUMS.txt`.

Observed: the focused tests and real build passed. `target/release/1.0.0-SNAPSHOT/` contains exactly the setup EXE (37,786,112 bytes), portable ZIP (35,804,244 bytes), and SHA256SUMS.txt. The app-image top level is `app`, `runtime`, and `EncryptDrive.exe`; `app/EncryptDrive.cfg` names the expected module and contains no machine JDK or user path. Both package checksums are in the generated SHA256SUMS.txt.

Inspect and record in the ledger:
```powershell
Get-ChildItem target/dist/EncryptDrive -Force | Select-Object Name
Get-ChildItem target/dist/EncryptDrive/app -Force | Select-Object Name
Get-Content target/dist/EncryptDrive/app/EncryptDrive.cfg
Get-Content target/release/1.0.0-SNAPSHOT/SHA256SUMS.txt
```
Expected: top level `app`, `runtime`, `EncryptDrive.exe` (note any other item for T21's allowlist); the `.cfg` names the module and contains no `C:\Program Files\Java` or user path.

- [x] **Step 8: Full suite + commit**

Final `mvn -B clean verify`: BUILD SUCCESS, 406 tests, 0 failures, 0 errors, 3 opt-in skips, 2 min 39 s.

```bash
git add packaging/windows/EncryptDrive.ico scripts/build-release.ps1 README.md src/test/java/com/fabianrodas/encryptdrive/ReleaseMetadataTest.java docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "build: produce versioned portable and installer artifacts" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

Committed as `build: produce versioned portable and installer artifacts`.

**Acceptance:** one script produces both artifacts and checksums with names derived from the pom (+ channel); numeric Windows version; stable upgrade UUID; per-user installer with directory chooser, Start Menu group and optional desktop shortcut; signing hook without secrets; no hard-coded versions; build green.

---

### Task 21: `verify-package.ps1` — portable, ZIP, installer and silent install checks

**Purpose:** Spec 18.3 (bundled runtime; launch without `JAVA_HOME`/system Java; no build-machine JDK path; only intended resources; no vault/test data; installed app launches and uninstalls) and 4.1 (uninstall keeps vaults), 21.6 (portable verifier keeps launching without Java).

**Files:**
- Create: `scripts/verify-package.ps1`
- Delete: `scripts/verify-portable-package.ps1`
- Modify: `README.md` (verify line)

**Interfaces:**
- Consumes: artifacts and names from T20.
- Produces: `scripts/verify-package.ps1 [-Channel rc.N] [-ArtifactDir <dir>] [-Launch] [-Install]`; exit code 0 = verified.

**Security:** checks the ZIP the user will download (extracted to a temp folder), not the build tree. `-Install` refuses to run if EncryptDrive is already installed for this user, so it can never replace or remove a real installation.

- [x] **Step 1: Write `scripts/verify-package.ps1`**

The verifier parses and reports missing artifact paths with a non-zero exit when run before packaging output exists.
The implementation checks HKCU and HKLM for an existing uninstall entry (the MSI records this entry under HKLM on this Windows build), passes a verbose MSI log path during `-Install`, and verifies the registered version, installed files, shortcut, uninstall, and sentinel vault after each process exits.

```powershell
<#
Checks release artifacts written by build-release.ps1, or downloaded from a
release, without trusting how they were built.

  (default)  checksums match; the portable ZIP holds only EncryptDrive/ with a
             bundled runtime, no build-machine paths, and no vault, account,
             or test data; the installer exists
  -Launch    also starts the portable EncryptDrive.exe with no Java on PATH and
             no JAVA_HOME, and waits for its window
  -Install   also installs Setup.exe silently for the current user into a
             temporary folder, checks the uninstall entry and Start Menu
             shortcut, launches the installed app without Java, uninstalls it,
             and checks that a vault folder outside the install folder is
             untouched. Refuses to run if EncryptDrive is already installed.

  -ArtifactDir <dir>  folder with the artifacts (default target/release/<version>)
  -Channel rc.1       release channel used in the file names
#>
param(
    [string] $Channel = "",
    [string] $ArtifactDir = "",
    [switch] $Launch,
    [switch] $Install
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

$version = ([xml](Get-Content pom.xml)).project.version
$numeric = $version -replace '-SNAPSHOT$', ''
$label = if ($Channel) { "$version-$Channel" } else { $version }
if (-not $ArtifactDir) { $ArtifactDir = "target/release/$version" }

$zip = Join-Path $ArtifactDir "EncryptDrive-$label-Windows-Portable.zip"
$setup = Join-Path $ArtifactDir "EncryptDrive-$label-Setup.exe"
$sums = Join-Path $ArtifactDir "SHA256SUMS.txt"
$failures = [System.Collections.Generic.List[string]]::new()
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ("EncryptDrive-verify-" + [guid]::NewGuid())
New-Item -ItemType Directory $scratch | Out-Null
$uninstallKeys = @(
    "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall",
    "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall"
)

function Fail([string] $message) {
    $failures.Add($message)
}

function Get-UninstallEntry {
    foreach ($key in $uninstallKeys) {
        $entry = Get-ChildItem $key -ErrorAction SilentlyContinue | Get-ItemProperty |
            Where-Object DisplayName -eq "EncryptDrive" | Select-Object -First 1
        if ($entry) { return $entry }
    }
}

# Starts an EncryptDrive.exe with no Java on PATH or JAVA_HOME and waits for
# its window. The jpackage launcher starts a child process that owns the
# window, so the whole process tree is checked.
function Test-Launch([string] $exe) {
    $savedHome = $env:JAVA_HOME
    $savedPath = $env:PATH
    $env:JAVA_HOME = $null
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"

    try {
        $launcher = Start-Process -FilePath (Resolve-Path $exe) -PassThru
        $deadline = (Get-Date).AddSeconds(30)
        $window = $null

        do {
            Start-Sleep -Milliseconds 500
            $tree = @($launcher.Id) + @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($launcher.Id)" |
                ForEach-Object { $_.ProcessId })
            $window = Get-Process -Id $tree -ErrorAction SilentlyContinue |
                Where-Object { $_.MainWindowTitle -eq "EncryptDrive" } | Select-Object -First 1
            $launcher.Refresh()
        } until ($window -or $launcher.HasExited -or (Get-Date) -gt $deadline)

        if ($window) {
            Write-Host "Started without a system Java: $exe"
        } elseif ($launcher.HasExited) {
            Fail "$exe exited on start (exit code $($launcher.ExitCode))"
        } else {
            Fail "no EncryptDrive window appeared within 30 seconds for $exe"
        }

        Get-Process -Id $tree -ErrorAction SilentlyContinue | Stop-Process -ErrorAction SilentlyContinue
    } finally {
        $env:JAVA_HOME = $savedHome
        $env:PATH = $savedPath
    }
}

function Test-Install([string] $installer) {
    if (Get-UninstallEntry) {
        Fail "EncryptDrive is already installed for this user; uninstall it first (this check never replaces a real installation)"
        return
    }

    $installDir = Join-Path $scratch "InstalledEncryptDrive"
    $vault = Join-Path $scratch "Sentinel vault"
    $installLog = Join-Path $scratch "installer.log"
    New-Item -ItemType Directory $vault | Out-Null
    Set-Content (Join-Path $vault "keep.txt") "must survive install and uninstall"
    $vaultHash = (Get-FileHash (Join-Path $vault "keep.txt")).Hash

    $arguments = "/qn INSTALLDIR=`"$installDir`" /l*v `"$installLog`""
    $process = Start-Process $installer -ArgumentList $arguments -PassThru
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        if (Test-Path $installLog) { Get-Content $installLog -Tail 30 | ForEach-Object { Write-Host $_ } }
        Fail "silent install failed with exit code $($process.ExitCode)"
        return
    }

    $entry = Get-UninstallEntry
    if (-not $entry) {
        Fail "no uninstall entry was registered"
        return
    }

    if ($entry.DisplayVersion -ne $numeric) { Fail "uninstall entry version is $($entry.DisplayVersion), expected $numeric" }

    $exe = Join-Path $installDir "EncryptDrive.exe"
    if (Test-Path $exe) { Test-Launch $exe } else { Fail "EncryptDrive.exe was not installed into $installDir" }

    $menu = Join-Path ([Environment]::GetFolderPath("Programs")) "EncryptDrive"
    if (-not (Get-ChildItem $menu -Filter *.lnk -ErrorAction SilentlyContinue)) { Fail "no Start Menu shortcut in $menu" }

    $process = Start-Process msiexec.exe -ArgumentList "/x", $entry.PSChildName, "/qn" -PassThru
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { Fail "silent uninstall failed with exit code $($process.ExitCode)" }
    if (Test-Path $exe) { Fail "EncryptDrive.exe is still present after uninstall" }
    if (Get-UninstallEntry) { Fail "the uninstall entry is still registered after uninstall" }
    if ((Get-FileHash (Join-Path $vault "keep.txt")).Hash -ne $vaultHash) { Fail "the vault folder changed during install or uninstall" }
}

try {
    foreach ($file in $zip, $setup, $sums) {
        if (-not (Test-Path $file)) { Fail "missing $file" }
    }

    if ($failures.Count -eq 0) {
        $expected = @{}
        foreach ($line in Get-Content $sums) {
            if ($line -match '^([0-9a-f]{64}) \*(.+)$') { $expected[$Matches[2]] = $Matches[1] } else { Fail "malformed checksum line: $line" }
        }
        if ($expected.Count -ne 2) { Fail "SHA256SUMS.txt must list exactly the installer and the portable ZIP" }
        foreach ($file in $zip, $setup) {
            $name = Split-Path $file -Leaf
            if ($expected[$name] -ne (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()) { Fail "checksum mismatch for $name" }
        }

        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $zip).Path)
        try { $entries = @($archive.Entries | ForEach-Object FullName) } finally { $archive.Dispose() }
        if ($entries | Where-Object { -not $_.StartsWith("EncryptDrive/") }) { Fail "ZIP entries must all be under EncryptDrive/" }
        if ($entries | Where-Object { $_.Contains("\") }) { Fail "ZIP entries must use forward slashes" }

        Expand-Archive $zip -DestinationPath $scratch
        $image = Join-Path $scratch "EncryptDrive"

        if (-not (Test-Path "$image/EncryptDrive.exe")) { Fail "EncryptDrive.exe is missing" }
        if (-not (Test-Path "$image/runtime/bin/server/jvm.dll")) { Fail "the bundled Java runtime is missing" }

        $top = (Get-ChildItem $image -Force | ForEach-Object Name | Sort-Object) -join ","
        if ($top -ne "app,EncryptDrive.exe,runtime") { Fail "unexpected top-level content: $top" }

        $release = "$image/runtime/release"
        if (Test-Path $release) {
            $modules = (Select-String -Path $release -Pattern '^MODULES=').Line
            foreach ($module in "java.base", "javafx.controls", "javafx.fxml", "com.google.gson",
                    "org.bouncycastle.provider", "com.fabianrodas.encryptdrive") {
                if ($modules -notmatch "(^|[`" ])$([regex]::Escape($module))([`" ]|$)") { Fail "the runtime lacks module $module" }
            }
        } else {
            Fail "runtime/release is missing"
        }

        # Nothing may point at the build machine's JDK or user folders.
        foreach ($path in @("Program Files\Java", $env:USERPROFILE, $env:JAVA_HOME) | Where-Object { $_ }) {
            if (Get-ChildItem "$image/app" -File -Recurse | Select-String -SimpleMatch $path) { Fail "app configuration references $path" }
        }

        # No vault, account, or test data may ship.
        Get-ChildItem $image -Recurse -Force | Where-Object {
            $_.Name -in @("vault.json", "users.enc", "users.json", ".encryptdrive", "lock") -or
            $_.Extension -in @(".edv", ".enc", ".part", ".tmp")
        } | ForEach-Object { Fail "packaged data file: $($_.FullName.Substring($image.Length))" }

        if ((Get-Item $setup).Length -lt 10MB) { Fail "the installer is suspiciously small" }
        $signature = (Get-AuthenticodeSignature $setup).Status
        Write-Host "Installer signature: $signature"
        if ($env:ENCRYPTDRIVE_SIGN_CERT_SHA1 -and $signature -ne "Valid") { Fail "the installer signature is $signature" }

        if ($Launch -and $failures.Count -eq 0) { Test-Launch "$image/EncryptDrive.exe" }
        if ($Install -and $failures.Count -eq 0) { Test-Install $setup }
    }
} finally {
    Remove-Item $scratch -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL: $_" }
    exit 1
}

Write-Host "Release artifacts verified: $ArtifactDir"
```
Adjust the top-level allowlist (`app,EncryptDrive.exe,runtime`) to exactly what T20 Step 7 recorded if jpackage adds another file; keep it exact.

- [x] **Step 2: Remove the old verifier; README line**

```bash
git rm scripts/verify-portable-package.ps1
```
README build section: add `powershell -ExecutionPolicy Bypass -File scripts/verify-package.ps1 -Launch -Install`.

- [x] **Step 3: Negative checks (prove the verifier fails when it should)**

On a copy of the artifacts in a scratch folder:
```powershell
$copy = "$env:TEMP\ed-verify-negative"; Remove-Item $copy -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item target/release/1.0.0-SNAPSHOT $copy -Recurse
Add-Content "$copy/EncryptDrive-1.0.0-SNAPSHOT-Setup.exe" "x"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-package.ps1 -ArtifactDir $copy; "exit=$LASTEXITCODE"
```
Expected: `FAIL: checksum mismatch for EncryptDrive-1.0.0-SNAPSHOT-Setup.exe`, `exit=1`. Then rebuild the copy, add a file `EncryptDrive/users.json` into a copy of the ZIP (with `System.IO.Compression.ZipFile` update mode), regenerate its checksum line, and expect `FAIL: packaged data file: \users.json`. Record both outcomes in the ledger.

Recorded: a tampered setup EXE failed with exit 1 on checksum mismatch; a ZIP containing `EncryptDrive/users.json` with refreshed checksums failed with exit 1 as packaged data. The first package build also exposed backslash ZIP entry names from .NET Framework `ZipFile.CreateFromDirectory`; `build-release.ps1` now writes normalized forward-slash entries, which the verifier accepts.

- [x] **Step 4: Positive run on this machine**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-package.ps1 -Launch -Install`
Expected: `Started without a system Java` twice (portable and installed), `Installer signature: NotSigned`, `Release artifacts verified`. If the silent install used a different install folder or shortcut default than assumed (`INSTALLDIR`, Start Menu shortcut under `/qn`), inspect with `msiexec /i <msi> /l*v log.txt` (extract the MSI by running Setup.exe with `/?` or reading jpackage's `--temp` output) and adjust the property name/assertion — never weaken the uninstall-entry, launch, uninstall or sentinel-vault checks. If a run aborts midway, clean up with: `Get-ChildItem HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall | Get-ItemProperty | Where-Object DisplayName -eq EncryptDrive | ForEach-Object { msiexec /x $_.PSChildName /qn }`.

Recorded: the combined standard-user run completed with exit 0; the installer was `NotSigned`; portable and installed launches worked without a system Java; the 1.0.0 uninstall entry and user Start Menu shortcut were present; uninstall removed the installed executable and entry; the sentinel vault hash was unchanged. Repeated successfully against the final rebuilt artifacts; the MSI phase took about three minutes on this host.
The MSI uninstall record appeared under HKLM on this machine while the app's registry values and shortcut were per-user. If an interrupted run needs cleanup, inspect both HKCU and HKLM; the verifier refuses an existing entry in either hive.

- [x] **Step 5: Full suite + commit**

Run: `mvn -B clean verify` → BUILD SUCCESS (`scriptsAndWorkflowsHardCodeNoApplicationVersion` now also scans `verify-package.ps1`).

Recorded: `mvn -B clean verify` → BUILD SUCCESS; 406 tests, 0 failures, 0 errors, 3 opt-in skips, 2 min 51 s.

```bash
git add -A -- README.md docs/superpowers/plans/2026-10-01-encryptdrive-v1.0.0-04-packaging-ci.md docs/superpowers/plans/V1_RELEASE_STATE.md scripts/build-release.ps1 scripts/verify-package.ps1 scripts/verify-portable-package.ps1
git commit -m "build: verify portable and installer artifacts end to end" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

**Acceptance:** verifier passes on good artifacts (launch without Java, silent install/launch/uninstall, sentinel vault untouched) and fails on tampered checksums and packaged data; build green; ledger has the evidence.

---

### Task 22: CI packaging job and tag release workflow

**Purpose:** Spec 19.1–19.3: every push/PR runs `mvn -B clean verify` on Java 21; a Windows job builds and verifies both artifacts; RC tags draft prereleases; `v1.0.0` drafts the stable release (never auto-published).

**Files:**
- Modify: `.github/workflows/build.yml`
- Create: `.github/workflows/release.yml`

**Interfaces:** consumes `build-release.ps1`, `verify-package.ps1`, the WiX SHA-256 recorded in T20 Step 1.

**Security:** `build.yml` keeps `contents: read`; only `release.yml` has `contents: write`, only for tags, and only creates drafts. The tag must be annotated, on `main`, and match the pom version.

- [ ] **Step 1: `build.yml`**

```yaml
name: Build

on:
  push:
    branches: ["**"]
  pull_request:

permissions:
  contents: read

jobs:
  verify:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v5

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "21"
          cache: maven

      # Tests create throwaway vaults in temporary folders; nothing is uploaded.
      - name: Verify
        run: mvn -B clean verify

  package:
    needs: verify
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v5

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "21"
          cache: maven

      - name: Install WiX 3
        shell: pwsh
        run: |
          $zip = Join-Path $env:RUNNER_TEMP "wix-binaries.zip"
          Invoke-WebRequest "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" -OutFile $zip
          if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne "<SHA-256 recorded in the ledger at T20 Step 1>") { throw "unexpected WiX download" }
          $wix = Join-Path $env:RUNNER_TEMP "wix"
          Expand-Archive $zip -DestinationPath $wix
          Add-Content $env:GITHUB_PATH $wix

      - name: Build release artifacts
        shell: pwsh
        run: ./scripts/build-release.ps1 -SkipTests

      - name: Verify release artifacts
        shell: pwsh
        run: ./scripts/verify-package.ps1 -Launch -Install

      - uses: actions/upload-artifact@v4
        with:
          name: encryptdrive-windows
          path: target/release/
          retention-days: 14
```
(Replace the angle-bracket text with the actual hash from the ledger before committing; the commit must contain the real 64-hex value.)

- [ ] **Step 2: `release.yml`**

```yaml
name: Release

on:
  push:
    tags: ["v*"]

permissions:
  contents: write

jobs:
  draft:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v5
        with:
          fetch-depth: 0

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "21"
          cache: maven

      - name: Check the tag
        id: tag
        shell: pwsh
        run: |
          $tag = "${{ github.ref_name }}"
          # actions/checkout stores the pushed tag as a plain commit ref; fetch the real tag object.
          git fetch --force origin "refs/tags/${tag}:refs/tags/${tag}"
          if ($LASTEXITCODE -ne 0) { throw "could not fetch $tag" }
          if ((git cat-file -t "refs/tags/$tag") -ne "tag") { throw "$tag must be an annotated tag" }
          git fetch origin main
          git merge-base --is-ancestor HEAD origin/main
          if ($LASTEXITCODE -ne 0) { throw "$tag does not point to a commit on main" }
          $version = ([xml](Get-Content pom.xml)).project.version
          if ($tag -notmatch '^v(\d+\.\d+\.\d+)(-(rc\.\d+))?$' -or $Matches[1] -ne $version) {
            throw "$tag does not match the project version $version"
          }
          "channel=$($Matches[3])" >> $env:GITHUB_OUTPUT
          "prerelease=$(if ($Matches[3]) { 'true' } else { 'false' })" >> $env:GITHUB_OUTPUT
          "version=$version" >> $env:GITHUB_OUTPUT

      - name: Verify
        run: mvn -B clean verify

      - name: Install WiX 3
        shell: pwsh
        run: |
          $zip = Join-Path $env:RUNNER_TEMP "wix-binaries.zip"
          Invoke-WebRequest "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" -OutFile $zip
          if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne "<same hash as build.yml>") { throw "unexpected WiX download" }
          $wix = Join-Path $env:RUNNER_TEMP "wix"
          Expand-Archive $zip -DestinationPath $wix
          Add-Content $env:GITHUB_PATH $wix

      - name: Build release artifacts
        shell: pwsh
        run: ./scripts/build-release.ps1 -Release -SkipTests -Channel "${{ steps.tag.outputs.channel }}"

      - name: Verify release artifacts
        shell: pwsh
        run: ./scripts/verify-package.ps1 -Channel "${{ steps.tag.outputs.channel }}" -Launch -Install

      - name: Release notes
        shell: pwsh
        run: |
          $version = "${{ steps.tag.outputs.version }}"
          $section = ((Get-Content CHANGELOG.md -Raw) -split '(?m)^## ') | Where-Object { $_.StartsWith("[$version]") }
          if (-not $section) { throw "CHANGELOG.md has no section for $version" }
          $notes = "## " + $section.Trim() + "`n`n" + (Get-Content docs/testing/release-notes-footer.md -Raw)
          Set-Content release-notes.md $notes -Encoding utf8

      # Always a draft: it is published by hand after the artifact smoke test.
      - name: Draft the GitHub release
        shell: pwsh
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          $files = Get-ChildItem "target/release/${{ steps.tag.outputs.version }}" -File | ForEach-Object FullName
          $flags = @("--draft", "--verify-tag", "--title", "EncryptDrive ${{ github.ref_name }}", "--notes-file", "release-notes.md")
          if ("${{ steps.tag.outputs.prerelease }}" -eq "true") { $flags += "--prerelease" }
          gh release create "${{ github.ref_name }}" @files @flags
```
`docs/testing/release-notes-footer.md` is created in T24 (checksum verification + unsigned-publisher note). Until T24 lands, no tag can exist, so the workflow is never triggered early.

- [ ] **Step 3: Static checks**

Run: `python -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]" .github/workflows/build.yml .github/workflows/release.yml` (install PyYAML with `python -m pip install --user pyyaml` if missing).
Expected: no output (valid YAML). Run `mvn -B -q test "-Dtest=ReleaseMetadataTest"` → PASS (no hard-coded version in workflows).

- [ ] **Step 4: Full suite + commit**

Run: `mvn -B clean verify` → BUILD SUCCESS.

```bash
git add .github/workflows/build.yml .github/workflows/release.yml docs/superpowers/plans/V1_RELEASE_STATE.md
git commit -m "ci: package Windows artifacts and draft tagged releases" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: Phase 04 boundary** — fresh-worktree `mvn -B clean verify`; superpowers:requesting-code-review over `<T20 commit>^..HEAD` (focus: scripts' failure modes, no secrets, workflow permissions); fix findings as separate commits.

- [ ] **Step 6: Stop at Human Gate H2** (below). After H2 resolves and the branch is pushed, watch the `Build` run for the pushed commit:

```powershell
$runs = Invoke-RestMethod "https://api.github.com/repos/Fabianrodas/EncryptDrive/actions/runs?branch=feature/encryptdrive-1.0&per_page=1"
$runs.workflow_runs[0] | Select-Object status, conclusion, head_sha, html_url
```
(public repository: no token needed; poll at most every few minutes). PACKAGING_COMPLETE becomes DONE only when both `verify` and `package` jobs conclude `success` for the branch head. If `-Launch`/`-Install` cannot work on the hosted runner (no interactive desktop), use superpowers:systematic-debugging; the only acceptable change is moving those two switches to a documented local/manual gate (T25/H3) — never dropping the artifact checks.

**Acceptance:** valid workflows committed; CI green on the pushed branch (verify + package with both artifacts verified); tag workflow drafts only, prerelease for `-rc.N`, refuses lightweight/non-main/mismatched tags.

---

### Human Gate H2: historical `data/users.json` and permission to push

**Why this stops:** rewriting history and force-pushing `main` are destructive to commit ids and to every existing clone; the spec requires explicit human confirmation (17.1). Pushing the branch and, later, `main` and annotated tags to `origin` are outward-facing actions.

**Facts to present:**
- `data/users.json` (fields `id, fullName, username, passwordHash, salt`) was added in `0030c7c`, changed in `9d866c1`, and is **still tracked in the current tip of `main` and `origin/main`**. It was removed only on the unpushed feature branch (`baf82ea`).
- Without a rewrite, merging the release branch removes it from the tip, but every historical commit on GitHub keeps it.
- Recommendation: **rewrite now**, before the first push of the release branch and before any tag, because no release tag exists yet and the branch is unpublished, so the rewrite only force-updates `main`. Regardless of the decision, change any real password that was ever used for those development accounts anywhere else.
- A rewrite cannot remove copies in forks, clones or caches; GitHub may keep unreachable commits viewable by id until GitHub Support purges them.

**Ask the user to choose exactly one:**
1. **Approve rewrite + force-push of `main`**, plus normal pushes of `feature/encryptdrive-1.0`, `main` (T27) and annotated release tags (T28, T30) for the rest of this workstream.
2. **Decline the rewrite**, but approve the normal pushes listed in option 1. The exposure is documented in `docs/SECURITY.md` (T24).
3. **Decline both** — PACKAGING_COMPLETE (CI evidence), RC_CREATED and later states stay BLOCKED until pushes are allowed.

**If option 1 — exact commands (run from the repository root, PowerShell):**

```powershell
# 0. Preconditions
git status --porcelain                                   # must print nothing
git rev-parse main origin/main feature/encryptdrive-1.0  # record in the ledger

# 1. Full backup of every ref (restore: git clone ..\EncryptDrive-pre-rewrite.bundle)
git bundle create ..\EncryptDrive-pre-rewrite.bundle --all
git bundle verify ..\EncryptDrive-pre-rewrite.bundle

# 2. Tool
python -m pip install --user git-filter-repo
python -m git_filter_repo --version

# 3. Rewrite all local branches (filter-repo removes the origin remote as a safety measure)
python -m git_filter_repo --invert-paths --path data/users.json --force
git remote add origin git@github.com:Fabianrodas/EncryptDrive.git

# 4. Verify locally before anything leaves the machine
git log --all --oneline -- data/users.json               # expect: no output
git rev-list --all | ForEach-Object { git ls-tree -r --name-only $_ } | Select-String -SimpleMatch "users.json"   # expect: no output
git log --oneline -3 feature/encryptdrive-1.0            # same messages as before, new ids
mvn -B clean verify                                      # expect: BUILD SUCCESS

# 5. Publish (the lease fails safely if origin/main moved since the audit)
git fetch origin
git push --force-with-lease=main:0f913d25b82fea4a1d41605325fbe9e140654cf9 origin main
git push -u origin feature/encryptdrive-1.0

# 6. Verify the remote
git ls-remote origin refs/heads/main refs/heads/feature/encryptdrive-1.0
git fetch origin; git log --oneline origin/main -- data/users.json   # expect: no output
```

**If option 2:** run only `git push -u origin feature/encryptdrive-1.0`, then the step-6 `ls-remote`.

**Evidence to record in the ledger:** chosen option; pre/post ref ids; outputs of step 4 and step 6. History cleanup status becomes `DONE (rewritten <date>)` or `DONE (declined, documented)`.

**Resume point:** T22 Step 6 (watch CI), then Plan 05 T23.
