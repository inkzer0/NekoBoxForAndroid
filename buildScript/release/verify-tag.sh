#!/usr/bin/env bash
set -euo pipefail

tag=${1:?release tag required}
version=$(sed -n 's/^VERSION_NAME=//p' nb4a.properties)
if [[ ! "$version" =~ ^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$ || "$tag" != "v$version" ]]; then
  echo "Expected release tag v$version for the checked-out version; got $tag." >&2
  exit 1
fi

ref="refs/tags/$tag"
refs=$(git ls-remote --exit-code origin "$ref" "$ref^{}")
# Annotated tags have both an object and a peeled commit; lightweight tags have one line.
tag_commit=$(awk -v ref="$ref" '$2==ref{tag=$1} $2==ref"^{}"{commit=$1} END{print commit?commit:tag}' <<<"$refs")
source_commit=$(git rev-parse HEAD)
if [[ "$tag_commit" != "$source_commit" ]]; then
  echo "Release tag $tag points to $tag_commit but this run built $source_commit." >&2
  exit 1
fi
echo "Verified $tag -> $source_commit"
