#!/usr/bin/env bash
# Builds the APK. Installs missing requirements (JDK 21 and Android SDK components) into the
# user's home directory, so no root access is needed. Linux only.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/Android/src"
SDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}"
BUILD_TYPE="release"
INSTALL=0

usage() {
  cat <<EOF
Uso: scripts/build-apk.sh [--debug | --release] [--install]

  --debug     Compila app-debug.apk (por defecto: --release)
  --release   Compila app-release.apk (firmada con la clave debug del proyecto)
  --install   Instala la APK en el dispositivo con adb (requiere adb en PATH)
  -h, --help  Muestra esta ayuda

Requisitos instalados automáticamente si faltan:
  - JDK 21 (Temurin) en ~/.jdks
  - Android SDK en \$ANDROID_HOME o ~/android-sdk: cmdline-tools, platform-tools,
    platforms;android-35 y build-tools;35.0.0
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug) BUILD_TYPE="debug" ;;
    --release) BUILD_TYPE="release" ;;
    --install) INSTALL=1 ;;
    -h | --help) usage; exit 0 ;;
    *) echo "Opción desconocida: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

log() { printf '\n==> %s\n' "$*"; }

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Falta '$1' en PATH. Instálalo con el gestor de paquetes de tu sistema." >&2
    exit 1
  }
}

[[ "$(uname -s)" == "Linux" ]] || { echo "Este script solo soporta Linux." >&2; exit 1; }
need curl
need unzip
need tar

java_is_21() {
  [[ -x "$1/bin/java" ]] && "$1/bin/java" -version 2>&1 | grep -q 'version "21'
}

ensure_jdk() {
  if [[ -n "${JAVA_HOME:-}" ]] && java_is_21 "$JAVA_HOME"; then return; fi
  local existing
  for existing in "$HOME"/.jdks/jdk-21*; do
    if [[ -d "$existing" ]] && java_is_21 "$existing"; then
      export JAVA_HOME="$existing"
      return
    fi
  done

  local arch
  case "$(uname -m)" in
    x86_64) arch="x64" ;;
    aarch64) arch="aarch64" ;;
    *) echo "Arquitectura no soportada: $(uname -m)" >&2; exit 1 ;;
  esac

  log "Descargando JDK 21 (Temurin) en ~/.jdks"
  mkdir -p "$HOME/.jdks"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/$arch/jdk/hotspot/normal/eclipse" \
    | tar -xz -C "$HOME/.jdks"
  for existing in "$HOME"/.jdks/jdk-21*; do
    if java_is_21 "$existing"; then export JAVA_HOME="$existing"; return; fi
  done
  echo "No se pudo preparar JDK 21 en ~/.jdks" >&2
  exit 1
}

ensure_sdk() {
  local sdkmanager="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
  if [[ ! -x "$sdkmanager" ]]; then
    log "Descargando Android command-line tools en $SDK_DIR"
    local tmp
    tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/cmdtools.zip" \
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    unzip -q "$tmp/cmdtools.zip" -d "$tmp"
    mkdir -p "$SDK_DIR/cmdline-tools"
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$tmp"
  fi

  log "Aceptando licencias e instalando componentes del SDK"
  # `yes` ends with SIGPIPE once sdkmanager exits, which is expected here.
  yes | "$sdkmanager" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
  "$sdkmanager" --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
  export ANDROID_HOME="$SDK_DIR"
}

write_local_properties() {
  local file="$ROOT/local.properties"
  if [[ ! -f "$file" ]] || ! grep -q '^sdk.dir=' "$file"; then
    printf 'sdk.dir=%s\n' "$SDK_DIR" >> "$file"
  fi
}

ensure_jdk
ensure_sdk
write_local_properties

if [[ "$BUILD_TYPE" == "debug" ]]; then task="assembleDebug"; else task="assembleRelease"; fi
log "Compilando $task (JAVA_HOME=$JAVA_HOME)"
(cd "$ROOT" && ./gradlew "$task")

apk="$(ls -t "$ROOT/app/build/outputs/apk/$BUILD_TYPE"/*.apk | head -1)"
log "APK generada: $apk"

if [[ "$INSTALL" -eq 1 ]]; then
  need adb
  adb install -r "$apk"
fi
