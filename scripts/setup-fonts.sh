#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools/fonts
noto=https://raw.githubusercontent.com/notofonts/noto-fonts/ffebf8c1ee449e544955a7e813c54f9b73848eac
cjk=https://raw.githubusercontent.com/notofonts/noto-cjk/f8d157532fbfaeda587e826d4cd5b21a49186f7c
while read -r name url expected; do
  target=".tools/fonts/$name"
  actual=''
  if [[ -f "$target" ]]; then
    if command -v sha256sum >/dev/null; then actual=$(sha256sum "$target" | awk '{print $1}');
    else actual=$(shasum -a 256 "$target" | awk '{print $1}'); fi
  fi
  if [[ "$actual" != "$expected" ]]; then
    curl --fail --location --retry 3 "$url" --output "$target"
    if command -v sha256sum >/dev/null; then actual=$(sha256sum "$target" | awk '{print $1}');
    else actual=$(shasum -a 256 "$target" | awk '{print $1}'); fi
    [[ "$actual" == "$expected" ]] || { echo "Font checksum failed: $name" >&2; exit 1; }
  fi
done <<ASSETS
NotoSerif-Regular.ttf $noto/hinted/ttf/NotoSerif/NotoSerif-Regular.ttf c8f669ceb2c9c60ccf55198b305e08a997ffca79a38cc7eeb551e643cbe66505
NotoSansThai-Regular.ttf $noto/hinted/ttf/NotoSansThai/NotoSansThai-Regular.ttf 404ddfb5ed0aaa6b6ec8a85700d682978992062d67da93903967b56cbd9a4acc
NotoNaskhArabic-Regular.ttf $noto/hinted/ttf/NotoNaskhArabic/NotoNaskhArabic-Regular.ttf 2f4b88e6ee50fa82c617e2d1d4ba18281cb1c6cd71c3af3ec64970c23995db4b
NotoSansCJKsc-Regular.otf $cjk/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf 2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b
OFL-Noto.txt $noto/LICENSE 0dab92d0544f7b233403f14b84a663bdbfa746982eda629e7f4f9ffe1b036feb
OFL-Noto-CJK.txt $cjk/Sans/LICENSE 6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2
ASSETS
echo 'Multilingual fonts ready. Font licenses are included in multilingual exports.'
