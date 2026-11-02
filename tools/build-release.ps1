# Builds the release APK signed with your own key, then verifies the signature.
#
# Run it in a PowerShell window:
#   powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
#
# Passwords are asked for without echoing, stay in this process only and are
# never written anywhere. Override the paths below with parameters if yours differ.

param(
    [string]$Keystore = 'E:\tools\release-key.jks',
    [string]$KeyAlias = '',
    [string]$Jdk = 'E:\tools\jdk-21.0.13+9',
    [string]$Sdk = 'E:\Android\Sdk',
    [string]$GradleHome = 'E:\gradle-home'
)

$ErrorActionPreference = 'Stop'

function Read-Secret([string]$Prompt) {
    $secure = Read-Host -AsSecureString $Prompt
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

if (-not (Test-Path $Keystore)) { throw "Keystore not found: $Keystore" }
if (-not (Test-Path "$Jdk\bin\java.exe")) { throw "JDK not found: $Jdk" }
if (-not (Test-Path "$Sdk\build-tools")) { throw "Android SDK not found: $Sdk" }

$gradle = Get-ChildItem "$GradleHome\wrapper\dists\gradle-9.4.1-bin\*\gradle-9.4.1\bin\gradle.bat" | Select-Object -First 1
if (-not $gradle) { throw "Gradle 9.4.1 not found under $GradleHome" }
$apksigner = Get-ChildItem "$Sdk\build-tools\*\apksigner.bat" | Sort-Object FullName | Select-Object -Last 1
if (-not $apksigner) { throw "apksigner not found under $Sdk\build-tools" }

$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:GRADLE_USER_HOME = $GradleHome

try {
    $env:PD2_KEYSTORE_PATH = $Keystore
    $env:PD2_KEYSTORE_PASSWORD = Read-Secret "Keystore password for $Keystore"

    if (-not $KeyAlias) {
        $listing = & "$Jdk\bin\keytool.exe" -list -keystore $Keystore -storepass:env PD2_KEYSTORE_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw "keytool could not open the keystore: wrong password?" }
        $aliases = @($listing | Where-Object { $_ -match 'PrivateKeyEntry' } | ForEach-Object { ($_ -split ',')[0].Trim() })
        if ($aliases.Count -eq 1) {
            $KeyAlias = $aliases[0]
            Write-Host "Key alias: $KeyAlias"
        } else {
            Write-Host "Aliases in the keystore: $($aliases -join ', ')"
            $KeyAlias = Read-Host "Key alias to use"
        }
    }
    $env:PD2_KEY_ALIAS = $KeyAlias

    $keyPassword = Read-Secret "Key password (press Enter to reuse the keystore password)"
    if ([string]::IsNullOrEmpty($keyPassword)) { $keyPassword = $env:PD2_KEYSTORE_PASSWORD }
    $env:PD2_KEY_PASSWORD = $keyPassword
    $keyPassword = $null

    Push-Location (Split-Path $PSScriptRoot -Parent)
    try {
        & $gradle.FullName --no-daemon --offline :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { throw "The build failed (exit $LASTEXITCODE)" }
        $apk = (Resolve-Path 'app\build\outputs\apk\release\app-release.apk').Path
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:PD2_KEYSTORE_PASSWORD, Env:PD2_KEY_PASSWORD, Env:PD2_KEYSTORE_PATH, Env:PD2_KEY_ALIAS -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Verifying the signature..." -ForegroundColor Cyan
& $apksigner.FullName verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "The APK signature did not verify" }

Write-Host ""
Write-Host "Signed APK: $apk" -ForegroundColor Green
Write-Host "SHA-256: $((Get-FileHash $apk -Algorithm SHA256).Hash)"
