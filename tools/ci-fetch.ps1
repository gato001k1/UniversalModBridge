[CmdletBinding()]
param(
  [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path $Root).Path
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$Cache = Join-Path $Root '.ci-cache/downloads'
$MojangManifestUrl = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
$LaunchWrapperUrl = 'https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar'
$McpSrgUrl = 'https://mcp.zeith.org/mcp/1.7.10/mcp-1.7.10-srg.zip'
$ForgeVersions = @{
  '1.7.10' = '10.13.4.1614-1.7.10'
  '1.12.2' = '14.23.5.2860'
  '1.16.5' = '36.2.39'
}

New-Item -ItemType Directory -Force $Cache | Out-Null

function Get-Sha1([string]$Path) {
  (Get-FileHash -Algorithm SHA1 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Get-TextUrl([string]$Url) {
  $response = Invoke-WebRequest -UseBasicParsing -Uri $Url
  if ($response.Content -is [byte[]]) { return [Text.Encoding]::ASCII.GetString($response.Content).Trim() }
  return $response.Content.ToString().Trim()
}

function Download-Verified([string]$Url, [string]$Sha1, [string]$Destination) {
  if ($Sha1 -notmatch '^[0-9a-fA-F]{40}$') { throw "No valid SHA-1 supplied for $Url" }
  $Destination = [IO.Path]::GetFullPath($Destination)
  New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($Destination)) | Out-Null
  if (Test-Path -LiteralPath $Destination) {
    if ((Get-Sha1 $Destination) -eq $Sha1.ToLowerInvariant()) { return }
    Remove-Item -LiteralPath $Destination -Force
  }
  $tmp = "$Destination.$PID.download"
  try {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $tmp
    if ((Get-Sha1 $tmp) -ne $Sha1.ToLowerInvariant()) { throw "SHA-1 mismatch for $Url" }
    Move-Item -LiteralPath $tmp -Destination $Destination -Force
  } finally {
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
  }
}

function Download-Maven([string]$Base, [string]$Group, [string]$Artifact, [string]$Version, [string]$FileName, [string]$Destination) {
  $path = "$($Group.Replace('.', '/'))/$Artifact/$Version/$FileName"
  $url = "$Base/$path"
  $sha = ((Get-TextUrl "$url.sha1") -split '\s+')[0]
  Download-Verified $url $sha $Destination
}

function Get-MojangVersion([string]$Version) {
  $manifest = Invoke-RestMethod -Uri $MojangManifestUrl
  $entry = $manifest.versions | Where-Object { $_.id -eq $Version } | Select-Object -First 1
  if (-not $entry) { throw "Mojang piston-meta has no version $Version" }
  Invoke-RestMethod -Uri $entry.url
}

function Get-PlatformKey {
  $onWindows = ($env:OS -eq 'Windows_NT') -or ($PSVersionTable.PSEdition -eq 'Desktop')
  $onMacOS = (Get-Variable IsMacOS -ValueOnly -ErrorAction SilentlyContinue) -eq $true
  $onLinux = (Get-Variable IsLinux -ValueOnly -ErrorAction SilentlyContinue) -eq $true
  $os = if ($onWindows) { 'windows' } elseif ($onMacOS) { 'osx' } elseif ($onLinux) { 'linux' } else { throw 'Unsupported runner OS' }
  $arch = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
  $arm = $arch -in @('arm64', 'arm')
  [pscustomobject]@{ Os = $os; Arm = $arm; Arch = $arch }
}

function Library-Allowed($Rules, [string]$Os) {
  if (-not $Rules) { return $true }
  $allowed = $false
  foreach ($rule in @($Rules)) {
    $ruleMatches = $true
    if ($rule.os -and $rule.os.name -and $rule.os.name -ne $Os) { $ruleMatches = $false }
    if ($ruleMatches) { $allowed = ($rule.action -eq 'allow') }
  }
  return $allowed
}

function Choose-Native([object]$Library, $Platform) {
  if (-not $Library.downloads.classifiers) { return $null }
  $names = @($Library.downloads.classifiers.PSObject.Properties.Name)
  $wanted = if ($Platform.Os -eq 'windows') {
    if ($Platform.Arm) { @('natives-windows-arm64', 'natives-windows-64', 'natives-windows') }
    else { @('natives-windows-64', 'natives-windows') }
  } elseif ($Platform.Os -eq 'linux') {
    if ($Platform.Arm) { @('natives-linux-arm64', 'natives-linux-aarch64', 'natives-linux') }
    else { @('natives-linux-64', 'natives-linux') }
  } else {
    if ($Platform.Arm) { @('natives-macos-arm64', 'natives-osx-arm64', 'natives-macos', 'natives-osx') }
    else { @('natives-macos', 'natives-osx') }
  }
  foreach ($key in $wanted) {
    if ($names -contains $key) { return $Library.downloads.classifiers.PSObject.Properties[$key].Value }
  }
  return $null
}

function Download-MojangVersion([string]$Version, [string]$Target, [string]$ClasspathFile) {
  $json = Get-MojangVersion $Version
  New-Item -ItemType Directory -Force $Target | Out-Null
  $client = Join-Path $Target 'client.jar'
  Download-Verified $json.downloads.client.url $json.downloads.client.sha1 $client
  if ($json.downloads.server) { Download-Verified $json.downloads.server.url $json.downloads.server.sha1 (Join-Path $Target 'server.jar') }

  $platform = Get-PlatformKey
  $libraryRoot = Join-Path $Target 'libraries'
  $classpath = [Collections.Generic.List[string]]::new()
  foreach ($lib in @($json.libraries)) {
    if (-not (Library-Allowed $lib.rules $platform.Os)) { continue }
    if ($lib.downloads.artifact) {
      $a = $lib.downloads.artifact
      $out = Join-Path $libraryRoot ($a.path -replace '/', [IO.Path]::DirectorySeparatorChar)
      Download-Verified $a.url $a.sha1 $out
      $classpath.Add((Resolve-Path $out).Path)
    }
    $native = Choose-Native $lib $platform
    if ($native -and (Library-Allowed $lib.rules $platform.Os)) {
      $out = Join-Path $libraryRoot ($native.path -replace '/', [IO.Path]::DirectorySeparatorChar)
      Download-Verified $native.url $native.sha1 $out
      $classpath.Add((Resolve-Path $out).Path)
    }
  }
  $classpath.Add((Resolve-Path $client).Path)
  New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($ClasspathFile)) | Out-Null
  [IO.File]::WriteAllText($ClasspathFile, (($classpath | Select-Object -Unique) -join [IO.Path]::PathSeparator) + [Environment]::NewLine, $Utf8NoBom)
  Write-Host "Mojang ${Version}: client + $($classpath.Count) native classpath entries"
}

function Copy-File([string]$Source, [string]$Destination) {
  New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($Destination)) | Out-Null
  Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Download-LibraryPath([string]$RelativePath, [string]$Destination) {
  $relativeUrl = ($RelativePath -replace '\\', '/')
  foreach ($base in @('https://maven.minecraftforge.net', 'https://repo1.maven.org/maven2')) {
    $url = "$base/$relativeUrl"
    try {
      $sha = (Get-TextUrl "$url.sha1").Split()[0]
      Download-Verified $url $sha $Destination
      return
    } catch {
      if ($base -eq 'https://repo1.maven.org/maven2') { throw "Unable to fetch Forge launcher library $RelativePath" }
    }
  }
}

function Get-ForgeRelativePath([string]$Value) {
  $normalized = $Value.Trim().Trim('"') -replace '\\', '/'
  $marker = 'research/visual/mc1710-native/'
  $index = $normalized.ToLowerInvariant().IndexOf($marker)
  if ($index -ge 0) { return $normalized.Substring($index + $marker.Length) }
  if ($normalized.StartsWith('libraries/', [StringComparison]::OrdinalIgnoreCase)) { return $normalized }
  if ($normalized.StartsWith('versions/', [StringComparison]::OrdinalIgnoreCase)) { return $normalized }
  return $null
}

function Split-LauncherClasspath([string]$Value) {
  $clean = $Value.Trim().Trim('"')
  if ($clean.Contains(';')) { return @($clean -split ';' | Where-Object { $_ }) }
  return @([regex]::Split($clean, '(?<=\.jar):') | Where-Object { $_ })
}

function Get-MavenRelativePath([string]$Coordinate) {
  $parts = $Coordinate.Split(':')
  if ($parts.Count -lt 3) { return $null }
  $group = $parts[0].Replace('.', '/')
  $artifact = $parts[1]
  $version = $parts[2]
  $classifier = if ($parts.Count -gt 3) { "-$($parts[3])" } else { '' }
  return "libraries/$group/$artifact/$version/$artifact-$version$classifier.jar"
}

function Write-ForgeClasspath([string]$V17Root, [string]$ForgeJar, [string]$LaunchWrapperJar, [string]$ClientJar, [string]$Output) {
  $launchCommandPath = Join-Path $V17Root 'launch-cmd.txt'
  if (-not (Test-Path -LiteralPath $launchCommandPath)) {
    # Fresh checkouts use the checked-in classpath order from the official 1.7.10 Forge launcher.
    $launchCommandPath = Join-Path $PSScriptRoot 'forge-1710-launch-classpath.txt'
  }
  if (-not (Test-Path -LiteralPath $launchCommandPath)) { throw "Missing Forge launcher metadata: $launchCommandPath" }

  # Forge's official universal jar carries the version JSON used by its launcher.
  # Read that list for the Forge/FML portion, then use the checked-in launch command
  # only for the inherited vanilla tail. This prevents a vanilla-only list from ever
  # replacing the Forge launcher list.
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $forgeZip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ForgeJar).Path)
  try {
    $versionEntry = $forgeZip.GetEntry('version.json')
    if (-not $versionEntry) { throw "Forge universal jar has no version.json: $ForgeJar" }
    $reader = New-Object IO.StreamReader($versionEntry.Open())
    try { $forgeVersionJson = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
  } finally { $forgeZip.Dispose() }
  $libraryRoot = (Resolve-Path -LiteralPath (Join-Path $V17Root 'libraries')).Path
  $forgeRelative = 'libraries/' + $ForgeJar.Substring($libraryRoot.Length).TrimStart('\', '/')
  $forgeRelative = $forgeRelative -replace '\\', '/'
  $lwRelative = 'libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar'
  $forgeEntries = [Collections.Generic.List[string]]::new()
  foreach ($library in @($forgeVersionJson.libraries)) {
    if ($library.clientreq -eq $false) { continue }
    $relative = Get-MavenRelativePath ([string]$library.name)
    if ([string]$library.name -like 'net.minecraftforge:forge:*') { $relative = $forgeRelative }
    if ($relative) { $forgeEntries.Add($relative) }
  }
  if (-not ($forgeEntries -contains $forgeRelative) -or -not ($forgeEntries -contains $lwRelative)) {
    throw 'Forge version.json did not provide both the Forge universal jar and LaunchWrapper'
  }

  $command = Get-Content -LiteralPath $launchCommandPath -Raw
  $match = [regex]::Match($command, '(?is)-cp\s+"([^"]+)"')
  if (-not $match.Success) { throw "Forge launcher metadata has no -cp list: $launchCommandPath" }

  $templateEntries = [Collections.Generic.List[string]]::new()
  foreach ($entry in (Split-LauncherClasspath $match.Groups[1].Value)) {
    $relative = Get-ForgeRelativePath $entry
    if ($relative) { $templateEntries.Add($relative) }
  }
  if ($templateEntries.Count -lt $forgeEntries.Count) { throw 'Forge launch command is shorter than Forge version.json' }
  for ($i = 0; $i -lt $forgeEntries.Count; $i++) {
    if ($templateEntries[$i].ToLowerInvariant() -ne $forgeEntries[$i].ToLowerInvariant()) {
      throw "Forge launch command/version.json mismatch at classpath entry $i"
    }
  }
  $ordered = [Collections.Generic.List[string]]::new()
  foreach ($relative in $forgeEntries) { $ordered.Add($relative) }
  for ($i = $forgeEntries.Count; $i -lt $templateEntries.Count; $i++) {
    # Preserve the launcher's duplicate entries and exact ordering. Some Forge
    # 1.7.10 launch commands intentionally list jopt-simple twice.
    $ordered.Add($templateEntries[$i])
  }

  $materialized = [Collections.Generic.List[string]]::new()
  foreach ($relative in $ordered) {
    $candidate = Join-Path $V17Root ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $candidate) -and $relative.StartsWith('libraries/', [StringComparison]::OrdinalIgnoreCase)) {
      Download-LibraryPath $relative.Substring('libraries/'.Length) $candidate
    }
    if (-not (Test-Path -LiteralPath $candidate)) { throw "Forge classpath entry is missing: $relative" }
    $materialized.Add((Resolve-Path -LiteralPath $candidate).Path)
  }
  $versionJar = Join-Path $V17Root 'versions/1.7.10-Forge10.13.4.1614-1.7.10/1.7.10-Forge10.13.4.1614-1.7.10.jar'
  if (-not (Test-Path -LiteralPath $versionJar)) { throw "Forge version jar is missing: $versionJar" }
  $versionPath = (Resolve-Path -LiteralPath $versionJar).Path
  if (-not ($materialized -contains $versionPath)) { $materialized.Add($versionPath) }
  [IO.File]::WriteAllText($Output, ($materialized -join [IO.Path]::PathSeparator) + [Environment]::NewLine, $Utf8NoBom)
  Write-Host "Forge 1.7.10: $($materialized.Count) launcher classpath entries"
}

