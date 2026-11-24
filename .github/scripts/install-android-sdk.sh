#!/usr/bin/env bash
# Install Android platform 37 for CI. Compose BOM requires compileSdk 37.
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

mkdir -p "$ANDROID_HOME/licenses" "$ANDROID_HOME/cmdline-tools" "$ANDROID_HOME/platforms"
printf '%s\n' '24333f8a63b6825ea9c5514f83c2829b004d1fee' \
  > "$ANDROID_HOME/licenses/android-sdk-license"
printf '%s\n' '84831b9409646a918e30573bab4c9c91346d8abd' \
  > "$ANDROID_HOME/licenses/android-sdk-preview-license"

# Fresh cmdline-tools so the package index includes current platforms.
install_cmdline_tools() {
  local tmp zip url
  tmp="$(mktemp -d)"
  for zip in \
    commandlinetools-linux-13114758_latest.zip \
    commandlinetools-linux-12266719_latest.zip \
    commandlinetools-linux-11076708_latest.zip
  do
    url="https://dl.google.com/android/repository/${zip}"
    echo "Downloading $url"
    if curl -fsSL -o "$tmp/cmdtools.zip" "$url"; then
      rm -rf "$ANDROID_HOME/cmdline-tools/latest-ci"
      mkdir -p "$ANDROID_HOME/cmdline-tools/latest-ci"
      unzip -qo "$tmp/cmdtools.zip" -d "$tmp/out"
      # Zip layout is cmdline-tools/{bin,lib,...}
      if [ -d "$tmp/out/cmdline-tools" ]; then
        mv "$tmp/out/cmdline-tools"/* "$ANDROID_HOME/cmdline-tools/latest-ci/"
      else
        mv "$tmp/out"/* "$ANDROID_HOME/cmdline-tools/latest-ci/"
      fi
      rm -rf "$tmp"
      return 0
    fi
  done
  rm -rf "$tmp"
  return 1
}

install_cmdline_tools
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest-ci/bin/sdkmanager"
chmod +x "$SDKMANAGER"
echo "Using sdkmanager: $SDKMANAGER"
yes | "$SDKMANAGER" --licenses >/dev/null || true

platform_installed() {
  [ -f "$ANDROID_HOME/platforms/android-37/android.jar" ]
}

install_platform_via_sdkmanager() {
  local ch pkg
  for ch in 0 1 2 3; do
    for pkg in "platforms;android-37" "platforms;android-37.1"; do
      echo "Trying sdkmanager --channel=$ch $pkg"
      if "$SDKMANAGER" --channel="$ch" "$pkg"; then
        platform_installed && return 0
      fi
    done
  done
  return 1
}

# Parse Google's repository XML for a platform-37 zip URL and unpack it.
install_platform_via_repository_xml() {
  local xml url dest name
  dest="$(mktemp -d)"
  for xml in repository2-3.xml repository2-1.xml; do
    echo "Fetching https://dl.google.com/android/repository/${xml}"
    if ! curl -fsSL "https://dl.google.com/android/repository/${xml}" -o "$dest/repo.xml"; then
      continue
    fi
    # Prefer complete platform archives that mention android-37 / platform-37.
    url="$(python3 - <<'PY' "$dest/repo.xml"
import re, sys
text = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
# Match url elements near platform-37 / android-37 package names.
candidates = re.findall(
    r"<url>\s*([^<]*platform[^<]*37[^<]*\.zip)\s*</url>",
    text,
    flags=re.I,
)
# Also plain href-style paths
candidates += re.findall(r"(platform-37[^\s\"'<>]*\.zip)", text, flags=re.I)
# Deduplicate preserving order
seen = set()
ordered = []
for c in candidates:
    c = c.strip()
    if c not in seen:
        seen.add(c)
        ordered.append(c)
for c in ordered:
    print(c)
    break
PY
)"
    if [ -z "${url:-}" ]; then
      continue
    fi
    case "$url" in
      http*) ;;
      *) url="https://dl.google.com/android/repository/${url}" ;;
    esac
    echo "Downloading platform: $url"
    if curl -fsSL -o "$dest/platform.zip" "$url"; then
      unzip -qo "$dest/platform.zip" -d "$ANDROID_HOME/platforms"
      # Normalize directory name to android-37 for compileSdk = 37
      if [ ! -d "$ANDROID_HOME/platforms/android-37" ]; then
        name="$(find "$ANDROID_HOME/platforms" -maxdepth 1 -type d -name 'android-37*' | head -n 1 || true)"
        if [ -n "$name" ]; then
          mv "$name" "$ANDROID_HOME/platforms/android-37"
        fi
      fi
      if platform_installed; then
        rm -rf "$dest"
        return 0
      fi
    fi
  done
  rm -rf "$dest"
  return 1
}

if ! platform_installed; then
  install_platform_via_sdkmanager || install_platform_via_repository_xml || {
    echo "::error::Could not install Android platform 37 (required by Compose)."
    "$SDKMANAGER" --list 2>/dev/null | grep -i "platforms;android" | head -n 40 || true
    exit 1
  }
fi

echo "Android 37 platform ready:"
ls -la "$ANDROID_HOME/platforms/android-37" | head

"$SDKMANAGER" "build-tools;35.0.0"
"$SDKMANAGER" --list_installed | head -n 80
