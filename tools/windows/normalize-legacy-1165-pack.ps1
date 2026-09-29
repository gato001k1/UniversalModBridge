param(
  [Parameter(Mandatory = $true)][string]$Pack,
  [Parameter(Mandatory = $true)][string]$Namespace,
  [switch]$MetadataOnly,
  [switch]$CheckStale
)

$ErrorActionPreference = 'Stop'
$resourceMajor = 88
$stampName = '.umb-1165-pack.json'

function Get-PackFingerprint([string]$Root) {
  $lines = @()
  $rootFull = (Get-Item -LiteralPath $Root).FullName.TrimEnd('\','/')
  foreach ($file in (Get-ChildItem -LiteralPath $Root -Recurse -File | Sort-Object FullName)) {
    if ($file.Name -eq $stampName) { continue }
    $rel = $file.FullName.Substring($rootFull.Length).TrimStart('\','/') -replace '\\','/'
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $lines += ($rel + '|' + $file.Length + '|' + $hash)
  }
  $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
  $sha = [Security.Cryptography.SHA256]::Create()
  try { return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant() }
  finally { $sha.Dispose() }
}

function Get-NormalizerHash() {
  return (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-JsonFile([string]$Path, [object]$Value) {
  $json = $Value | ConvertTo-Json -Depth 100
  [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Normalize-PathPart([string]$PathPart) {
  $PathPart = $PathPart -replace '^blocks/', 'block/'
  $PathPart = $PathPart -replace '^items/', 'item/'
  return $PathPart
}

function Split-ResourceLocation([string]$Value, [string]$DefaultNamespace) {
  $parts = $Value.Split(':', 2)
  if ($parts.Count -eq 2) {
    return @($parts[0], (Normalize-PathPart $parts[1]))
  }
  return @($DefaultNamespace, (Normalize-PathPart $Value))
}

function Normalize-ModelLocation([string]$Value, [string]$DefaultNamespace) {
  if ([string]::IsNullOrWhiteSpace($Value)) { return $Value }
  $loc = Split-ResourceLocation $Value $DefaultNamespace
  $path = $loc[1]
  if ($path.StartsWith('item/')) { return ($loc[0] + ':' + $path) }
  if ($path.StartsWith('block/')) { return ($loc[0] + ':' + $path) }
  return ($loc[0] + ':block/' + $path)
}

function Normalize-ParentLocation([string]$Value) {
  if ([string]::IsNullOrWhiteSpace($Value)) { return $Value }
  if ($Value -eq 'block') { return 'minecraft:block/block' }
  if ($Value -eq 'item') { return 'minecraft:item/generated' }
  # 1.16.5 spawn-egg models inherit the removed template_spawn_egg model. 26.2
  # has no minecraft:item/template_spawn_egg, so retaining the qualified legacy
  # parent produces a magenta item even though the rest of the pack is valid.
  if ($Value -eq 'item/template_spawn_egg' -or $Value -eq 'minecraft:item/template_spawn_egg') {
    return 'minecraft:item/generated'
  }
  if ($Value -match '^block/') { return 'minecraft:' + $Value }
  if ($Value -match '^item/') { return 'minecraft:' + $Value }
  return $Value
}

function Normalize-TextureLocation([string]$Value, [string]$DefaultNamespace, [string]$ModelKind) {
  if ([string]::IsNullOrWhiteSpace($Value) -or $Value.StartsWith('#')) { return $Value }
  $loc = Split-ResourceLocation $Value $DefaultNamespace
  $path = $loc[1]
  if (-not ($path.StartsWith('block/') -or $path.StartsWith('item/')) -and $path.IndexOf('/') -lt 0) {
    $path = $ModelKind + '/' + $path
  }
  if (-not ($Value.Contains(':'))) { return ($loc[0] + ':' + $path) }
  return ($loc[0] + ':' + $path)
}

function Get-StringProperty([string]$Text, [string]$Name) {
  $match = [Text.RegularExpressions.Regex]::Match($Text, '(?s)"' + [regex]::Escape($Name) + '"\s*:\s*"(?<value>[^"]+)"')
  if ($match.Success) { return $match.Groups['value'].Value }
  return $null
}

function Find-FallbackTexture([string]$ModelRelative, [string]$ModelText) {
  foreach ($key in @('front', 'side', 'texture', 'particle', 'top', 'bottom')) {
    $value = Get-StringProperty $ModelText $key
    if ($value -and -not $value.StartsWith('#')) { return (Normalize-TextureLocation $value $Namespace 'block') }
  }
  $leaf = [IO.Path]::GetFileNameWithoutExtension($ModelRelative)
  $textureRoot = Join-Path $Pack ('assets\' + $Namespace + '\textures')
  if (Test-Path -LiteralPath $textureRoot) {
    $candidate = Get-ChildItem -LiteralPath $textureRoot -Filter ($leaf + '*.png') -File -Recurse | Select-Object -First 1
    if ($candidate) {
      $relative = $candidate.FullName.Substring($textureRoot.Length).TrimStart('\','/') -replace '\\','/'
      return ($Namespace + ':' + [IO.Path]::ChangeExtension($relative, $null).TrimEnd('.'))
    }
  }
  return ($Namespace + ':block/' + $leaf)
}

function Convert-UnsupportedLoaderModel([string]$Text, [string]$ModelRelative, [string]$ModelKind) {
  if ($Text -notmatch '(?s)"loader"\s*:') { return $Text }
  $fallback = Find-FallbackTexture $ModelRelative $Text
  if ($ModelKind -eq 'item') {
    return ('{"parent":"minecraft:item/generated","textures":{"layer0":"' + $fallback + '"}}')
  }
  $front = Get-StringProperty $Text 'front'; if (-not $front) { $front = $fallback } else { $front = Normalize-TextureLocation $front $Namespace 'block' }
  $side = Get-StringProperty $Text 'side'; if (-not $side) { $side = $fallback } else { $side = Normalize-TextureLocation $side $Namespace 'block' }
  $top = Get-StringProperty $Text 'top'; if (-not $top) { $top = $side } else { $top = Normalize-TextureLocation $top $Namespace 'block' }
  $bottom = Get-StringProperty $Text 'bottom'; if (-not $bottom) { $bottom = $side } else { $bottom = Normalize-TextureLocation $bottom $Namespace 'block' }
  return ('{"parent":"minecraft:block/cube","textures":{"down":"' + $bottom + '","up":"' + $top + '","north":"' + $front + '","south":"' + $front + '","west":"' + $side + '","east":"' + $side + '","particle":"' + $fallback + '"}}')
}

function Ensure-BlockModelAlias([string]$RawValue) {
  $loc = Split-ResourceLocation $RawValue $Namespace
  if ($loc[0] -ne $Namespace) { return }
  $path = $loc[1]
  if ($path.StartsWith('block/') -or $path.StartsWith('item/')) { return }
  $source = Join-Path $Pack ('assets\' + $Namespace + '\models\' + $path + '.json')
  $target = Join-Path $Pack ('assets\' + $Namespace + '\models\block\' + $path + '.json')
  if ((Test-Path $source) -and -not (Test-Path $target)) {
    New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath $source -Destination $target
  }
}

function Normalize-Node([object]$Node, [string]$Key, [string]$ModelKind) {
  if ($null -eq $Node) { return }
  if ($Node -is [System.Collections.IList]) {
    for ($i = 0; $i -lt $Node.Count; $i++) { Normalize-Node $Node[$i] $Key $ModelKind }
    return
  }
  if ($Node -isnot [pscustomobject]) { return }
  foreach ($property in @($Node.PSObject.Properties)) {
    $name = $property.Name
    $value = $property.Value
    if ($name -eq 'model' -and $value -is [string]) {
      if ($ModelKind -eq 'blockstate') { Ensure-BlockModelAlias $value }
      $property.Value = Normalize-ModelLocation $value $Namespace
      continue
    }
    if ($name -eq 'parent' -and $value -is [string]) {
      $property.Value = Normalize-ParentLocation $value
      continue
    }
    if ($name -eq 'textures' -and $value -is [pscustomobject]) {
      foreach ($texture in @($value.PSObject.Properties)) {
        if ($texture.Value -is [string]) {
          $texture.Value = Normalize-TextureLocation $texture.Value $Namespace $ModelKind
        }
      }
      continue
    }
    Normalize-Node $value $name $ModelKind
  }
}

function Rewrite-BlockstateText([string]$Text) {
  $modelEvaluator = [Text.RegularExpressions.MatchEvaluator]{
    param($Match)
    $raw = $Match.Groups['value'].Value
    Ensure-BlockModelAlias $raw
    return ($Match.Groups['prefix'].Value + (Normalize-ModelLocation $raw $Namespace) + $Match.Groups['suffix'].Value)
  }
  $Text = [Text.RegularExpressions.Regex]::Replace(
    $Text,
    '(?<prefix>"model"\s*:\s*")(?<value>[^"]+)(?<suffix>")',
    $modelEvaluator)
  # Legacy 1.16 blockstates describe Forge properties (active/facing/variant/etc.) that the
  # host's generic 26.2 block registrations intentionally do not expose. Keeping those keys
  # makes the vanilla loader reject the entire definition. Preserve the first data-backed model
  # as the state-independent default; this is universal and avoids mod-specific property lists.
  $firstModel = Get-StringProperty $Text 'model'
  if ($firstModel) { return ('{"variants":{"":{"model":"' + $firstModel + '"}}}') }
  return $Text
}

function Rewrite-ModelText([string]$Text, [string]$ModelKind) {
  $spawnEggTemplate = $Text -match '(?s)"parent"\s*:\s*"(?:minecraft:)?item/template_spawn_egg"'
  $parentEvaluator = [Text.RegularExpressions.MatchEvaluator]{
    param($Match)
    return ($Match.Groups['prefix'].Value + (Normalize-ParentLocation $Match.Groups['value'].Value) + $Match.Groups['suffix'].Value)
  }
  $Text = [Text.RegularExpressions.Regex]::Replace(
    $Text,
    '(?<prefix>"parent"\s*:\s*")(?<value>[^"]+)(?<suffix>")',
    $parentEvaluator)

  # The modern generated item model is a valid, visible fallback for every legacy
  # spawn egg.  Egg colors are runtime item data in the old era and cannot be
  # represented by a removed 26.2 template model; using the vanilla egg sprite
  # keeps the item textured while the legacy item/use path remains authoritative.
  if ($spawnEggTemplate -and $ModelKind -eq 'item' -and $Text -notmatch '(?s)"textures"\s*:') {
    $Text = [Text.RegularExpressions.Regex]::Replace(
      $Text,
      '(?<prefix>"parent"\s*:\s*"minecraft:item/generated")',
      '$1,"textures":{"layer0":"minecraft:item/egg"}', 1)
  }

  $texturesEvaluator = [Text.RegularExpressions.MatchEvaluator]{
    param($Match)
    $valueEvaluator = [Text.RegularExpressions.MatchEvaluator]{
      param($ValueMatch)
      $raw = $ValueMatch.Groups['value'].Value
      return ($ValueMatch.Groups['prefix'].Value + (Normalize-TextureLocation $raw $Namespace $ModelKind) + $ValueMatch.Groups['suffix'].Value)
    }
    $body = [Text.RegularExpressions.Regex]::Replace(
      $Match.Groups['body'].Value,
      '(?<prefix>"[^"]+"\s*:\s*")(?<value>[^"]+)(?<suffix>")',
      $valueEvaluator)
    return ($Match.Groups['prefix'].Value + $body + $Match.Groups['suffix'].Value)
  }
  $Text = [Text.RegularExpressions.Regex]::Replace(
    $Text,
    '(?s)(?<prefix>"textures"\s*:\s*\{)(?<body>.*?)(?<suffix>\})',
    $texturesEvaluator)
  return $Text
}

function Write-ItemDefinition([string]$Path, [string]$ModelLocation) {
  $definition = [pscustomobject]@{
    model = [pscustomobject]@{
      type = 'minecraft:model'
      model = $ModelLocation
    }
  }
  Write-JsonFile $Path $definition
}

if (-not (Test-Path -LiteralPath $Pack -PathType Container)) { throw "pack directory missing: $Pack" }
$stampPath = Join-Path $Pack $stampName
if ($CheckStale) {
  $stamp = if (Test-Path -LiteralPath $stampPath) {
    try { Get-Content -LiteralPath $stampPath -Raw | ConvertFrom-Json } catch { $null }
  } else { $null }
  $actual = Get-PackFingerprint $Pack
  $stale = ($null -eq $stamp -or $stamp.schema -ne 1 -or
    $stamp.normalizerSha256 -ne (Get-NormalizerHash) -or $stamp.fingerprint -ne $actual)
  [pscustomobject]@{ stale = [bool]$stale; fingerprint = $actual; stamp = $stamp } | ConvertTo-Json -Compress -Depth 8
  exit $(if ($stale) { 3 } else { 0 })
}
$metaPath = Join-Path $Pack 'pack.mcmeta'
$meta = if (Test-Path -LiteralPath $metaPath) { Get-Content -LiteralPath $metaPath -Raw | ConvertFrom-Json } else { [pscustomobject]@{} }
if ($null -eq $meta.pack) { $meta | Add-Member -NotePropertyName pack -NotePropertyValue ([pscustomobject]@{}) }
$meta.pack.PSObject.Properties.Remove('pack_format')
$meta.pack | Add-Member -Force -NotePropertyName min_format -NotePropertyValue $resourceMajor
$meta.pack | Add-Member -Force -NotePropertyName max_format -NotePropertyValue $resourceMajor
Write-JsonFile $metaPath $meta

if ($MetadataOnly) {
  Write-Output ("normalized metadata pack={0} namespace={1} resource_format={2}" -f $Pack, $Namespace, $resourceMajor)
  exit 0
}

$nsRoot = Join-Path $Pack ('assets\' + $Namespace)
$blockstates = Join-Path $nsRoot 'blockstates'
if (Test-Path -LiteralPath $blockstates) {
  Get-ChildItem -LiteralPath $blockstates -Filter '*.json' -File -Recurse | ForEach-Object {
    $text = [IO.File]::ReadAllText($_.FullName)
    [IO.File]::WriteAllText($_.FullName, (Rewrite-BlockstateText $text), [Text.UTF8Encoding]::new($false))
  }
}

$models = Join-Path $nsRoot 'models'
if (Test-Path -LiteralPath $models) {
  # Get-ChildItem returns absolute paths even when -LiteralPath is relative.
  # Resolve the root first so item/ is recognized (and not accidentally treated
  # as a block model), regardless of the caller's current directory.
  $modelsRoot = (Get-Item -LiteralPath $models).FullName.TrimEnd('\','/')
  $itemModels = @()
  Get-ChildItem -LiteralPath $models -Filter '*.json' -File -Recurse | ForEach-Object {
    $relative = $_.FullName.Substring($modelsRoot.Length).TrimStart('\','/')
    $kind = if ($relative -match '^item[\\/]') { 'item' } else { 'block' }
    $text = [IO.File]::ReadAllText($_.FullName)
    $text = Rewrite-ModelText $text $kind
    $text = Convert-UnsupportedLoaderModel $text $relative $kind
    [IO.File]::WriteAllText($_.FullName, $text, [Text.UTF8Encoding]::new($false))
    if ($kind -eq 'item') { $itemModels += $relative.Substring(5).TrimStart('\','/') }
  }

  # 26.2 no longer discovers an item's render model from models/item alone. Each
  # legacy item model therefore needs the modern client item-definition wrapper in
  # assets/<namespace>/items/<id>.json. This also covers block items whose model
  # inherits a mod block model; the item model itself remains the source of truth.
  if ($itemModels.Count -gt 0) {
    $itemDefinitions = Join-Path $nsRoot 'items'
    New-Item -ItemType Directory -Force $itemDefinitions | Out-Null
    foreach ($itemModel in $itemModels) {
      $itemId = ($itemModel -replace '\.json$','') -replace '\\','/'
      $definitionPath = Join-Path $itemDefinitions ($itemId + '.json')
      Write-ItemDefinition $definitionPath ($Namespace + ':item/' + $itemId)
    }
  }
}

Write-Output ("normalized pack={0} namespace={1} resource_format={2}" -f $Pack, $Namespace, $resourceMajor)
$stamp = [ordered]@{
  schema = 1
  namespace = $Namespace
  resourceMajor = $resourceMajor
  normalizerSha256 = Get-NormalizerHash
  fingerprint = Get-PackFingerprint $Pack
}
[IO.File]::WriteAllText($stampPath, (($stamp | ConvertTo-Json -Depth 8) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
Write-Output ("stale-check stamp: " + $stampPath)
