# ============================================================================
# NekoType —— Windows 一键签名构建脚本
# 用法:  powershell -ExecutionPolicy Bypass -File sign_and_build.ps1
# 产物:  app\build\outputs\apk\release\app-release.apk （已签名，可直接安装/发布）
# ============================================================================
$ErrorActionPreference = "Stop"

Write-Host "==> NekoType 签名构建脚本 (Windows)" -ForegroundColor Cyan
$root = $PSScriptRoot
Set-Location $root

# ---------- 1. 环境检测 ----------
Write-Host "[1/5] 检测构建环境..." -ForegroundColor Yellow

# JDK 17+
$javaBin = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $javaBin = "$env:JAVA_HOME\bin\java.exe"
} else {
    $cmdJava = Get-Command java -ErrorAction SilentlyContinue
    if ($cmdJava) { $javaBin = $cmdJava.Source }
}
if (-not $javaBin) {
    Write-Host "  [ERROR] 未找到 JDK，请安装 JDK 17+ 并设置 JAVA_HOME" -ForegroundColor Red
    exit 1
}
$verOut = & $javaBin -version 2>&1 | Out-String
if ($verOut -notmatch 'version "([0-9]+)') {
    Write-Host "  [ERROR] 无法解析 JDK 版本: $verOut" -ForegroundColor Red
    exit 1
}
$major = [int]$Matches[1]
if ($major -lt 17) {
    Write-Host "  [ERROR] 需要 JDK 17+，当前: $($verOut.Trim())" -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] JDK $major 就绪: $javaBin"

# Android SDK
if (-not $env:ANDROID_HOME -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk")) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    Write-Host "  [ERROR] 未找到 Android SDK，请安装 Android Studio 或设置 ANDROID_HOME" -ForegroundColor Red
    exit 1
}
Write-Host "  [OK] ANDROID_HOME=$env:ANDROID_HOME"

# local.properties
if (-not (Test-Path "$root\local.properties")) {
    $sdkEscaped = $env:ANDROID_HOME -replace '\\', '\\'
    Set-Content -Path "$root\local.properties" -Value "sdk.dir=$sdkEscaped" -Encoding UTF8
    Write-Host "  [OK] 已生成 local.properties"
}

# ---------- 2. 生成签名密钥库 ----------
Write-Host "[2/5] 检查/生成签名密钥库..." -ForegroundColor Yellow
$ks = "$root\nekotype-release.keystore"
$keytool = Join-Path (Split-Path $javaBin) "keytool.exe"
if (-not (Test-Path $ks)) {
    Write-Host "  生成 RSA-2048 密钥库 (有效期 10000 天)..."
    & $keytool -genkeypair -v -keystore $ks -alias nekotype -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass nekotype2026 -keypass nekotype2026 `
        -dname "CN=NekoType, OU=NekoType, O=NekoType, L=Beijing, ST=Beijing, C=CN" | Out-Null
    Write-Host "  [OK] 密钥库已生成: $ks"
} else {
    Write-Host "  [OK] 密钥库已存在: $ks"
}
# keystore.properties（已被 .gitignore 忽略）
@"
storeFile=nekotype-release.keystore
storePassword=nekotype2026
keyAlias=nekotype
keyPassword=nekotype2026
"@ | Set-Content -Path "$root\keystore.properties" -Encoding UTF8
Write-Host "  [OK] keystore.properties 已写入"

# ---------- 3. 确保 Gradle Wrapper 可用 ----------
Write-Host "[3/5] 准备 Gradle Wrapper..." -ForegroundColor Yellow
if (-not (Test-Path "$root\gradlew.bat")) {
    Write-Host "  未找到 gradlew.bat，尝试用已安装的 gradle 生成 wrapper..."
    $gradleCmd = Get-Command gradle -ErrorAction SilentlyContinue
    if (-not $gradleCmd) {
        Write-Host "  [ERROR] 本机无 gradle，且仓库缺少 wrapper。" -ForegroundColor Red
        Write-Host "          请先下载 https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip" -ForegroundColor Red
        Write-Host "          解压后把 bin 目录加入 PATH，再重新运行本脚本。" -ForegroundColor Red
        exit 1
    }
    & $gradleCmd.Source wrapper --gradle-version 8.7
}
Write-Host "  [OK] Wrapper 就绪"

# ---------- 4. 构建已签名 APK ----------
Write-Host "[4/5] 构建已签名 APK (gradlew.bat assembleRelease)..." -ForegroundColor Yellow
& "$root\gradlew.bat" assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) {
    Write-Host "  [ERROR] 构建失败，请检查上方日志" -ForegroundColor Red
    exit 1
}

# ---------- 5. 校验产物 ----------
Write-Host "[5/5] 校验产物..." -ForegroundColor Yellow
$apk = "$root\app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) {
    Write-Host "  [ERROR] 未找到 $apk" -ForegroundColor Red
    exit 1
}
$sizeKB = [math]::Round((Get-Item $apk).Length / 1KB)
Write-Host "  [SUCCESS] 已签名 APK: $apk (${sizeKB} KB)" -ForegroundColor Green

# apksigner 校验
$apksigner = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Recurse -Filter apksigner.bat |
    Sort-Object FullName -Descending | Select-Object -First 1
if ($apksigner) {
    $verify = & $apksigner.FullName verify --print-certs $apk 2>&1 | Out-String
    Write-Host "  [OK] apksigner 校验:" -ForegroundColor Green
    Write-Host ($verify | Select-Object -First 4)
} else {
    Write-Host "  [WARN] 未找到 apksigner，跳过签名校验" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "==> 完成！安装到设备:  adb install -r `"$apk`"" -ForegroundColor Cyan
