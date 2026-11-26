#!/bin/bash

source "$(dirname "${BASH_SOURCE[0]}")/android_toolchain.properties"

if [ -z "${ANDROID_HOME:-}" ]; then
  for SDK in "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/.local/lib/android/sdk" "$HOME/Library/Android/sdk"; do
    if [ -d "$SDK" ]; then
      export ANDROID_HOME="$SDK"
      break
    fi
  done
fi

for NDK_CANDIDATE in "${ANDROID_HOME:-}/ndk/$NDK_VERSION" "${ANDROID_NDK_HOME:-}" "${NDK:-}"; do
  if [[ -n "$NDK_CANDIDATE" && -f "$NDK_CANDIDATE/source.properties" ]] &&
    grep -Eq "^Pkg[.]Revision[[:space:]]*=[[:space:]]*${NDK_VERSION//./[.]}[[:space:]]*$" "$NDK_CANDIDATE/source.properties"; then
    export ANDROID_NDK_HOME="$NDK_CANDIDATE"
    export NDK="$NDK_CANDIDATE"
    return 0 2>/dev/null || exit 0
  fi
done

echo "Error: Android NDK $NDK_VERSION not found; install with sdkmanager 'ndk;$NDK_VERSION'." >&2
return 1 2>/dev/null || exit 1
