#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools/layout-ai target/ai-layout-setup
docker_renderer=false
skip_probe=false
for argument in "$@"; do
  case "$argument" in
    --docker-renderer) docker_renderer=true ;;
    --skip-render-probe) skip_probe=true ;;
    *) echo "Unknown argument: $argument" >&2; exit 1 ;;
  esac
done
if $docker_renderer && $skip_probe; then
  echo '--docker-renderer requires its render probe; omit --skip-render-probe.' >&2
  exit 1
fi

verify() {
  local actual
  if command -v sha256sum >/dev/null; then actual=$(sha256sum "$1" | awk '{print $1}');
  else actual=$(shasum -a 256 "$1" | awk '{print $1}'); fi
  [[ "$actual" == "$2" ]]
}
download() {
  local url=$1 destination=$2 hash=$3 partial
  if [[ -f "$destination" ]] && verify "$destination" "$hash"; then
    echo "Verified existing $destination"
    return
  fi
  partial=$(mktemp "$destination.partial.XXXXXXXX")
  if ! curl --fail --location --retry 3 "$url" --output "$partial" || ! verify "$partial" "$hash"; then
    rm -f -- "$partial"
    echo "Download or SHA256 verification failed: $url" >&2
    exit 1
  fi
  mv -f -- "$partial" "$destination"
}
model_base=https://huggingface.co/PaddlePaddle/PP-DocLayoutV3_onnx/resolve/46bbdf188bb0a772c08aed74882ce7e51a8f1ea6
download "$model_base/inference.onnx" .tools/layout-ai/inference.onnx 45bf71750b00739a41fc209f132eb104a4d6b5bb29483c9078164d8b87cf28ba
download "$model_base/inference.yml" .tools/layout-ai/inference.yml 506fcfac13b3b546ae40d7886b44126420f392adb694e3f8bb6a6286a1f90fdc

image=doc2tex-layout-renderer:26.2.6
if $docker_renderer; then
  command -v docker >/dev/null || { echo 'Install Docker before selecting --docker-renderer.' >&2; exit 1; }
  if ! docker info > target/ai-layout-setup/docker-info.log 2>&1; then
    echo 'Starting the installed Docker Desktop for the requested container renderer...'
    docker desktop start --timeout 60 > target/ai-layout-setup/docker-start.log 2>&1 || {
      echo 'Start your Docker Linux engine, then rerun --docker-renderer. See target/ai-layout-setup/docker-start.log.' >&2
      exit 1
    }
  fi
  [[ "$(docker info --format '{{.OSType}}')" == linux ]] || { echo 'The renderer requires the Docker Linux engine.' >&2; exit 1; }
  echo 'Building the pinned LibreOffice container renderer...'
  docker build --platform linux/amd64 --progress plain --tag "$image" packaging/layout-renderer > target/ai-layout-setup/docker-build.log 2>&1
  docker run --rm --network none "$image" --headless --version | tee target/ai-layout-setup/libreoffice-version.log
else
soffice=${SOFFICE_PATH:-}
if [[ -z "$soffice" ]]; then
  soffice=$(command -v libreoffice || command -v soffice || true)
  if [[ -z "$soffice" && -x /Applications/LibreOffice.app/Contents/MacOS/soffice ]]; then
    soffice=/Applications/LibreOffice.app/Contents/MacOS/soffice
  fi
fi
if [[ -z "$soffice" || ! -x "$soffice" ]]; then
  cat >&2 <<'EOF'
Install LibreOffice manually, then rerun or set SOFFICE_PATH to its executable:
  Debian/Ubuntu: sudo apt install libreoffice-writer
  Fedora:        sudo dnf install libreoffice-writer
  macOS:         brew install --cask libreoffice
  Windows:       powershell -ExecutionPolicy Bypass -File scripts/setup-ai-layout.ps1
This script does not install system packages or change PATH.
EOF
  exit 1
fi
"$soffice" --headless --version | tee target/ai-layout-setup/libreoffice-version.log
echo "SOFFICE_PATH=$soffice"
fi
if ! $skip_probe; then
  probe="$PWD/target/ai-layout-render-probe"
  mkdir -p "$probe"
  profile=$(mktemp -d "$probe/profile-XXXXXXXX")
  mkdir -p "$profile/user"
  # LibreOffice Common.xcs disables all macro languages; Writer.xcs Link=2 means Never.
  cat > "$profile/user/registrymodifications.xcu" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<oor:items xmlns:oor="http://openoffice.org/2001/registry">
 <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop><prop oor:name="DisableActiveContent" oor:op="fuse"><value>true</value></prop><prop oor:name="BlockUntrustedRefererLinks" oor:op="fuse"><value>true</value></prop></item>
 <item oor:path="/org.openoffice.Office.Writer/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>2</value></prop></item>
</oor:items>
EOF
  cp src/test/resources/corpus/research-complex.docx "$probe/source.docx"
  rm -f -- "$probe/source.pdf"
  if $docker_renderer; then
    container_name=doc2tex-layout-setup-$(basename "$profile")-$$
    trap 'docker rm --force "$container_name" >/dev/null 2>&1 || true' EXIT
    task_mount_source="source=$probe"
    task_mount_source=${task_mount_source//\"/\"\"}
    docker run --rm --name "$container_name" --network none --pull=never \
      --user "$(id -u):$(id -g)" --env XDG_CACHE_HOME=/work/cache \
      --mount "type=bind,\"$task_mount_source\",target=/work" "$image" \
      "-env:UserInstallation=file:///work/$(basename "$profile")/" \
      --headless --nologo --nodefault --nofirststartwizard --norestore \
      --convert-to pdf:writer_pdf_Export --outdir /work /work/source.docx \
      > target/ai-layout-setup/libreoffice-render.log 2>&1
    trap - EXIT
  else
  # Percent-encode the path bytes; avoid relying on Python or modifying the user's profile.
  profile_uri=file://
  LC_ALL=C
  for ((i=0; i<${#profile}; i++)); do
    c=${profile:i:1}
    case "$c" in [a-zA-Z0-9/._~-]) profile_uri+=$c ;; *) printf -v encoded '%%%02X' "'$c"; profile_uri+=$encoded ;; esac
  done
  "$soffice" "-env:UserInstallation=$profile_uri/" --headless --nologo --nodefault --nofirststartwizard --norestore \
    --convert-to pdf:writer_pdf_Export --outdir "$probe" "$probe/source.docx" > target/ai-layout-setup/libreoffice-render.log 2>&1
  fi
  [[ -s "$probe/source.pdf" && "$(head -c 5 "$probe/source.pdf")" == '%PDF-' ]] || {
    echo 'Render probe failed; inspect target/ai-layout-setup/libreoffice-render.log' >&2; exit 1;
  }
  echo "Render probe ready: $probe/source.pdf"
  if $docker_renderer; then
    settings=.tools/layout-ai/renderer.properties
    temporary=$(mktemp "$settings.XXXXXXXX")
    if [[ -f "$settings" ]]; then
      awk '!/^[[:space:]]*doc2tex[.]ai-layout[.]docker-image[[:space:]]*[=:]/' "$settings" > "$temporary"
    fi
    printf '\ndoc2tex.ai-layout.docker-image=%s\n' "$image" >> "$temporary"
    mv -f -- "$temporary" "$settings"
    echo "Docker renderer selected in $settings; inference stays on the host."
  fi
fi
echo 'Local AI layout prerequisites ready.'
