#!/bin/bash
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
DOCS="$DIR/../docs"
PRIV="$DIR/private_key.pem"

[ -f "$PRIV" ] || { echo "密钥不存在: $PRIV"; exit 1; }
[ -f "$DOCS/issued.json" ] || { echo "issued.json 不存在: $DOCS/issued.json"; exit 1; }

openssl dgst -sha256 -sign "$PRIV" -out /tmp/issued.json.sig.raw "$DOCS/issued.json"
openssl base64 -A -in /tmp/issued.json.sig.raw > "$DOCS/issued.json.sig"
rm -f /tmp/issued.json.sig.raw

echo "已签名: $DOCS/issued.json.sig  ($(wc -c < "$DOCS/issued.json.sig") bytes)"
