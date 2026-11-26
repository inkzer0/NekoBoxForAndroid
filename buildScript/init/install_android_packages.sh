#!/usr/bin/env bash
set -euo pipefail

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${sdk:?Android SDK not configured}"
if manager=$(command -v sdkmanager); then
  :
elif [[ -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]]; then
  manager="$sdk/cmdline-tools/latest/bin/sdkmanager"
else
  shopt -s nullglob
  managers=("$sdk"/cmdline-tools/*/bin/sdkmanager)
  manager=${managers[-1]:-}
fi
if [[ -z ${manager:-} || ! -x "$manager" ]]; then
  echo "Android SDK command-line tools not found under $sdk" >&2
  exit 1
fi
"$manager" --sdk_root="$sdk" "$@"
