#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"

if [[ -f .fixture/revisions ]]; then
  exit 0
fi
if [[ -e .fixture ]]; then
  echo "An incomplete .fixture exists. Remove it and run ./prepare.sh again." >&2
  exit 1
fi

export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_AUTHOR_DATE=2026-11-25T00:00:00Z GIT_COMMITTER_DATE=2026-11-25T00:00:00Z
export GIT_AUTHOR_NAME='Hardware fixture' GIT_COMMITTER_NAME='Hardware fixture'
export GIT_AUTHOR_EMAIL='fixture@example.invalid' GIT_COMMITTER_EMAIL='fixture@example.invalid'
mkdir -p .fixture/remote

git -C .fixture/remote init -q --object-format=sha1 --template=
printf 'object ExampleCore { val width = 32 }\n' > .fixture/remote/ExampleCore.scala
git -C .fixture/remote add ExampleCore.scala
git -C .fixture/remote commit -qm baseline
git -C .fixture/remote rev-parse HEAD > .fixture/revisions.tmp
printf 'object ExampleCore { val width = 64 }\n' > .fixture/remote/ExampleCore.scala
git -C .fixture/remote commit -qam experimental
git -C .fixture/remote rev-parse HEAD >> .fixture/revisions.tmp
mv .fixture/revisions.tmp .fixture/revisions
