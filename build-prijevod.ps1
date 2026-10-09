$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$taskJdk = Get-ChildItem -LiteralPath 'C:/smarttube-tools/jdk11' -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
    Select-Object -First 1
if ($taskJdk) { $env:JAVA_HOME = $taskJdk.FullName }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) {
    throw 'Set JAVA_HOME to JDK 11 and set the Android SDK path in local.properties.'
}
if (-not (Test-Path -LiteralPath 'keystore.properties')) {
    throw 'Missing keystore.properties. For a debug APK use .\gradlew.bat :smarttubetv:assembleStfdroidDebug.'
}
if (-not (Test-Path -LiteralPath (Join-Path $env:USERPROFILE '.smarttube-prijevod/openai.properties'))) {
    throw 'Missing private OpenAI configuration: USERPROFILE/.smarttube-prijevod/openai.properties.'
}

& .\gradlew.bat :common:testStfdroidDebugUnitTest --tests '*BosnianSubtitle*Test' :smarttubetv:assembleStfdroidRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed (exit $LASTEXITCODE)." }
$apk = Get-ChildItem -LiteralPath 'smarttubetv/build/outputs/apk/stfdroid/release' -Filter '*universal.apk' |
    Select-Object -First 1
if (-not $apk) { throw 'Universal APK was not found.' }
New-Item -ItemType Directory -Path 'releases' -Force | Out-Null
Copy-Item -LiteralPath $apk.FullName -Destination 'releases/SmartTube-Nas-Prijevod.apk' -Force
Get-FileHash -LiteralPath 'releases/SmartTube-Nas-Prijevod.apk' -Algorithm SHA256