function Assert-LegacyRuntime([string]$Path) {
  $expectedSha256 = '9c283503e63e5d5fe72c25e561970120aa568fddeb8d97c589e6253ebb808d19'
  $expectedEntrySha256 = '8447b4388899e4c37ea6398082ecd3d9c21ec9986c069048b393609712bca4bc'
  $expectedEntries = 1811
  if (-not (Test-Path -LiteralPath $Path)) {
    throw "Missing verified legacy runtime cache: $Path (restore the CI cache; ci-fetch never regenerates this DEBUG_SAVE jar)"
  }
  $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
  if ($actual -ne $expectedSha256) { throw "Legacy DEBUG_SAVE runtime SHA-256 mismatch: expected $expectedSha256, got $actual" }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [IO.Compression.ZipFile]::OpenRead($Path)
  try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
  } finally { $zip.Dispose() }
  $sorted = [string[]]$names
  [Array]::Sort($sorted, [StringComparer]::Ordinal)
  $entryBytes = [Text.Encoding]::UTF8.GetBytes(($sorted -join "`n") + "`n")
  $entryHash = (-join ([Security.Cryptography.SHA256]::Create().ComputeHash($entryBytes) | ForEach-Object { $_.ToString('x2') }))
  if ($names.Count -ne $expectedEntries -or $entryHash -ne $expectedEntrySha256) {
    throw "Legacy DEBUG_SAVE runtime entry-list mismatch: expected $expectedEntries/$expectedEntrySha256, got $($names.Count)/$entryHash"
  }
  Write-Host "Legacy DEBUG_SAVE runtime verified: $expectedEntries entries, SHA-256 $actual"
}

