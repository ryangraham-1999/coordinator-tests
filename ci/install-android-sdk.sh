#!/bin/bash
# Same steps as the cloud environment's setup script (designs/stage-1-foundation.md 4.19.2).
# The two must stay the same; a change to one is a design amendment that changes both.
set -euo pipefail
SDK="${ANDROID_HOME:?ANDROID_HOME must be set}"
command -v unzip >/dev/null || { apt-get update -qq && apt-get install -y -qq unzip; }
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  CLT_ZIP=$(curl -fsSL https://developer.android.com/studio | grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' | head -1)
  test -n "$CLT_ZIP"
  rm -rf /tmp/clt && mkdir -p /tmp/clt "$SDK/cmdline-tools"
  curl -fsSL -o /tmp/clt/clt.zip "https://dl.google.com/android/repository/$CLT_ZIP"
  unzip -q /tmp/clt/clt.zip -d /tmp/clt
  mv /tmp/clt/cmdline-tools "$SDK/cmdline-tools/latest"
  rm -rf /tmp/clt
fi
(yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null) || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platform-tools" >/dev/null
