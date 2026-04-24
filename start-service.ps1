param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

Set-Location $PSScriptRoot

$env:FLYWAY_ENABLED = 'true'

$mavenWrapper = Join-Path $PSScriptRoot '..\tools\apache-maven-3.9.9\bin\mvn.cmd'
if (Test-Path $mavenWrapper) {
    $mvnCommand = $mavenWrapper
} else {
    $mvnCommand = 'mvn'
}

$jarPath = Join-Path $PSScriptRoot 'target\datacollect-1.0.0.jar'

if (-not $SkipBuild -or -not (Test-Path $jarPath)) {
    & $mvnCommand -DskipTests package
}

if ($env:JAVA_HOME) {
    $javaPath = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path $javaPath)) {
        $javaPath = $null
    }
} else {
    $javaPath = $null
}

if (-not $javaPath) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaPath = $javaCommand.Source
    }
}

if (-not $javaPath) {
    throw 'Java runtime not found. Please set JAVA_HOME or ensure java.exe is available in PATH.'
}

& $javaPath -jar $jarPath