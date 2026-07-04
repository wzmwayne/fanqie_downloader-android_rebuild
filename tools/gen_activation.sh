#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
DOCS="$DIR/../docs"
PRIV="$DIR/private_key.pem"
JSON="$DOCS/issued.json"
PACKAGE="com.example.fqdownloader"

[ -f "$PRIV" ] || { echo "请先生成密钥对: ./gen_keys.sh"; exit 1; }
[ -f "$JSON" ] || { echo "请先生成密钥对: ./gen_keys.sh"; exit 1; }

# 获取授权者名称：参数 1 或交互输入
USERNAME="${1:-}"
if [ -z "$USERNAME" ]; then
    read -p "授权者名称: " USERNAME
fi
[ -z "$USERNAME" ] && { echo "授权者名称不能为空"; exit 1; }

# 读取当前最新序号
LATEST=$(python3 -c "import json; print(json.load(open('$JSON'))['latest'])" 2>/dev/null || echo "0")
NEXT=$((10#$LATEST + 1))
ID=$(printf "%016d" $NEXT)

# 签名
MSG="$PACKAGE||$ID"
SIG=$(echo -n "$MSG" | openssl dgst -sha256 -sign "$PRIV" | openssl base64 -A)

# 更新 issued.json
python3 -c "
import json
d = json.load(open('$JSON'))
d['codes']['$ID'] = '$USERNAME'
d['latest'] = $NEXT
json.dump(d, open('$JSON', 'w'), indent=2)
"

ACTIVATION_CODE="${ID}:${SIG}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  授权者:      $USERNAME"
echo "  ID:          $ID"
echo "  激活码:      $ACTIVATION_CODE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "$ACTIVATION_CODE" > "$DIR/activation_code.txt"
echo ""
echo "已保存到 $DIR/activation_code.txt"

# 可选生成 QR 码
if command -v qrencode &>/dev/null; then
    qrencode -o "$DIR/activation_qr_$ID.png" "$ACTIVATION_CODE"
    echo "QR 码: $DIR/activation_qr_$ID.png"
fi

echo ""
echo "请提交 docs/issued.json 到 git:"
echo "  git add docs/issued.json && git commit -m 'add activation ${ID} for ${USERNAME}' && git push"
