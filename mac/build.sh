#!/bin/bash
# Builds "KurmanciKlavye.app" (Intel + Apple Silicon) into mac/build/.
#   ./build.sh            build only
#   ./build.sh install    build, copy to ~/Library/Input Methods and register with the system
# Needs Apple's Command Line Tools (xcode-select --install) or Xcode, macOS 13 or later.
set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="KurmanciKlavye"
APP="build/$APP_NAME.app"
DICT="../app/src/main/assets/tr_ku.tsv"
DEST="$HOME/Library/Input Methods"

[ -f "$DICT" ] || { echo "Sözlük bulunamadı: $DICT (depoyu bütün olarak indirin)"; exit 1; }

binaries=()
for arch in x86_64 arm64; do
    echo "==> Derleniyor: $arch"
    if swift build -c release --arch "$arch"; then
        binaries+=("$(swift build -c release --arch "$arch" --show-bin-path)/$APP_NAME")
    elif [ "$arch" = "$(uname -m)" ]; then
        echo "Derleme başarısız oldu."; exit 1
    else
        echo "UYARI: $arch derlenemedi; yalnızca bu Mac'in mimarisi için devam ediliyor."
    fi
done

echo "==> Paket hazırlanıyor: $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
lipo -create "${binaries[@]}" -output "$APP/Contents/MacOS/$APP_NAME"
cp Resources/Info.plist "$APP/Contents/Info.plist"
cp -R Resources/tr.lproj Resources/en.lproj "$APP/Contents/Resources/"
tiffutil -cathidpicheck Resources/icon.png Resources/icon@2x.png -out "$APP/Contents/Resources/icon.tiff" >/dev/null
cp "$DICT" "$APP/Contents/Resources/tr_ku.tsv"
xattr -cr "$APP" 2>/dev/null || true  # a downloaded ZIP leaves quarantine flags on the copied files
codesign --force --sign - "$APP"
lipo -info "$APP/Contents/MacOS/$APP_NAME"

if [ "${1:-}" = "install" ]; then
    echo "==> Kuruluyor: $DEST"
    pkill -x "$APP_NAME" 2>/dev/null || true
    mkdir -p "$DEST"
    rm -rf "$DEST/$APP_NAME.app"
    cp -R "$APP" "$DEST/"
    "$DEST/$APP_NAME.app/Contents/MacOS/$APP_NAME" --register || true
    echo
    echo "Kuruldu. Şimdi: Sistem Ayarları → Klavye → Metin Girişi → Girdi Kaynakları → Düzenle… → +"
    echo "→ Türkçe → \"Kurmancî Klavye\" → Ekle. Listede görünmüyorsa oturumu kapatıp yeniden açın."
fi
