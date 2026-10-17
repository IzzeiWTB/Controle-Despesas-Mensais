$ErrorActionPreference = 'Stop'
$mobileRoot = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $env:LOCALAPPDATA 'ClarezaTools'
# Keep the backend's Java 25 separate. Environment changes apply only to this process.
if ($env:CLAREZA_JAVA_HOME) {
    $env:JAVA_HOME = $env:CLAREZA_JAVA_HOME
} else {
    $jdk = Get-ChildItem $toolRoot -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/java.exe")) {
    throw 'Instale JDK 21 e configure CLAREZA_JAVA_HOME. Consulte docs/android.md.'
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path "$env:ANDROID_HOME/platforms/android-35/android.jar")) {
    throw 'SDK Android 35 ausente. Configure ANDROID_HOME. Consulte docs/android.md.'
}
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
Push-Location (Join-Path $mobileRoot 'android')
try {
    & ./gradlew.bat --no-daemon assembleDebug testDebugUnitTest lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Compilacao ou verificacao Android falhou.' }
    $output = Join-Path $mobileRoot 'artifacts'
    New-Item -ItemType Directory -Force $output | Out-Null
    $apk = Join-Path $output 'Clareza-1.0-debug.apk'
    Copy-Item -LiteralPath 'app/build/outputs/apk/debug/app-debug.apk' -Destination $apk
    $signer = Get-ChildItem "$env:ANDROID_HOME/build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
    & "$($signer.FullName)/apksigner.bat" verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'Assinatura APK invalida.' }
    Get-FileHash $apk -Algorithm SHA256
    Write-Output "APK de teste: $apk"
} finally { Pop-Location }
