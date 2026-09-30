# Checks that target/dist/EncryptDrive is self-contained.
#   -Launch  also starts the app with no Java on PATH or JAVA_HOME and waits
#            for its window (closes it again afterwards).

param([switch] $Launch)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

$image = "target/dist/EncryptDrive"
$failures = @()

if (-not (Test-Path "$image/EncryptDrive.exe")) {
    $failures += "EncryptDrive.exe is missing"
}

if (-not (Test-Path "$image/runtime/bin/server/jvm.dll")) {
    $failures += "the bundled Java runtime is missing"
}

$release = "$image/runtime/release"

if (Test-Path $release) {
    $modules = (Select-String -Path $release -Pattern '^MODULES=').Line

    foreach ($module in "java.base", "javafx.controls", "javafx.fxml", "com.google.gson",
            "org.bouncycastle.provider", "com.fabianrodas.encryptdrive") {
        if ($modules -notmatch "(^|[`" ])$([regex]::Escape($module))([`" ]|$)") {
            $failures += "the runtime lacks module $module"
        }
    }
} else {
    $failures += "runtime/release is missing"
}

# Nothing in the launcher configuration may point at the build machine's JDK.
$jdkPaths = @("Program Files\Java")
if ($env:JAVA_HOME) {
    $jdkPaths += $env:JAVA_HOME
}

foreach ($path in $jdkPaths) {
    $hits = Get-ChildItem "$image/app" -File -ErrorAction SilentlyContinue |
        Select-String -SimpleMatch $path

    if ($hits) {
        $failures += "launcher configuration references $path"
    }
}

if ($Launch -and $failures.Count -eq 0) {
    $env:JAVA_HOME = $null
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"

    # The jpackage launcher starts a child EncryptDrive.exe that hosts the JVM
    # and owns the window, so the whole process tree is checked.
    $launcher = Start-Process -FilePath (Resolve-Path "$image/EncryptDrive.exe") -PassThru
    $deadline = (Get-Date).AddSeconds(30)
    $window = $null

    do {
        Start-Sleep -Milliseconds 500
        $tree = @($launcher.Id) + @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($launcher.Id)" |
            ForEach-Object { $_.ProcessId })
        $window = Get-Process -Id $tree -ErrorAction SilentlyContinue |
            Where-Object { $_.MainWindowTitle -eq "EncryptDrive" } |
            Select-Object -First 1
        $launcher.Refresh()
    } until ($window -or $launcher.HasExited -or (Get-Date) -gt $deadline)

    if ($window) {
        Write-Host "EncryptDrive started without a system Java (process $($window.Id))."
    } elseif ($launcher.HasExited) {
        $failures += "the app exited on start (exit code $($launcher.ExitCode))"
    } else {
        $failures += "no EncryptDrive window appeared within 30 seconds"
    }

    Get-Process -Id $tree -ErrorAction SilentlyContinue | Stop-Process -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL: $_" }
    exit 1
}

Write-Host "Portable package verified: $image"
