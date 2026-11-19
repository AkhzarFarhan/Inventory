# Runs the app's JVM unit tests (Robolectric included).
# Usage: powershell -ExecutionPolicy Bypass -File scripts/test.ps1 [--tests "fully.qualified.TestClass"]
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
# Java's Windows selector uses a Unix-domain socket in the temp dir; a short, writable dir avoids
# "Unable to establish loopback connection".
$tmp = Join-Path $root "build\jtmp"
New-Item -ItemType Directory -Force $tmp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$tmp"
Push-Location $root
try {
    & .\gradlew.bat ":app:testDebugUnitTest" --console=plain @GradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
