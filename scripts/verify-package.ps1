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
# its window. The jpackage launcher may start a child process that owns the
# window, so check newly started processes rather than only the launcher.
function Test-Launch([string] $exe) {
    $savedHome = $env:JAVA_HOME
    $savedPath = $env:PATH
    $existingIds = @(Get-Process | ForEach-Object Id)
    $env:JAVA_HOME = $null
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"

    try {
        $launcher = Start-Process -FilePath (Resolve-Path $exe) -PassThru
        $deadline = (Get-Date).AddSeconds(30)
        $window = $null

        do {
            Start-Sleep -Milliseconds 500
            $window = Get-Process -ErrorAction SilentlyContinue |
                Where-Object { $_.Id -notin $existingIds -and $_.MainWindowTitle -eq "EncryptDrive" } |
                Select-Object -First 1
            $launcher.Refresh()
        } until ($window -or (Get-Date) -gt $deadline)

        if ($window) {
            Write-Host "Started without a system Java: $exe"
        } elseif ($launcher.HasExited) {
            Fail "$exe exited on start (exit code $($launcher.ExitCode))"
        } else {
            Fail "no EncryptDrive window appeared within 30 seconds for $exe"
        }

        $processIds = @($launcher.Id)
        if ($window) { $processIds += $window.Id }
        Get-Process -Id $processIds -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        foreach ($processId in $processIds) {
            Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
        }
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
    $installAttempted = $false
    New-Item -ItemType Directory $vault | Out-Null
    Set-Content (Join-Path $vault "keep.txt") "must survive install and uninstall"
    $vaultHash = (Get-FileHash (Join-Path $vault "keep.txt")).Hash

    try {
        $arguments = "/qn INSTALLDIR=`"$installDir`" /l*v `"$installLog`""
        $installAttempted = $true
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
    } finally {
        if ($installAttempted) {
            try {
                $remainingEntry = Get-UninstallEntry
                if ($remainingEntry) {
                    $cleanup = Start-Process msiexec.exe -ArgumentList "/x", $remainingEntry.PSChildName, "/qn" -PassThru
                    $cleanup.WaitForExit()
                    if ($cleanup.ExitCode -ne 0) { Fail "cleanup uninstall failed with exit code $($cleanup.ExitCode)" }
                }
            } catch {
                Fail "cleanup uninstall failed: $($_.Exception.Message)"
            }

            try {
                if (Test-Path $installDir) { Remove-Item -LiteralPath $installDir -Recurse -Force }
            } catch {
                Fail "could not remove temporary install directory: $($_.Exception.Message)"
            }
        }
    }
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
    for ($attempt = 0; $attempt -lt 5 -and (Test-Path -LiteralPath $scratch); $attempt++) {
        Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $scratch) { Start-Sleep -Milliseconds 250 }
    }
    if (Test-Path -LiteralPath $scratch) { Fail "could not remove verifier temporary directory: $scratch" }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL: $_" }
    exit 1
}

Write-Host "Release artifacts verified: $ArtifactDir"
