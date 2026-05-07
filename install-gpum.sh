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
  java --enable-native-access=ALL-UNNAMED -jar "$TARGET_JAR" --version 2>/dev/null | awk 'NR == 1 { print $2 }'
}

write_launcher() {
  cat >"$TARGET_LAUNCHER" <<'EOF'
#!/usr/bin/env sh
set -eu
GPUM_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GPUM_JAR="$GPUM_ROOT/gpu-mgr.jar"
if [ ! -f "$GPUM_JAR" ]; then
  echo "ERROR: gpum jar not found at '$GPUM_JAR'." >&2
  echo "Place gpu-mgr.jar in the same directory as this launcher." >&2
  exit 1
fi
exec java --enable-native-access=ALL-UNNAMED -jar "$GPUM_JAR" "$@"
EOF
  chmod +x "$TARGET_LAUNCHER"
}

download_release() {
  tmp_jar="$TARGET_JAR.tmp"
  cleanup() {
    rm -f "$tmp_jar"
  }
  trap cleanup EXIT INT TERM
  curl -fsSL "$RELEASE_JAR_URL" -o "$tmp_jar"
  mv "$tmp_jar" "$TARGET_JAR"
  trap - EXIT INT TERM
}

detect_profile() {
  if [ -n "${ZSH_VERSION:-}" ]; then
    printf '%s\n' "$HOME/.zshrc"
    return
  fi
  if [ -n "${BASH_VERSION:-}" ]; then
    printf '%s\n' "$HOME/.bashrc"
    return
  fi
  if [ -f "$HOME/.profile" ]; then
    printf '%s\n' "$HOME/.profile"
    return
  fi
  printf '%s\n' "$HOME/.profile"
}

ensure_path() {
  if [ "${GPUM_SKIP_PATH_UPDATE:-0}" = "1" ]; then
    echo "Skipping PATH update because GPUM_SKIP_PATH_UPDATE=1"
    return 0
  fi

  case ":$PATH:" in
    *":$INSTALL_DIR:"*)
      return 0
      ;;
  esac

  profile="$(detect_profile)"
  marker="# Added by gpum installer"
  path_line="export PATH=\"$INSTALL_DIR:\$PATH\""

  mkdir -p "$(dirname "$profile")"
  touch "$profile"

  if grep -Fq "$path_line" "$profile"; then
    return 0
  fi

  {
    printf '\n%s\n' "$marker"
    printf '%s\n' "$path_line"
  } >> "$profile"

  echo "Added $INSTALL_DIR to PATH in $profile"
  echo "Apply it now with: . \"$profile\""
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
  echo "Remote release $REMOTE_VERSION is not newer. Skipping download."
  write_launcher
  ensure_path
  exit 0
fi

if [ -n "$LOCAL_VERSION" ]; then
  printf 'Installed version %s is older than remote version %s. Upgrade? [y/N] ' "$LOCAL_VERSION" "$REMOTE_VERSION"
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
ensure_path

echo "Installed gpum $REMOTE_VERSION"
echo "Launcher: $TARGET_LAUNCHER"
echo "Jar: $TARGET_JAR"
echo "Run: gpum --help"
