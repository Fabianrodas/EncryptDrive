$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot '..\ReleasePackageConfig.psm1') -Force -ErrorAction Stop

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('EncryptDrive-prism-config-' + [guid]::NewGuid())
$packageKinds = @('portable', 'installed')
New-Item -ItemType Directory -Path $testRoot | Out-Null

try {
    foreach ($packageKind in $packageKinds) {
        $appDir = Join-Path $testRoot "$packageKind/app"
        New-Item -ItemType Directory -Path $appDir -Force | Out-Null
        $config = Join-Path $appDir 'EncryptDrive.cfg'

        if (Test-ReleasePrismConfiguration -AppConfigPath $config) {
            throw "$packageKind package passed Prism validation without an EncryptDrive.cfg."
        }

        @'
[Application]
app.mainmodule=com.fabianrodas.encryptdrive/com.fabianrodas.encryptdrive.App

[JavaOptions]
java-options=-Djpackage.app-version=1.0.0
'@ | Set-Content -LiteralPath $config -Encoding ascii

        if (Test-ReleasePrismConfiguration -AppConfigPath $config) {
            throw "$packageKind package passed Prism validation with the RC2 hardware-default configuration."
        }

        Add-Content -LiteralPath $config 'java-options=-Dprism.order=sw'
        if (-not (Test-ReleasePrismConfiguration -AppConfigPath $config)) {
            throw "$packageKind package failed Prism validation with the required software pipeline option."
        }
    }

    Write-Output 'Portable and installed app-image configs reject missing/default Prism settings and accept prism.order=sw.'
} finally {
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if (-not $resolvedTestRoot.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup outside the system temp directory: $resolvedTestRoot"
    }
    if (Test-Path -LiteralPath $resolvedTestRoot) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