$junitDir = Join-Path $Root 'tools/junit'
Download-Maven 'https://repo1.maven.org/maven2' 'org.ow2.asm' 'asm' '9.9' 'asm-9.9.jar' (Join-Path $junitDir 'asm-9.9.jar')
foreach ($artifact in @('asm-tree', 'asm-commons', 'asm-analysis', 'asm-util')) {
  Download-Maven 'https://repo1.maven.org/maven2' 'org.ow2.asm' $artifact '9.9' "$artifact-9.9.jar" (Join-Path $junitDir "$artifact-9.9.jar")
}
Download-Maven 'https://repo1.maven.org/maven2' 'com.google.code.gson' 'gson' '2.14.0' 'gson-2.14.0.jar' (Join-Path $junitDir 'gson.jar')
Download-Maven 'https://repo1.maven.org/maven2' 'org.junit.platform' 'junit-platform-console-standalone' '1.12.2' 'junit-platform-console-standalone-1.12.2.jar' (Join-Path $junitDir 'junit-platform-console-standalone.jar')
Download-Maven 'https://repo1.maven.org/maven2' 'org.apache.commons' 'commons-compress' '1.21' 'commons-compress-1.21.jar' (Join-Path $junitDir 'commons-compress-1.21.jar')

$v17Root = Join-Path $Root 'research/visual/mc1710-native'
$v17Version = '1.7.10-Forge10.13.4.1614-1.7.10'
Download-MojangVersion '1.7.10' $v17Root (Join-Path $v17Root 'classpath.generated.txt')
$v17Client = Join-Path $v17Root 'client.jar'
Copy-File $v17Client (Join-Path $v17Root "versions/$v17Version/$v17Version.jar")

