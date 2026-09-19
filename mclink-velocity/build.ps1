# Build mclink-velocity.jar against the bundled velocity jar.
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1 [-JdkRoot <jdk-home>] [-VelocityJarPath <velocity.jar>]
#
# Requires JDK 25+: the velocity jar bundles an annotation processor whose
# class-file version (69.0) older javac cannot read. Output bytecode targets
# --release 21, so runtime stays Java 21 compatible.
#
# JDK resolution order (first one with javac >= 25 wins):
#   1. -JdkRoot parameter
#   2. JAVA_HOME environment variable
#   3. javac on PATH
# Velocity jar resolution order:
#   1. -VelocityJarPath parameter
#   2. velocity-*.jar two directories up (works in the velocity plugins-src layout)
# To pin machine-specific paths, keep them out of the repo (see .gitignore):
# create build.local.ps1 next to this script that calls:
#   & (Join-Path $PSScriptRoot "build.ps1") -JdkRoot "C:\path\to\jdk-25" -VelocityJarPath "C:\path\to\velocity.jar"
# NOTE: keep this file UTF-8 with BOM (PS 5.1 mis-decodes BOM-less UTF-8 as
# ANSI and the Chinese error message below breaks parsing).

param(
    [string]$JdkRoot = "",
    [string]$VelocityJarPath = ""
)

$ErrorActionPreference = "Stop"

$Root = $PSScriptRoot
if (-not $Root) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $Root) { $Root = (Get-Location).Path }

$OutDir = Join-Path $Root "out"
$SrcDir = Join-Path $Root "src"
$JarName = "mclink-velocity.jar"

if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

function Get-JavacMajor([string]$JavacPath) {
    try {
        $out = & $JavacPath -version 2>&1
        if ($out -match '(\d+)') { return [int]$Matches[1] }
    } catch {}
    return -1
}

# Return the first candidate whose javac is >= 25, else $null
function Resolve-Jdk {
    param([string]$Override)
    $candidates = @()
    if ($Override) {
        $candidates += ,@{ Javac = (Join-Path $Override "bin\javac.exe"); Label = "$Override (-JdkRoot)" }
    }
    if ($env:JAVA_HOME) {
        $candidates += ,@{ Javac = (Join-Path $env:JAVA_HOME "bin\javac.exe"); Label = "$env:JAVA_HOME (JAVA_HOME)" }
    }
    $pathJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $candidates += ,@{ Javac = $pathJavac.Source; Label = "PATH" }
    }
    foreach ($c in $candidates) {
        if (-not ($c.Javac -and (Test-Path $c.Javac))) { continue }
        $ver = Get-JavacMajor $c.Javac
        if ($ver -ge 25) {
            $bin = Split-Path $c.Javac
            $jarCmd = Join-Path $bin "jar.exe"
            if (-not (Test-Path $jarCmd)) {
                $onPath = Get-Command jar -ErrorAction SilentlyContinue
                if (-not $onPath) { Write-Host "skip $($c.Label): jar.exe not found next to javac"; continue }
                $jarCmd = $onPath.Source
            }
            return @{ Javac = $c.Javac; Jar = $jarCmd; Label = $c.Label; Version = $ver }
        }
        Write-Host "skip $($c.Label): javac $ver (< 25)"
    }
    return $null
}

function Resolve-VelocityJar {
    param([string]$Override)
    if ($Override) {
        if (Test-Path $Override) { return $Override }
        Write-Host "skip -VelocityJarPath: not found: $Override"
    }
    $up2 = Join-Path $Root "..\.."
    $hit = Get-ChildItem -Path $up2 -Filter "velocity-*.jar" -File -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

$Jdk = Resolve-Jdk $JdkRoot
if (-not $Jdk) {
    throw "未找到 JDK 25+。请用 -JdkRoot <jdk主目录> 指定，或设置 JAVA_HOME 指向 JDK 25+。"
}
Write-Host ("Using JDK {0} ({1})" -f $Jdk.Version, $Jdk.Label)

$VelocityJar = Resolve-VelocityJar $VelocityJarPath
if (-not $VelocityJar) {
    throw "未找到 velocity 依赖 jar。请用 -VelocityJarPath <velocity-*.jar> 指定。"
}
Write-Host "Using velocity jar: $VelocityJar"

$sources = Get-ChildItem -Path $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }

Write-Host "Compiling $($sources.Count) java files..."
& $Jdk.Javac -encoding UTF-8 --release 21 -cp $VelocityJar -d $OutDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "Packaging $JarName..."
$jarPath = Join-Path $Root $JarName
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }

Push-Location $OutDir
& $Jdk.Jar cf $jarPath .
Pop-Location

Push-Location $Root
& $Jdk.Jar uf $jarPath velocity-plugin.json
Pop-Location

Write-Host "Done: $jarPath"
