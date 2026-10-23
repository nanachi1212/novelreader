[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$assetRoot = Join-Path $projectRoot 'assets\morandi'
$pngPath = Join-Path $assetRoot 'app-icon.png'
$icoPath = Join-Path $assetRoot 'app-icon.ico'
$sizes = @(256, 128, 64, 48, 32, 24, 16)

function New-NovelReaderBitmap([int] $Size) {
    $bitmap = [System.Drawing.Bitmap]::new($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)

    $scale = $Size / 100.0
    function S([double] $Value) { [single]($Value * $scale) }
    function New-Brush([string] $Color) { [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml($Color)) }

    $shadow = New-Brush '#536A57'
    $background = New-Brush '#8B9D8D'
    $page = New-Brush '#F9F5ED'
    $bookmark = New-Brush '#BA9897'
    $spine = [System.Drawing.Pen]::new([System.Drawing.ColorTranslator]::FromHtml('#D7C9AB'), (S 3))
    $text = [System.Drawing.Pen]::new([System.Drawing.ColorTranslator]::FromHtml('#7D6250'), (S 3))

    try {
        $shadowRect = [System.Drawing.RectangleF]::new((S 5), (S 7), (S 90), (S 90))
        $backgroundRect = [System.Drawing.RectangleF]::new((S 5), (S 4), (S 90), (S 90))
        $shadowColor = [System.Drawing.Color]::FromArgb(31, [System.Drawing.ColorTranslator]::FromHtml('#536A57'))
        $shadow.Color = $shadowColor
        $graphics.FillPath($shadow, (New-RoundedRectanglePath $shadowRect (S 24)))
        $graphics.FillPath($background, (New-RoundedRectanglePath $backgroundRect (S 24)))

        $book = [System.Drawing.Drawing2D.GraphicsPath]::new()
        $book.AddPolygon([System.Drawing.PointF[]]@(
            [System.Drawing.PointF]::new((S 23), (S 29)), [System.Drawing.PointF]::new((S 44), (S 29)),
            [System.Drawing.PointF]::new((S 50), (S 35)), [System.Drawing.PointF]::new((S 56), (S 29)),
            [System.Drawing.PointF]::new((S 77), (S 29)), [System.Drawing.PointF]::new((S 77), (S 72)),
            [System.Drawing.PointF]::new((S 57), (S 72)), [System.Drawing.PointF]::new((S 50), (S 78)),
            [System.Drawing.PointF]::new((S 42), (S 72)), [System.Drawing.PointF]::new((S 23), (S 72))
        ))
        $graphics.FillPath($page, $book)

        $bookmarkPath = [System.Drawing.Drawing2D.GraphicsPath]::new()
        $bookmarkPath.AddPolygon([System.Drawing.PointF[]]@(
            [System.Drawing.PointF]::new((S 61), (S 29)), [System.Drawing.PointF]::new((S 73), (S 29)),
            [System.Drawing.PointF]::new((S 73), (S 52)), [System.Drawing.PointF]::new((S 67), (S 47)),
            [System.Drawing.PointF]::new((S 61), (S 52))
        ))
        $graphics.FillPath($bookmark, $bookmarkPath)
        $graphics.DrawLine($spine, (S 50), (S 36), (S 50), (S 71))
        $graphics.DrawLine($text, (S 31), (S 42), (S 42), (S 42))
        $graphics.DrawLine($text, (S 31), (S 51), (S 42), (S 51))
        $graphics.DrawLine($text, (S 58), (S 60), (S 69), (S 60))
        return $bitmap
    }
    catch {
        $bitmap.Dispose()
        throw
    }
    finally {
        $text.Dispose(); $spine.Dispose(); $bookmark.Dispose(); $page.Dispose(); $background.Dispose(); $shadow.Dispose(); $graphics.Dispose()
    }
}

function New-RoundedRectanglePath([System.Drawing.RectangleF] $Rectangle, [single] $Radius) {
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $diameter = $Radius * 2
    $arc = [System.Drawing.RectangleF]::new($Rectangle.X, $Rectangle.Y, $diameter, $diameter)
    $path.AddArc($arc, 180, 90)
    $arc.X = $Rectangle.Right - $diameter; $path.AddArc($arc, 270, 90)
    $arc.Y = $Rectangle.Bottom - $diameter; $path.AddArc($arc, 0, 90)
    $arc.X = $Rectangle.Left; $path.AddArc($arc, 90, 90)
    $path.CloseFigure()
    return $path
}

$master = New-NovelReaderBitmap 1024
try { $master.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png) }
finally { $master.Dispose() }

$images = [System.Collections.Generic.List[byte[]]]::new()
foreach ($size in $sizes) {
    $bitmap = New-NovelReaderBitmap $size
    $stream = [System.IO.MemoryStream]::new()
    try {
        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        $images.Add($stream.ToArray())
    }
    finally { $stream.Dispose(); $bitmap.Dispose() }
}

$output = [System.IO.File]::Open($icoPath, [System.IO.FileMode]::Create)
$writer = [System.IO.BinaryWriter]::new($output)
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
    $offset = 6 + 16 * $sizes.Count
    for ($i = 0; $i -lt $sizes.Count; $i++) {
        $dimension = if ($sizes[$i] -eq 256) { 0 } else { $sizes[$i] }
        $writer.Write([byte]$dimension); $writer.Write([byte]$dimension); $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32); $writer.Write([uint32]$images[$i].Length); $writer.Write([uint32]$offset)
        $offset += $images[$i].Length
    }
    foreach ($bytes in $images) { $writer.Write([byte[]]$bytes) }
}
finally { $writer.Dispose(); $output.Dispose() }

Write-Output "Generated: $pngPath"
Write-Output "Generated: $icoPath"
