# Build mclink-velocity.jar against the bundled velocity jar.
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = "Stop"

$Root = $PSScriptRoot
if (-not $Root) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $Root) { $Root = (Get-Location).Path }

$VelocityJar = Join-Path $Root "..\..\velocity-4.1.1-24.jar"
$OutDir = Join-Path $Root "out"
$SrcDir = Join-Path $Root "src"
$JarName = "mclink-velocity.jar"

if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

$Javac = "F:\Java\jdk-25.0.2\bin\javac.exe"
$Jar = "F:\Java\jdk-25.0.2\bin\jar.exe"
if (-not (Test-Path $Javac)) { $Javac = "javac" }
if (-not (Test-Path $Jar)) { $Jar = "jar" }

$sources = Get-ChildItem -Path $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }

Write-Host "Compiling $($sources.Count) java files..."
& $Javac -encoding UTF-8 --release 21 -cp $VelocityJar -d $OutDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "Packaging $JarName..."
$jarPath = Join-Path $Root $JarName
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }

Push-Location $OutDir
& $Jar cf $jarPath .
Pop-Location

Push-Location $Root
& $Jar uf $jarPath velocity-plugin.json
Pop-Location

Write-Host "Done: $jarPath"
