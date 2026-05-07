#!/usr/bin/env sh
set -eu

RELEASE_VERSION_URL="${GPUM_RELEASE_VERSION_URL:-https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpum-version.txt}"
RELEASE_JAR_URL="${GPUM_RELEASE_JAR_URL:-https://github.com/drewdrew0414/AIGPUManager/releases/latest/download/gpu-mgr.jar}"
INSTALL_DIR="${GPUM_INSTALL_DIR:-$HOME/.local/bin}"
TARGET_JAR="$INSTALL_DIR/gpu-mgr.jar"
TARGET_LAUNCHER="$INSTALL_DIR/gpum"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $1" >&2
    exit 1
  fi
}

normalize_version() {
  printf '%s' "$1" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s/^v//'
}

version_ge() {
  awk -v a="$(normalize_version "$1")" -v b="$(normalize_version "$2")" '
    function splitver(v, out, n, i) {
      n = split(v, out, /\./)
      for (i = n + 1; i <= 4; i++) {
        out[i] = 0
      }
    }
    BEGIN {
      splitver(a, av)
      splitver(b, bv)
      for (i = 1; i <= 4; i++) {
        if ((av[i] + 0) > (bv[i] + 0)) exit 0
        if ((av[i] + 0) < (bv[i] + 0)) exit 1
      }
      exit 0
    }
  '
}

installed_version() {
  if [ ! -f "$TARGET_JAR" ]; then
    return 1
  fi
  java -jar "$TARGET_JAR" --version 2>/dev/null | awk 'NR == 1 { print $2 }'
}

write_launcher() {
  cat >"$TARGET_LAUNCHER" <<'EOF'
#!/usr/bin/env sh
set -eu
GPUM_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GPUM_JAR="$GPUM_ROOT/gpu-mgr.jar"
if [ ! -f "$GPUM_JAR" ]; then
  echo "ERROR: gpum jar not found at '$GPUM_JAR'." >&2
  exit 1
fi
exec java -jar "$GPUM_JAR" "$@"
EOF
  chmod +x "$TARGET_LAUNCHER"
}

download_release() {
  tmp_jar="$TARGET_JAR.tmp"
  curl -fsSL "$RELEASE_JAR_URL" -o "$tmp_jar"
  mv "$tmp_jar" "$TARGET_JAR"
}

require_command curl
require_command java
mkdir -p "$INSTALL_DIR"

REMOTE_VERSION="$(curl -fsSL "$RELEASE_VERSION_URL" | tr -d '\r\n')"
REMOTE_VERSION="$(normalize_version "$REMOTE_VERSION")"
if [ -z "$REMOTE_VERSION" ]; then
  echo "ERROR: remote version string is empty." >&2
  exit 1
fi

LOCAL_VERSION=""
if LOCAL_VERSION="$(installed_version)"; then
  LOCAL_VERSION="$(normalize_version "$LOCAL_VERSION")"
fi

if [ -n "$LOCAL_VERSION" ] && version_ge "$LOCAL_VERSION" "$REMOTE_VERSION"; then
  echo "gpum $LOCAL_VERSION is already installed at $TARGET_JAR"
  echo "Remote release $REMOTE_VERSION is not newer. Pass."
  write_launcher
  exit 0
fi

if [ -n "$LOCAL_VERSION" ]; then
  printf 'Installed version %s is lower than remote version %s. Upgrade? [y/N] ' "$LOCAL_VERSION" "$REMOTE_VERSION"
  read -r answer
  case "${answer:-N}" in
    y|Y|yes|YES) ;;
    *)
      echo "Cancelled."
      exit 0
      ;;
  esac
fi

download_release
write_launcher

echo "Installed gpum $REMOTE_VERSION"
echo "Launcher: $TARGET_LAUNCHER"
echo "Jar: $TARGET_JAR"
echo "Run with: $TARGET_LAUNCHER --help"
