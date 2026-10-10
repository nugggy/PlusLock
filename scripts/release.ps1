# Publishes a new PlusLock version from this PC.
# Builds and signs the APK locally (the signing key never leaves this machine),
# tags the commit, and uploads the APK to GitHub Releases. Phones update themselves.
#
#   .\scripts\release.ps1 0.2.0          # stable
#   .\scripts\release.ps1 0.3.0-beta.1   # beta channel only
#
# Needs: keystore.properties in the project root, and the GitHub CLI signed in (gh auth login).
param([Parameter(Mandatory = $true)][string]$Version)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

if ($Version -notmatch '^\d+\.\d+\.\d+(-[a-z]+\.\d+)?$') { throw "Version must look like 1.2.3 or 1.2.3-beta.1" }
if (-not (Test-Path "$root\keystore.properties")) { throw "keystore.properties is missing, so the APK can't be signed." }
if (git status --porcelain) { throw "Commit or stash your changes first." }

$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { $gh = @("C:\Program Files\GitHub CLI\gh.exe", "$env:LOCALAPPDATA\Programs\GitHub CLI\gh.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1 }
if (-not $gh) { throw "GitHub CLI not found. Install it with: winget install GitHub.cli" }
& $gh auth status *> $null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI isn't signed in. Run: gh auth login" }

$tag = "v$Version"
$repo = "nugggy/PlusLock"

Write-Host "Building and signing PlusLock $tag..."
& "$root\gradlew.bat" assembleRelease "-Ppluslock.version=$Version" --console=plain -q
if ($LASTEXITCODE -ne 0) { throw "Build failed." }

# Fixed asset names mean releases/latest/download/PlusLock.apk always points at the newest stable build.
$dist = "$root\dist\$tag"
New-Item -ItemType Directory -Force $dist | Out-Null
$apk = "$dist\PlusLock.apk"
Copy-Item "$root\app\build\outputs\apk\release\app-release.apk" $apk -Force
(Get-FileHash $apk -Algorithm SHA256).Hash.ToLower() | Out-File -Encoding ascii -NoNewline "$apk.sha256"

git tag -a $tag -m "PlusLock $tag"
git push origin HEAD
git push origin $tag

$ghArgs = @("release", "create", $tag, $apk, "$apk.sha256", "--repo", $repo, "--title", "PlusLock $tag", "--generate-notes")
if ($Version.Contains('-')) { $ghArgs += "--prerelease" }
& $gh @ghArgs
if ($LASTEXITCODE -ne 0) { throw "Upload to GitHub failed. The tag $tag was pushed; rerun 'gh release create' or delete the tag and try again." }

Write-Host ""
Write-Host "Published $tag"
Write-Host "This version: https://github.com/$repo/releases/download/$tag/PlusLock.apk"
if (-not $Version.Contains('-')) { Write-Host "Always latest: https://github.com/$repo/releases/latest/download/PlusLock.apk" }
