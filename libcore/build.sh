#!/bin/bash
set -eo pipefail
export GOTOOLCHAIN=go1.25.5
cd "$(dirname "$0")"
[ ! -f ./env_java.sh ] || source ./env_java.sh
source ../buildScript/init/env_ndk.sh
source ../buildScript/lib/core/get_source_env.sh
core=../../sing-box
[ "$(git -C "$core" remote get-url origin)" = https://github.com/SagerNet/sing-box.git ]
[ "$(git -C "$core" rev-parse HEAD)" = "$COMMIT_SING_BOX" ]
[ "$(git -C "$core" rev-parse "v$VERSION_SING_BOX^{commit}")" = "$COMMIT_SING_BOX" ]
[ -z "$(git -C "$core" status --porcelain --untracked-files=all)" ]
bash ./init.sh
# Official gomobile resolves gobind through PATH, not the old GOBIND variable.
export PATH="$PWD/.build/toolchain/bin:$PATH"
gomobile bind -v -target=android -androidapi 21 -trimpath \
  -ldflags="-X github.com/sagernet/sing-box/constant.Version=$VERSION_SING_BOX -X libcore.coreCommit=$COMMIT_SING_BOX -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=" \
  -tags=with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api \
  -o libcore.aar .
[ -z "$(git -C "$core" status --porcelain --untracked-files=all)" ]
mkdir -p ../app/libs
cp libcore.aar ../app/libs/libcore.aar
echo ">> install $(realpath ../app/libs/libcore.aar)"
