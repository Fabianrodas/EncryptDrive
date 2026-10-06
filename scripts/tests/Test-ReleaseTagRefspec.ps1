$ErrorActionPreference = "Stop"

$workflowPath = Join-Path $PSScriptRoot "..\..\.github\workflows\release.yml"
$fetchLine = Select-String -LiteralPath $workflowPath -Pattern 'git fetch --force origin "([^"]+)"' |
    Select-Object -First 1
if (-not $fetchLine) {
    throw "The Release workflow's annotated-tag fetch command was not found."
}

$refspecExpression = $fetchLine.Matches[0].Groups[1].Value
if ($refspecExpression -notmatch '^refs/tags/\$(?:\{tag\}|tag):refs/tags/\$(?:\{tag\}|tag)$') {
    throw "The annotated-tag fetch refspec has an unexpected form: $refspecExpression"
}

$tag = "v1.0.0-rc.2"
$refspec = & ([scriptblock]::Create('"' + $refspecExpression + '"'))
$expected = "refs/tags/${tag}:refs/tags/${tag}"
if ($refspec -cne $expected) {
    throw "The annotated-tag fetch refspec expanded to '$refspec'; expected '$expected'."
}

Write-Output "Release tag fetch refspec expands correctly: $refspec"
