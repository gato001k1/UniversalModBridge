# Crop a region out of a full-screen shot and upscale it, so a 16x16 creative-inventory slot is
# actually inspectable. Nearest-neighbour, so no interpolation invents detail.
#   powershell -File umb-objbridge\dev\crop-zoom.ps1 -In shot.png -Out zoom.png -X 560 -Y 380 -W 160 -H 60 -Scale 6
param(
  [Parameter(Mandatory = $true)][string]$In,
  [Parameter(Mandatory = $true)][string]$Out,
  [Parameter(Mandatory = $true)][int]$X,
  [Parameter(Mandatory = $true)][int]$Y,
  [Parameter(Mandatory = $true)][int]$W,
  [Parameter(Mandatory = $true)][int]$H,
  [int]$Scale = 6
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile((Resolve-Path $In))
try {
  $rect = New-Object System.Drawing.Rectangle $X, $Y, $W, $H
  $dst = New-Object System.Drawing.Bitmap ($W * $Scale), ($H * $Scale)
  $g = [System.Drawing.Graphics]::FromImage($dst)
  try {
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, ($W * $Scale), ($H * $Scale)), $rect,
      [System.Drawing.GraphicsUnit]::Pixel)
  } finally { $g.Dispose() }
  $dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
  $dst.Dispose()
} finally { $src.Dispose() }
Write-Output ("ZOOM " + $Out)
