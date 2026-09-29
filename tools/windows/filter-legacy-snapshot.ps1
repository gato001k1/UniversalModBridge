#requires -Version 5.1
param(
  [Parameter(Mandatory=$true)][string]$InputSnapshot,
  [Parameter(Mandatory=$true)][string]$OutputSnapshot,
  [Parameter(Mandatory=$true)][string]$Namespace,
  [string[]]$ExcludeId = @()
)
$ErrorActionPreference = 'Stop'

$data = Get-Content -Raw -LiteralPath $InputSnapshot | ConvertFrom-Json
$prefix = $Namespace + ':'
$owns = { param($id) $null -ne $id -and $id.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) }

$blocks = @($data.blocks | Where-Object { (& $owns $_.id) -and ($ExcludeId -notcontains $_.id) })
$items = @($data.items | Where-Object { (& $owns $_.id) -and ($ExcludeId -notcontains $_.id) })
$teClasses = @($blocks | Where-Object { $_.tileEntityClass } | ForEach-Object { $_.tileEntityClass } | Sort-Object -Unique)
$tileEntities = @($data.tileEntities | Where-Object { $teClasses -contains $_.className })
$entities = @($data.entities | Where-Object {
  (& $owns $_.modid) -or (& $owns $_.id)
})
$fluids = @($data.fluids | Where-Object {
  (& $owns $_.blockId) -or (& $owns $_.iconName)
})

$data.blocks = $blocks
$data.items = $items
$data.tileEntities = $tileEntities
$data.entities = $entities
$data.fluids = $fluids
$data.counts = [ordered]@{
  blocksTotal = $blocks.Count
  blocksHbm = $blocks.Count
  itemsTotal = $items.Count
  itemsHbm = $items.Count
  tabsTotal = @($data.creativeTabs).Count
  tabsHbm = 0
  iconRowsBlocks = @($blocks | ForEach-Object { @($_.icons).Count } | Measure-Object -Sum).Sum
  fluids = $fluids.Count
  oreDictHbm = 0
}

$parent = Split-Path -Parent $OutputSnapshot
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$data | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $OutputSnapshot -Encoding UTF8
Write-Output ([string]::Format("filtered namespace={0} blocks={1} items={2} tileEntities={3} entities={4} fluids={5}",
  $Namespace, $blocks.Count, $items.Count, $tileEntities.Count, $entities.Count, $fluids.Count))
