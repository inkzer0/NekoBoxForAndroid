#!/bin/bash
set -euo pipefail
source "buildScript/lib/core/get_source_env.sh"

checkout_source() {
  local directory="$1" remote="$2" commit="$3" tag="${4:-}"
  if [ ! -e "$directory" ]; then
    git clone --no-checkout "$remote" "$directory"
    git -C "$directory" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$directory" fetch origin "$commit"
    git -C "$directory" checkout --detach "$commit"
  else
    [ "$(git -C "$directory" remote get-url origin)" = "$remote" ] || {
      echo "Refusing to reuse a checkout from a different origin: $directory" >&2; return 1;
    }
    [ -z "$(git -C "$directory" status --porcelain --untracked-files=all)" ] || {
      echo "Source checkout is not clean: $directory" >&2; return 1;
    }
    [ "$(git -C "$directory" rev-parse HEAD)" = "$commit" ] || {
      echo "Existing checkout is not at the approved commit; use an isolated sibling: $directory" >&2; return 1;
    }
  fi
  if [ -n "$tag" ]; then
    if ! git -C "$directory" rev-parse --verify "refs/tags/$tag^{commit}" >/dev/null 2>&1; then
      git -C "$directory" fetch origin "refs/tags/$tag:refs/tags/$tag"
    fi
    [ "$(git -C "$directory" rev-parse "refs/tags/$tag^{commit}")" = "$commit" ]
  fi
  [ "$(git -C "$directory" rev-parse HEAD)" = "$commit" ]
  [ -z "$(git -C "$directory" status --porcelain --untracked-files=all)" ]
}

checkout_source ../sing-box https://github.com/SagerNet/sing-box.git "$COMMIT_SING_BOX" "v$VERSION_SING_BOX"
checkout_source ../libneko https://github.com/MatsuriDayo/libneko.git "$COMMIT_LIBNEKO"
