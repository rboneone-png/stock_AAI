#!/usr/bin/env bash
# 把簽章金鑰寫入 GitHub Actions Secrets（需先安裝並登入 gh：gh auth login）。
# 用法：./scripts/set-signing-secrets.sh <keystore.jks> <keystore.properties> [owner/repo]
#   keystore.properties 內容：storeFile=… storePassword=… keyAlias=… keyPassword=…
set -euo pipefail
KS="${1:?用法：$0 <keystore.jks> <keystore.properties> [owner/repo]}"
PROPS="${2:?缺少 keystore.properties}"
REPO="${3:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
get() { grep "^$1=" "$PROPS" | cut -d= -f2- | tr -d '\r\n'; }

base64 -w0 "$KS" 2>/dev/null | gh secret set KEYSTORE_BASE64 --repo "$REPO" \
  || base64 < "$KS" | tr -d '\n' | gh secret set KEYSTORE_BASE64 --repo "$REPO"   # macOS 的 base64 沒有 -w
printf '%s' "$(get storePassword)" | gh secret set KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$(get keyAlias)"      | gh secret set KEY_ALIAS         --repo "$REPO"
printf '%s' "$(get keyPassword)"   | gh secret set KEY_PASSWORD      --repo "$REPO"
echo "完成。已設定的 secrets："; gh secret list --repo "$REPO"
