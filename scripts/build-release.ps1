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
        --java-options "-Dprism.order=sw" `
        --icon packaging/windows/EncryptDrive.ico `
        --module-path target/modules `
        --module "com.fabianrodas.encryptdrive/com.fabianrodas.encryptdrive.App" `
        --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --ignore-signing-information"
}
Sign-IfConfigured "target/dist/EncryptDrive/EncryptDrive.exe"

New-Item -ItemType Directory -Force $out | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$imagePath = (Resolve-Path "target/dist/EncryptDrive").Path
$portablePath = Join-Path (Resolve-Path $out).Path (Split-Path $portable -Leaf)
if (Test-Path $portablePath) { Remove-Item -LiteralPath $portablePath -Force }
$archive = [System.IO.Compression.ZipFile]::Open(
    $portablePath,
    [System.IO.Compression.ZipArchiveMode]::Create
)
try {
    $archive.CreateEntry("EncryptDrive/") | Out-Null
    Get-ChildItem $imagePath -Directory -Recurse -Force | ForEach-Object {
        $relative = $_.FullName.Substring($imagePath.Length).TrimStart([char[]]@("\", "/")).Replace("\", "/")
        $archive.CreateEntry("EncryptDrive/$relative/") | Out-Null
    }
    Get-ChildItem $imagePath -File -Recurse -Force | ForEach-Object {
        $relative = $_.FullName.Substring($imagePath.Length).TrimStart([char[]]@("\", "/")).Replace("\", "/")
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive,
            $_.FullName,
            "EncryptDrive/$relative",
            [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
    }
} finally {
    $archive.Dispose()
}

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
