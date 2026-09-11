#!/bin/bash
set -eo pipefail
export GOTOOLCHAIN=go1.25.5
cd "$(dirname "$0")"
source ../buildScript/lib/core/get_source_env.sh
tool_root="$PWD/.build/toolchain"
tool_source="$tool_root/source"
mkdir -p "$tool_root/bin"
if [ ! -e "$tool_source" ]; then
  git clone --no-checkout https://github.com/SagerNet/gomobile.git "$tool_source"
  git -C "$tool_source" cat-file -e "$COMMIT_GOMOBILE^{commit}" 2>/dev/null || git -C "$tool_source" fetch origin "$COMMIT_GOMOBILE"
  git -C "$tool_source" checkout --detach "$COMMIT_GOMOBILE"
fi
[ "$(git -C "$tool_source" remote get-url origin)" = https://github.com/SagerNet/gomobile.git ]
[ "$(git -C "$tool_source" rev-parse HEAD)" = "$COMMIT_GOMOBILE" ]
[ "$(git -C "$tool_source" rev-parse "$VERSION_GOMOBILE^{commit}")" = "$COMMIT_GOMOBILE" ]
[ -z "$(git -C "$tool_source" status --porcelain --untracked-files=all)" ]
# Rebuild both actual binaries from the verified source; never reuse GOPATH tools.
# gomobile init would install an unpinned gobind and is unnecessary for binding.
pushd "$tool_source"
go build -mod=readonly -trimpath -buildvcs=true -o "$tool_root/bin/gomobile" ./cmd/gomobile
go build -mod=readonly -trimpath -buildvcs=true -o "$tool_root/bin/gobind" ./cmd/gobind
popd
go version -m "$tool_root/bin/gomobile" "$tool_root/bin/gobind"
