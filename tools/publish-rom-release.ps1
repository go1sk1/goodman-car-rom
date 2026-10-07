param([Parameter(Mandatory=$true)][string]$Directory,
      [string]$Repository='go1sk1/goodman-car-rom')
$ErrorActionPreference='Stop'
$manifest=Get-Content -LiteralPath (Join-Path $Directory 'update.json') -Raw | ConvertFrom-Json
$assets=@(Get-Item -LiteralPath (Join-Path $Directory 'update.json'),(Join-Path $Directory 'update.sig'))
foreach($part in $manifest.parts) {
    $asset=Get-Item -LiteralPath (Join-Path $Directory ([IO.Path]::GetFileName(([uri]$part.url).AbsolutePath)))
    if ($asset.Length -ne $part.bytes -or (Get-FileHash -LiteralPath $asset.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne $part.sha256) { throw 'Release asset changed; stop publication.' }
    $assets+=$asset
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'Install GitHub CLI and run gh auth login before publishing.' }
& gh auth status
if ($LASTEXITCODE) { throw 'GitHub CLI login is required.' }
$notes=Join-Path $Directory 'release-notes.md'
[IO.File]::WriteAllText($notes,"Development GSI. Phone boot and vehicle operation remain unverified.`n`n$($manifest.notes)`n`nDownload assets from the ROM update screen; install manually in TWRP.",[Text.UTF8Encoding]::new($false))
# A draft is published only after all assets upload successfully. --latest is
# intentional for this owner's development download channel, which uses /latest.
& gh release create $manifest.build --repo $Repository --title "Goodman Car $($manifest.build) (development)" --notes-file $notes --draft
if ($LASTEXITCODE) { throw 'Draft creation failed; inspect the repository before retrying.' }
& gh release upload $manifest.build --repo $Repository @($assets.FullName)
if ($LASTEXITCODE) { throw 'Upload failed; draft remains unpublished.' }
& gh release edit $manifest.build --repo $Repository --draft=false --latest
if ($LASTEXITCODE) { throw 'Release publication failed.' }
