#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
dir=$(dirname "${BASH_SOURCE[0]}")
source "$dir/../init/android_toolchain.properties"

if (( $# == 0 )); then
  echo 'Usage: verify-packages.sh app.aab | arm64.apk x86_64.apk [other APKs...]' >&2
  exit 1
fi

python3 "$dir/check-elf.py" "$@"
for package in "$@"; do
  case "$package" in
    *.apk)
      tools="${ANDROID_HOME:?Android SDK required}/build-tools/$BUILD_TOOLS_VERSION"
      "$tools/apksigner" verify --verbose "$package"
      "$tools/zipalign" -c -P 16 -v 4 "$package"
      ;;
    *.aab)
      signature=$(jarsigner -J-Duser.language=en -J-Duser.country=US -verify "$package")
      if ! grep -qx 'jar verified[.]' <<<"$signature"; then
        echo "AAB signature did not verify: $package" >&2
        exit 1
      fi
      config=$(java -jar "${BUNDLETOOL_JAR:?Verified bundletool JAR required}" dump config --bundle="$package")
      grep -q 'PAGE_ALIGNMENT_16K' <<<"$config"
      ;;
    *) echo "Unsupported package: $package" >&2; exit 1 ;;
  esac
done
