function Test-ReleasePrismConfiguration {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $AppConfigPath
    )

    if (-not (Test-Path -LiteralPath $AppConfigPath -PathType Leaf)) {
        return $false
    }

    $inJavaOptions = $false
    foreach ($line in Get-Content -LiteralPath $AppConfigPath) {
        $trimmed = $line.Trim()
        if ($trimmed -match '^\[.*\]$') {
            $inJavaOptions = $trimmed -ceq '[JavaOptions]'
            continue
        }

        if ($inJavaOptions -and $trimmed -ceq 'java-options=-Dprism.order=sw') {
            return $true
        }
    }

    return $false
}

Export-ModuleMember -Function Test-ReleasePrismConfiguration
