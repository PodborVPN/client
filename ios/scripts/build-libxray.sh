#!/usr/bin/env bash
# Сборка LibXray.xcframework (iOS: устройство + симулятор) из того же тега XTLS/libXray, что и Android-сборка.
#   build-libxray.sh        — собрать, если Frameworks/LibXray.xcframework ещё нет
#   build-libxray.sh force  — пересобрать
# Только macOS (gomobile под Apple требует Xcode). Цели и минимальная iOS — как build/app/apple_gomobile.py апстрима,
# флаги линковки — как у Android (-checklinkname=0 не убирать).
set -euo pipefail
cd "$(dirname "$0")/.."

TAG=v26.10.21
COMMIT=dbe9512
OUT=Frameworks/LibXray.xcframework

die() { echo "СТОП: $*" >&2; exit 1; }

if [[ -d $OUT && ${1:-} != force ]]; then
  echo "уже есть: $OUT (force — пересобрать)"
  exit 0
fi

[[ $(uname) == Darwin ]] || die "нужна macOS с Xcode"
command -v go >/dev/null || die "нет Go"
# Тулчейн — как у выпусков Xray и Android-сборки; нужного нет — Go скачает сам.
export GOTOOLCHAIN=go1.27.3
command -v xcrun >/dev/null || die "нет Xcode (xcrun)"

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

gomobile bind -target ios,iossimulator -iosversion 15.0 -trimpath \
  -ldflags="-checklinkname=0 -s -w -buildid="

cd - >/dev/null
mkdir -p Frameworks
rm -rf "$OUT"
cp -R "$tmp/libXray/LibXray.xcframework" "$OUT"
echo "готово: $OUT ($(du -sh "$OUT" | cut -f1)), x/mobile $mobile, $(go version | cut -d' ' -f3)"
