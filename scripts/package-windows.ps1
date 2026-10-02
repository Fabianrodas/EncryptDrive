# Builds a portable Windows app-image with its own Java runtime:
#   target/dist/EncryptDrive/EncryptDrive.exe
# Requires JDK 21 (jpackage) and Maven on PATH. Run from any directory.

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

function Invoke-Checked([string] $what, [scriptblock] $command) {
    & $command
    if ($LASTEXITCODE -ne 0) {
        throw "$what failed with exit code $LASTEXITCODE"
    }
}

# verify runs the whole test suite; its package phase copies the runtime
# dependencies to target/modules (see maven-dependency-plugin in pom.xml).
Invoke-Checked "Maven build" { mvn -B clean verify }

$version = ([xml](Get-Content pom.xml)).project.version
Copy-Item "target/EncryptDrive-$version.jar" "target/modules/" -Force
Remove-Item "target/dist" -Recurse -Force -ErrorAction SilentlyContinue

# Bouncy Castle ships as a signed modular JAR, which jlink refuses unless told
# to drop the signature files; the remaining options are jpackage's defaults.
Invoke-Checked "jpackage" {
    jpackage `
        --type app-image `
        --name EncryptDrive `
        --dest target/dist `
        --app-version ($version -replace '-SNAPSHOT$', '') `
        --vendor "Fabian Rodas" `
        --module-path target/modules `
        --module "com.fabianrodas.encryptdrive/com.fabianrodas.encryptdrive.App" `
        --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --ignore-signing-information"
}

Write-Host "Portable app-image: target/dist/EncryptDrive/EncryptDrive.exe"
