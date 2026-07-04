#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
DOCS="$DIR/../docs"
PRIV="$DIR/private_key.pem"
JSON="$DOCS/issued.json"
PACKAGE="com.example.fqdownloader"

[ -f "$PRIV" ] || { echo "请先生成密钥对: ./gen_keys.sh"; exit 1; }
[ -f "$JSON" ] || { echo "请先生成密钥对: ./gen_keys.sh"; exit 1; }

# 授权者名称: 参数 1 或交互输入
USERNAME="${1:-}"
if [ -z "$USERNAME" ]; then
    read -p "授权者名称: " USERNAME
fi
[ -z "$USERNAME" ] && { echo "授权者名称不能为空"; exit 1; }

read -p "过期日期 (yyyy-MM-dd, 默认 2030-12-31): " EXPIRY
EXPIRY="${EXPIRY:-2030-12-31}"

read -p "警告级别 (0-4, 默认 0): " WARN_LEVEL
WARN_LEVEL="${WARN_LEVEL:-0}"

WARN_TEXT=""
if [ "$WARN_LEVEL" != "0" ]; then
    read -p "警告文字 (留空使用默认): " WARN_TEXT
fi

# 读取当前最新序号
LATEST=$(python3 -c "import json; print(json.load(open('$JSON'))['latest'])" 2>/dev/null || echo "0")
NEXT=$((10#$LATEST + 1))
ID=$(printf "%016d" $NEXT)

# 签名激活码
MSG="$PACKAGE||$ID"
SIG=$(echo -n "$MSG" | openssl dgst -sha256 -sign "$PRIV" | openssl base64 -A)

# 更新 issued.json（Python heredoc 避免引号转义）
python3 << EOF
import json
d = json.load(open('$JSON'))
d['codes']['$ID'] = {
    'user': '$USERNAME',
    'expiry': '$EXPIRY',
    'warn_level': $WARN_LEVEL,
    'warn_text': '$WARN_TEXT'
}
d['latest'] = $NEXT
json.dump(d, open('$JSON', 'w'), indent=2, ensure_ascii=False)
EOF

ACTIVATION_CODE="${ID}:${SIG}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  授权者:      $USERNAME"
echo "  ID:          $ID"
echo "  过期日期:    $EXPIRY"
echo "  警告级别:    $WARN_LEVEL"
echo "  激活码:      $ACTIVATION_CODE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "$ACTIVATION_CODE" > "$DIR/activation_code.txt"
echo "已保存到 $DIR/activation_code.txt"

# 生成 QR 码
if command -v qrencode &>/dev/null; then
    qrencode -o "$DIR/activation_qr_$ID.png" "$ACTIVATION_CODE"
    echo "QR 码: $DIR/activation_qr_$ID.png"
else
    echo "qrencode 未安装，跳过 QR 码生成"
fi

# 重新签名 issued.json
"$DIR/gen_issued_sig.sh"

echo ""
echo "请提交 docs/ 到 git:"
echo "  git add docs/ && git commit -m 'add activation ${ID} for ${USERNAME}' && git push"
