# Publishes a new PlusLock version: tags the current commit and pushes it.
# GitHub Actions then builds, signs and publishes the APK, and phones update themselves.
#
#   .\scripts\release.ps1 0.2.0          # stable
#   .\scripts\release.ps1 0.3.0-beta.1   # beta channel only
param([Parameter(Mandatory = $true)][string]$Version)

$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+(-[a-z]+\.\d+)?$') {
    throw "Version must look like 1.2.3 or 1.2.3-beta.1"
}
if (git status --porcelain) { throw "Commit or stash your changes first." }

$tag = "v$Version"
git tag -a $tag -m "PlusLock $tag"
git push origin HEAD
git push origin $tag
Write-Host "Pushed $tag. Watch the build at https://github.com/nugggy/PlusLock/actions"
