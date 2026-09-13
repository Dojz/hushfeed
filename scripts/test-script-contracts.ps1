<#
.SYNOPSIS
    Exercise the shared patch-target and guarded device replacement contracts.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'device-install.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern, [string]$Message)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "$Message Unexpected error: $($_.Exception.Message)"
    }
    throw "$Message No error was raised."
}

$catalog = Get-Content -LiteralPath (Join-Path $Root 'patches-list.json') -Raw | ConvertFrom-Json
$target = Get-PatchTarget -PatchList $catalog
Assert-True ($target.PackageName -eq 'com.zhiliaoapp.musically') 'The catalog package was not resolved.'
Assert-True ($target.PackageVersion -eq '46.2.3') 'The catalog version was not resolved.'

$futureCatalog = [pscustomobject]@{
    patches = @([pscustomobject]@{
        name = 'future target'
        compatiblePackages = [pscustomobject]@{ 'com.example.future' = @('99.4.2') }
    })
}
$futureTarget = Get-PatchTarget -PatchList $futureCatalog
Assert-True ($futureTarget.PackageName -eq 'com.example.future' -and
    $futureTarget.PackageVersion -eq '99.4.2') 'A synthetic future target was replaced by a pinned value.'

$twoVersions = [pscustomobject]@{
    patches = @([pscustomobject]@{
        name = 'two versions'
        compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0', '2.0.0') }
    })
}
Assert-Throws { Get-PatchTarget -PatchList $twoVersions } '*Expected one compatible version*' `
    'A catalog with two versions was accepted.'
$missingTarget = [pscustomobject]@{ patches = @([pscustomobject]@{ name = 'missing target' }) }
Assert-Throws { Get-PatchTarget -PatchList $missingTarget } '*has no compatible package*' `
    'A patch without compatibility metadata was accepted.'

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase ("hushfeed-script-test-" + [guid]::NewGuid().ToString('N'))))
$requiredPrefix = $tempBase.TrimEnd('\') + '\'
if (-not $caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create test files outside the temporary directory: $caseRoot"
}
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $fakeAdb = Join-Path $caseRoot 'adb.cmd'
    $log = Join-Path $caseRoot 'adb.log'
    $mode = Join-Path $caseRoot 'mode.txt'
    $fakeBody = @'
@echo off
set /p FAKE_ADB_MODE=<"%~dp0mode.txt"
echo mode=%FAKE_ADB_MODE% args=%*>>"%~dp0adb.log"
if "%3|%4|%5"=="shell|pm|path" goto package_path
if "%3"=="uninstall" goto uninstall
exit /b 0
:package_path
if "%FAKE_ADB_MODE%"=="check-fail" goto check_fail
if "%FAKE_ADB_MODE%"=="present" echo package:/data/app/example/base.apk
if "%FAKE_ADB_MODE%"=="uninstall-fail" echo package:/data/app/example/base.apk
exit /b 0
:uninstall
if "%FAKE_ADB_MODE%"=="uninstall-fail" goto uninstall_fail
echo Success
exit /b 0
:check_fail
exit /b 17
:uninstall_fail
exit /b 19
'@
    [System.IO.File]::WriteAllText($fakeAdb, $fakeBody, [System.Text.Encoding]::ASCII)

    [System.IO.File]::WriteAllText($mode, 'absent', [System.Text.Encoding]::ASCII)
    $removed = Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'CLEAN' -PackageName 'com.example.app'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True (-not $removed) 'An absent package was reported as removed.'
    Assert-True ($calls.Count -eq 1 -and $calls[0] -like '*shell pm path com.example.app') `
        'The absent-package path attempted an uninstall.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'present', [System.Text.Encoding]::ASCII)
    $removed = Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'READY' -PackageName 'com.example.app'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True $removed 'An installed package was not removed.'
    Assert-True ($calls.Count -eq 2 -and $calls[0] -like '*shell pm path com.example.app' -and
        $calls[1] -like '*uninstall com.example.app') 'The installed-package path did not check then uninstall.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'check-fail', [System.Text.Encoding]::ASCII)
    Assert-Throws {
        Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'BROKEN' -PackageName 'com.example.app'
    } '*could not check*' 'An ADB transport failure was treated as an absent package.'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True ($calls.Count -eq 1) 'The check-failure path continued after ADB failed.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'uninstall-fail', [System.Text.Encoding]::ASCII)
    Assert-Throws {
        Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'LOCKED' -PackageName 'com.example.app'
    } '*uninstall failed*' 'An uninstall failure was accepted.'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True ($calls.Count -eq 2) 'The uninstall-failure path did not perform exactly a check and uninstall.'
} finally {
    if ($caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$consumerScripts = @('patch-for-device.ps1', 'verify-all-patches.ps1', 'measure-patch-heap.ps1')
foreach ($name in $consumerScripts) {
    $text = Get-Content -LiteralPath (Join-Path $PSScriptRoot $name) -Raw
    Assert-True ($text -notmatch '(?m)expectedPackageVersion\s*=\s*[''\"]') `
        "$name pins an expected package version instead of reading the catalog."
    Assert-True ($text -match 'Get-PatchTarget') "$name does not read its target through Get-PatchTarget."
}

$global:LASTEXITCODE = 0
Write-Host '[scripts] patch target and guarded replacement contracts passed'