$forge17 = Join-Path $v17Root "libraries/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"
Download-Maven 'https://maven.minecraftforge.net' 'net.minecraftforge' 'forge' '1.7.10-10.13.4.1614-1.7.10' 'forge-1.7.10-10.13.4.1614-1.7.10-universal.jar' $forge17
$lw17 = Join-Path $v17Root 'libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar'
Download-Verified $LaunchWrapperUrl (Get-TextUrl "$LaunchWrapperUrl.sha1").Split()[0] $lw17
Write-ForgeClasspath $v17Root $forge17 $lw17 $v17Client (Join-Path $v17Root 'classpath.txt')

$v26Root = Join-Path $Root 'research/visual/mc262-vanilla'
Download-MojangVersion '26.2' (Join-Path $Root 'research/jars/26.2') (Join-Path $v26Root 'classpath.generated.txt')
Copy-Item -Path (Join-Path $Root 'research/jars/26.2/libraries/*') -Destination (Join-Path $v26Root 'libraries') -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath (Join-Path $v26Root 'classpath.generated.txt') -Destination (Join-Path $v26Root 'classpath.txt') -Force

foreach ($version in @('1.12.2', '1.16.5')) {
  $target = Join-Path $Root "research/jars/$version"
  Download-MojangVersion $version $target (Join-Path $target 'classpath.txt')
  $forgeVersion = $ForgeVersions[$version]
  $forgeName = "forge-$version-$forgeVersion-universal.jar"
  Download-Maven 'https://maven.minecraftforge.net' 'net.minecraftforge' 'forge' "$version-$forgeVersion" $forgeName (Join-Path $target $forgeName)
}

