#!/usr/bin/env bash
# NekoType —— 一键生成签名密钥 + 构建已签名 APK
# 用法: chmod +x sign_and_build.sh && ./sign_and_build.sh
# 产物: app/build/outputs/apk/release/app-release.apk (已签名, 通常 3~8 MB)
set -e

cd "$(dirname "$0")"
echo "==> NekoType 签名构建脚本"

# 1. 环境检测
echo "[1/5] 检测构建环境..."
JAVA_OK=0
if command -v java >/dev/null 2>&1; then
  JV=$(java -version 2>&1 | grep -oP 'version "(1\.)?\K[0-9]+' | head -1)
  if [ "$JV" -ge 17 ] 2>/dev/null; then JAVA_OK=1; fi
fi
if [ $JAVA_OK -ne 1 ]; then
  echo "  [ERROR] 需要 JDK 17 或更高 (AGP 8.5+ 要求). 当前 java 版本: $(java -version 2>&1 | head -1)"
  echo "          安装: sudo apt install openjdk-17-jdk  或  用 Android Studio 自带 JDK"
  exit 1
fi
echo "  [OK] JDK >= 17 就绪"

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
  # 尝试常见路径
  for p in "$HOME/Android/Sdk" "/opt/android-sdk" "$HOME/Library/Android/sdk"; do
    if [ -d "$p" ]; then export ANDROID_HOME="$p"; break; fi
  done
fi
if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
  echo "  [ERROR] 未找到 Android SDK. 请安装 Android Studio 或设置 ANDROID_HOME 环境变量"
  exit 1
fi
echo "  [OK] ANDROID_HOME=$ANDROID_HOME"

# 2. 生成签名密钥库 (若不存在)
echo "[2/5] 检查/生成签名密钥库..."
KS="nekotype-release.keystore"

# 签名密码来源（按优先级）：keystore.properties → 环境变量 → 交互输入。
# 注意：本仓库是公开的，切勿在脚本里硬编码密码。
get_prop() {
  [ -f keystore.properties ] || return 1
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\(.*[^[:space:]]\)[[:space:]]*$/\1/p" keystore.properties | head -n 1
}
STORE_PASS="$(get_prop storePassword || true)"
KEY_PASS="$(get_prop keyPassword || true)"
[ -z "$STORE_PASS" ] && STORE_PASS="${NEKOTYPE_STORE_PASSWORD:-}"
[ -z "$KEY_PASS" ] && KEY_PASS="${NEKOTYPE_KEY_PASSWORD:-}"
[ -z "$KEY_PASS" ] && KEY_PASS="$STORE_PASS"
if [ -z "$STORE_PASS" ]; then
  echo "  未找到签名密码，请输入（或先设置环境变量 NEKOTYPE_STORE_PASSWORD）"
  printf "  密钥库密码 (storePassword): "
  stty -echo 2>/dev/null || true
  read -r STORE_PASS
  stty echo 2>/dev/null || true
  echo ""
  KEY_PASS="$STORE_PASS"
fi
if [ -z "$STORE_PASS" ]; then
  echo "  [ERROR] 密码为空，无法继续"
  exit 1
fi

if [ ! -f "$KS" ]; then
  echo "  生成 RSA-2048 密钥库 (有效期 10000 天)..."
  keytool -genkeypair -v -keystore "$KS" -alias nekotype -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=NekoType, OU=NekoType, O=NekoType, L=Beijing, S=Beijing, C=CN"
fi
# 写入 keystore.properties (Git 已忽略，仅保存在本机)
cat > keystore.properties <<EOF
storeFile=$KS
storePassword=$STORE_PASS
keyAlias=nekotype
keyPassword=$KEY_PASS
EOF
chmod 600 keystore.properties 2>/dev/null || true
echo "  [OK] 密钥库就绪 ($KS)，keystore.properties 已写入（本机，已被 git 忽略）"

# 3. 确保 Gradle Wrapper 可用
echo "[3/5] 准备 Gradle Wrapper..."
chmod +x gradlew 2>/dev/null || true
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "  [WARN] 未找到 gradle/wrapper/gradle-wrapper.jar (Gradle 自举必需)."
  echo "         请在本机三选一解决:"
  echo "          1) 用 Android Studio 打开本项目, 首次 Sync 会自动生成 wrapper;"
  echo "          2) 本机已装 Gradle>=8.7, 运行 'gradle wrapper --gradle-version 8.7' 生成;"
  echo "          3) 手动下载 https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip 解压,"
  echo "             将其内 gradle/wrapper/gradle-wrapper.jar 拷到本项目同名目录."
  echo "         解决后重新运行本脚本."
  exit 1
fi
chmod +x gradlew 2>/dev/null || true
echo "  [OK] Wrapper 就绪"

# 4. 构建已签名 APK
echo "[4/5] 构建已签名 APK (./gradlew assembleRelease)..."
./gradlew assembleRelease --no-daemon --refresh-dependencies || {
  echo "  [ERROR] 构建失败. 常见原因: 首次需联网下载 Android SDK 平台/Build-Tools 及依赖."
  echo "          请在 Android Studio 中先打开本项目完成一次 'Sync Project', 再重跑本脚本."
  exit 1
}

# 5. 校验产物
APK="app/build/outputs/apk/release/app-release.apk"
echo "[5/5] 校验产物..."
if [ -f "$APK" ]; then
  SIZE=$(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK")
  SIZE_KB=$((SIZE/1024))
  echo "  [SUCCESS] 已签名 APK: $APK  (${SIZE_KB} KB)"
  if [ "$SIZE_KB" -lt 500 ]; then
    echo "  [WARN] APK 仅 ${SIZE_KB}KB, 疑似未打入 AndroidX 依赖, 请检查构建日志."
  fi
else
  echo "  [ERROR] 未找到 $APK, 构建未产出 APK."
  exit 1
fi
echo ""
echo "==> 完成. 用 'adb install -r $APK' 安装到设备."
