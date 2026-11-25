#!/bin/bash
set -euo pipefail

# Release builds use tested data versions and do not rely on GitHub's
# rate-limited, unauthenticated "latest release" API.
dir=app/src/main/assets/sing-box
mkdir -p "$dir"
stage=$(mktemp -d "$dir/.download.XXXXXX")
trap 'rm -rf "$stage"' EXIT

fetch_database() {
  local kind=$1 version=$2 checksum=$3
  echo "Fetching $kind $version"
  curl --fail --silent --show-error --location --retry 3 \
    "https://github.com/SagerNet/sing-$kind/releases/download/$version/$kind.db" \
    --output "$stage/$kind.db"
  printf '%s  %s\n' "$checksum" "$stage/$kind.db" | sha256sum --check
  xz -9 "$stage/$kind.db"
  printf '%s' "$version" > "$stage/$kind.version.txt"
}

fetch_database geoip 20261112 0da32148f688ba65c0c6b2e2e0b1e6261802534819545d6fd2e3c8e71322825e
fetch_database geosite 20261124090120 23a17958d2636997d3f3e4fe0d672b6b17cc24cd491239e67300f062f8b85945
for kind in geoip geosite; do
  mv -f "$stage/$kind.db.xz" "$stage/$kind.version.txt" "$dir/"
done