$lw1122 = Join-Path $Root 'research/out/legacy-1122/libs/launchwrapper-1.12.jar'
if (-not (Test-Path -LiteralPath $lw1122)) {
  Copy-File (Join-Path $v17Root 'libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar') $lw1122
} else {
  Write-Host "Preserved cached LaunchWrapper copy: $lw1122"
}
$cp1165 = Join-Path $Root 'umb-legacy-1165/resources/classpath-1165.txt'
if (-not (Test-Path -LiteralPath $cp1165)) { throw "Tracked curated classpath is missing: $cp1165" }
Write-Host "Preserved curated 1.16.5 classpath: $cp1165"

# The MCP SRG archive is a text mapping, not a redistributable game/mod binary.  It is
# downloaded only in the build workspace and never enters a release artifact.
$mcpZip = Join-Path $Root 'research/mappings/ci-mcp-1.7.10-srg.zip'
if (-not (Test-Path -LiteralPath $mcpZip)) {
  $mcpSha = (Get-TextUrl "$McpSrgUrl.sha1").Split()[0]
  Download-Verified $McpSrgUrl $mcpSha $mcpZip
}
$mapping = Join-Path $Root 'research/mappings/joined-1.7.10.srg'
if (-not (Test-Path -LiteralPath $mapping)) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [IO.Compression.ZipFile]::OpenRead($mcpZip)
  try {
    $entry = $zip.Entries | Where-Object { $_.FullName -match '(?i)(^|/)joined\.srg$' } | Select-Object -First 1
    if (-not $entry) { throw 'MCP SRG archive has no joined.srg' }
    New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($mapping)) | Out-Null
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $mapping, $true)
  } finally { $zip.Dispose() }
}

# This raw jar is a one-time LaunchWrapper DEBUG_SAVE capture. It is deliberately
# not regenerated from Mojang/Forge inputs: doing so changes the class set and breaks
# the legacyside ABI. CI restores it from its cache and verifies both bytes and entries.
# It is gitignored (Minecraft-derived code), so a cold runner with no warm cache cannot
# produce it: warn and expose a step output so the workflow skips only the legacy 1.7.10
# steps instead of failing. Everything above this point is still fetched.
function Set-CiStepOutput([string]$Name, [string]$Value) {
  if ($env:GITHUB_OUTPUT) {
    [IO.File]::AppendAllText($env:GITHUB_OUTPUT, "$Name=$Value`n", $Utf8NoBom)
  }
}

$legacyRuntime = Join-Path $Root 'research/out/legacy/1.7.10-forge-srg-runtime.jar'
if (Test-Path -LiteralPath $legacyRuntime) {
  Assert-LegacyRuntime $legacyRuntime
  Set-CiStepOutput 'legacy-runtime' 'present'
} else {
  Write-Host "::warning::Missing legacy DEBUG_SAVE runtime jar: $legacyRuntime - it is gitignored and cannot be fetched from Mojang/Forge, so CI will skip the legacy 1.7.10 build/test steps that require it; release builds still fail without it"
  Set-CiStepOutput 'legacy-runtime' 'absent'
}

Write-Host 'CI-INPUTS-OK'
