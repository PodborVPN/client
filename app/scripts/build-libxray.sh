#!/usr/bin/env bash
# Сборка libXray.aar (только arm64) из тега XTLS/libXray во временном каталоге.
#   build-libxray.sh        — собрать, если app/libs/libXray.aar ещё нет
#   build-libxray.sh force  — пересобрать
# Флаги gomobile — как в build/app/android.py апстрима (-checklinkname=0 не убирать),
# цель — одна: android/arm64 (телефон arm64, AAR меньше).
set -euo pipefail
cd "$(dirname "$0")/.."

TAG=v26.10.21
COMMIT=dbe9512
OUT=libs/libXray.aar
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$HOME/go-sdk/go/bin:$HOME/go/bin:$PATH"

die() { echo "СТОП: $*" >&2; exit 1; }

if [[ -f $OUT && ${1:-} != force ]]; then
  echo "уже есть: $OUT (force — пересобрать)"
  exit 0
fi

command -v go >/dev/null || die "нет Go (нужен ≥ 1.27.2 в ~/go-sdk/go, см. README)"
# Тулчейн — как у выпусков Xray: в 1.27.3 фильтр отозванных корневых сертификатов. Нужного нет — Go скачает сам.
export GOTOOLCHAIN=go1.27.3
command -v git >/dev/null || die "нет git"
# Заданный ANDROID_NDK_HOME (CI закрепляет версию) — как есть; иначе самая новая из установленных.
if [[ -n ${ANDROID_NDK_HOME:-} ]]; then
  [[ -d $ANDROID_NDK_HOME ]] || die "ANDROID_NDK_HOME=$ANDROID_NDK_HOME — нет такого каталога"
else
  ndk=$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1 || true)
  [[ -n $ndk ]] || die "нет NDK в $ANDROID_HOME/ndk (sdkmanager \"ndk;<версия>\", см. README)"
  export ANDROID_NDK_HOME="${ndk%/}"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git clone -q --depth 1 --branch "$TAG" https://github.com/XTLS/libXray "$tmp/libXray"
cd "$tmp/libXray"
[[ $(git rev-parse --short=7 HEAD) == "$COMMIT" ]] || die "тег $TAG указывает не на коммит $COMMIT"

# Версия x/mobile — ровно та, что в go.mod libXray.
mobile=$(go list -m -f '{{.Version}}' golang.org/x/mobile)
[[ -n $mobile ]] || die "не определилась версия golang.org/x/mobile"
go get -tool "golang.org/x/mobile/cmd/gobind@$mobile"
GOBIN="$tmp/bin" go install "golang.org/x/mobile/cmd/gomobile@$mobile"
export PATH="$tmp/bin:$PATH"
gomobile init

gomobile bind -target android/arm64 -androidapi 21 -trimpath \
  -ldflags="-checklinkname=0 -s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384"

cd - >/dev/null
mkdir -p libs
cp "$tmp/libXray/libXray.aar" "$OUT"
echo "готово: $OUT ($(du -h "$OUT" | cut -f1)), x/mobile $mobile, NDK $(basename "$ANDROID_NDK_HOME")"
