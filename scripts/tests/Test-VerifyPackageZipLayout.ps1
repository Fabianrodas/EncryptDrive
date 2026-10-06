$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("EncryptDrive-verify-test-" + [guid]::NewGuid())
$artifactDir = Join-Path $testRoot "artifacts"
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null

try {
    $setupName = "EncryptDrive-1.0.0-SNAPSHOT-Setup.exe"
    $zipName = "EncryptDrive-1.0.0-SNAPSHOT-Windows-Portable.zip"
    $setupPath = Join-Path $artifactDir $setupName
    $zipPath = Join-Path $artifactDir $zipName
    $setupStream = [System.IO.File]::Create($setupPath)
    try { $setupStream.SetLength((10 * 1MB) + 1) } finally { $setupStream.Dispose() }

    $archive = [System.IO.Compression.ZipFile]::Open(
        $zipPath,
        [System.IO.Compression.ZipArchiveMode]::Create
    )
    try {
        $unsafePaths = @(
            "EncryptDrive/../escape.txt"
            "EncryptDrive/.. /space-escape.txt"
            "EncryptDrive/./dot.txt"
            "EncryptDrive//empty.txt"
            "EncryptDrive/app:stream"
            "EncryptDrive/NUL.txt"
            "EncryptDrive/bad?.txt"
            "EncryptDrive\backslash.txt"
            "outside.txt"
        )
        $entries = @{
            "EncryptDrive/" = ""
            "EncryptDrive/EncryptDrive.exe" = "launcher"
            "EncryptDrive/app/app.cfg" = ""
            "EncryptDrive/runtime/bin/server/jvm.dll" = "runtime"
            "EncryptDrive/runtime/release" = 'MODULES="java.base javafx.controls javafx.fxml com.google.gson org.bouncycastle.provider com.fabianrodas.encryptdrive"'
        }
        foreach ($unsafePath in $unsafePaths) { $entries[$unsafePath] = "must be rejected before extraction" }

        foreach ($entryName in $entries.Keys) {
            $entry = $archive.CreateEntry($entryName)
            if ($entries[$entryName]) {
                $writer = [System.IO.StreamWriter]::new($entry.Open())
                try { $writer.Write($entries[$entryName]) } finally { $writer.Dispose() }
            }
        }
    } finally {
        $archive.Dispose()
    }

    $setupHash = (Get-FileHash -LiteralPath $setupPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    @(
        "$setupHash *$setupName"
        "$zipHash *$zipName"
    ) | Set-Content -LiteralPath (Join-Path $artifactDir "SHA256SUMS.txt")

    $verifier = Join-Path $PSScriptRoot "..\verify-package.ps1"
    $powershell = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
    $output = & $powershell -NoProfile -ExecutionPolicy Bypass -File $verifier -ArtifactDir $artifactDir 2>&1
    $exitCode = $LASTEXITCODE
    $text = $output -join [Environment]::NewLine

    if ($exitCode -eq 0) {
        throw "The package verifier accepted a ZIP containing EncryptDrive/../escape.txt."
    }
    foreach ($unsafePath in $unsafePaths) {
        if (-not $text.Contains("unsafe ZIP path entry: $unsafePath")) {
            throw "The verifier did not identify unsafe ZIP path '$unsafePath':`n$text"
        }
    }
    if ($text -match "EncryptDrive.exe is missing|Release artifacts verified") {
        throw "The verifier continued into extraction or package inspection after rejecting the ZIP:`n$text"
    }

    Write-Output "All $($unsafePaths.Count) unsafe ZIP paths rejected before extraction."
} finally {
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    if (-not $resolvedTestRoot.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup outside the system temp directory: $resolvedTestRoot"
    }
    if (Test-Path -LiteralPath $resolvedTestRoot) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
