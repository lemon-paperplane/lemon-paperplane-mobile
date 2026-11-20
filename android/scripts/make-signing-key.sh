#!/bin/bash
# 生成柠檬纸飞机安卓版的发布签名密钥，存进 GitHub 仓库 Secrets，并在本机留一份备份。只需要做一次。
#
# 用法：android/scripts/make-signing-key.sh [备份目录]
#   备份目录默认 ~/Documents/柠檬纸飞机-安卓签名密钥（里面有密钥文件和密码，务必另外再备份一份，丢了以后发布的新版本无法覆盖升级旧版本）
#
# 存进仓库 Secrets（云端构建 .github/workflows/android.yml 用）：
#   ANDROID_KEYSTORE_BASE64   密钥文件（PKCS12）的 base64
#   ANDROID_KEYSTORE_PASSWORD 密钥库密码（PKCS12 的密钥密码与它相同）
#   ANDROID_KEY_ALIAS         密钥别名
# 需要：JDK 的 keytool、openssl、已登录的 gh（对仓库有管理权限）。密码不会打印到屏幕上。
set -euo pipefail

REPO="${PAPERPLANE_MOBILE_REPO:-lemon-paperplane/lemon-paperplane-mobile}"
OUT="${1:-$HOME/Documents/柠檬纸飞机-安卓签名密钥}"
ALIAS="lemon-paperplane"
KS="$OUT/lemon-paperplane-release.p12"

die() { echo "错误：$*" >&2; exit 1; }

KEYTOOL="$(command -v keytool || true)"
if [ -z "$KEYTOOL" ] && [ -x /usr/libexec/java_home ]; then
  KEYTOOL="$(/usr/libexec/java_home 2>/dev/null)/bin/keytool"
fi
[ -x "$KEYTOOL" ] || die "找不到 keytool（需要 JDK）"
command -v openssl >/dev/null || die "找不到 openssl"
command -v gh >/dev/null || die "找不到 gh（GitHub 命令行）"
gh auth status >/dev/null 2>&1 || die "gh 还没登录：先运行 gh auth login"
[ -e "$KS" ] && die "$KS 已经存在：签名密钥只生成一次，不要覆盖（丢了旧密钥，已安装的版本就无法覆盖升级）"

mkdir -p "$OUT"
chmod 700 "$OUT"
umask 077
PASS="$(openssl rand -base64 33 | tr -dc 'A-Za-z0-9' | head -c 32)"
[ "${#PASS}" -ge 24 ] || die "生成随机密码失败"
export PASS  # keytool 用 -storepass:env 从环境变量读，密码不出现在命令行参数里

"$KEYTOOL" -genkeypair -storetype PKCS12 -keystore "$KS" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Lemon Paperplane, O=Lemon Paperplane" \
  -storepass:env PASS -keypass:env PASS >/dev/null 2>&1 \
  < /dev/null || die "keytool 生成密钥失败"

FP="$("$KEYTOOL" -list -v -keystore "$KS" -storetype PKCS12 -storepass:env PASS -alias "$ALIAS" 2>/dev/null | awk -F': ' '/SHA256:/{print $2; exit}')"

cat > "$OUT/说明.txt" <<TXT
柠檬纸飞机安卓版发布签名密钥（$(date '+%Y-%m-%d') 生成）

密钥文件：lemon-paperplane-release.p12（PKCS12）
密钥别名：$ALIAS
密钥库密码（密钥密码相同）：$PASS
证书 SHA-256 指纹：$FP

用途：GitHub 仓库 $REPO 的云端构建用它给 APK 签名（仓库 Secrets：ANDROID_KEYSTORE_BASE64、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS）。
只有用同一把密钥签名的新版本才能在手机上直接覆盖升级。请把这个文件夹另外备份到安全的地方（如密码管理器、加密的移动硬盘），不要提交到任何 Git 仓库。
TXT
chmod 600 "$OUT/说明.txt" "$KS"

base64 < "$KS" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO" >/dev/null
printf '%s' "$PASS" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO" >/dev/null
printf '%s' "$ALIAS" | gh secret set ANDROID_KEY_ALIAS --repo "$REPO" >/dev/null
unset PASS

echo "已生成签名密钥并存进 $REPO 的 Secrets。"
echo "  备份：$OUT"
echo "  证书 SHA-256：$FP"
