#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools/downloads

verify() {
  local actual
  if command -v sha256sum >/dev/null; then actual=$(sha256sum "$1" | awk '{print $1}');
  else actual=$(shasum -a 256 "$1" | awk '{print $1}'); fi
  [[ "$actual" == "$2" ]] || { echo "Checksum verification failed: $1" >&2; exit 1; }
}
install_tool() {
  local name=$1 url=$2 hash=$3
  local archive=".tools/downloads/$name-${url##*/}"
  if [[ -x ".tools/$name/$name" ]]; then echo "$name is already installed."; return; fi
  curl --fail --location --retry 3 "$url" --output "$archive"
  verify "$archive" "$hash"
  mkdir -p ".tools/$name/upstream"
  case "$archive" in
    *.zip) unzip -oq "$archive" -d ".tools/$name/upstream" ;;
    *.tar.gz) tar -xzf "$archive" -C ".tools/$name/upstream" ;;
  esac
  local binary
  binary=$(find ".tools/$name/upstream" -type f -name "$name" -print -quit)
  [[ -n "$binary" ]] || { echo "Missing $name executable" >&2; exit 1; }
  cp "$binary" ".tools/$name/$name"
  chmod +x ".tools/$name/$name"
  ".tools/$name/$name" --version
}

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)
    pandoc_asset=linux-amd64.tar.gz
    pandoc_hash=d0c90410e90204c9ca83b8539fac5c7aed01fd537207e4585849f8abc5df20b8
    tectonic_asset=x86_64-unknown-linux-musl.tar.gz
    tectonic_hash=8533d07f9ccbd7a65824b9e0459041bca34af1eb33daba48f59215593753a3b7 ;;
  Darwin-arm64)
    pandoc_asset=arm64-macOS.zip
    pandoc_hash=c2146cf33a583f3ac93eccddf1bb662371d5cfcce1aad1c87ad860cca1435d46
    tectonic_asset=aarch64-apple-darwin.tar.gz
    tectonic_hash=a3f1cac7c5678f01661a92212f58480ae3b0634115d880dbc59e2953ded45667 ;;
  Darwin-x86_64)
    pandoc_asset=x86_64-macOS.zip
    pandoc_hash=81ed3e2cfecf096df15d407152f89a684fd65221082b919963f738e977fad3bc
    tectonic_asset=x86_64-apple-darwin.tar.gz
    tectonic_hash=7c90ef5b6ddb1eb1937e4337add5237b79338e4b9676459fa91187d24d6cdf80 ;;
  *) echo 'Unsupported architecture. Install Pandoc and Tectonic manually and set PANDOC_PATH / TECTONIC_PATH.' >&2; exit 1 ;;
esac
install_tool pandoc "https://github.com/jgm/pandoc/releases/download/3.12.1/pandoc-3.12.1-$pandoc_asset" "$pandoc_hash"
install_tool tectonic "https://github.com/tectonic-typesetting/tectonic/releases/download/tectonic%400.17.0/tectonic-0.17.0-$tectonic_asset" "$tectonic_hash"
bash scripts/setup-fonts.sh
