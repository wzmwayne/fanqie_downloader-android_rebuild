#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
DOCS="$DIR/../docs"

echo "生成 RSA-2048 密钥对..."

openssl genrsa -out "$DIR/private_key.pem" 2048
openssl rsa -in "$DIR/private_key.pem" -pubout -out "$DOCS/public_key.pem"

echo "公钥已复制到 $DOCS/public_key.pem"

# 初始化 issued.json
cat > "$DOCS/issued.json" <<EOF
{
  "latest": 0,
  "codes": {}
}
EOF

echo "已初始化 $DOCS/issued.json"
echo ""
echo "完成！请提交 docs/ 目录到 git"
