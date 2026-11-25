#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

prepare_counter() (
  export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
  export GIT_AUTHOR_DATE=2026-11-25T00:00:00Z GIT_COMMITTER_DATE=2026-11-25T00:00:00Z
  export GIT_AUTHOR_NAME='Hardware fixture' GIT_COMMITTER_NAME='Hardware fixture'
  export GIT_AUTHOR_EMAIL='fixture@example.invalid' GIT_COMMITTER_EMAIL='fixture@example.invalid'

  mkdir -p .fixture/remote/src/main/scala
  local source=.fixture/remote/src/main/scala/RevisionCounter.scala
  git -C .fixture/remote init -q --object-format=sha1 --template=
  cp source/RevisionCounter.scala "$source"
  git -C .fixture/remote add src
  git -C .fixture/remote commit -qm 'Counter revision A'
  git -C .fixture/remote rev-parse HEAD > .fixture/baseline
  sed 's/AliasCounterA/AliasCounterB/; s/count + 1.U/count + 2.U/' source/RevisionCounter.scala > "$source"
  git -C .fixture/remote add src
  git -C .fixture/remote commit -qm 'Counter revision B'
  git -C .fixture/remote rev-parse HEAD > .fixture/experimental
)

check_cached_source() {
  local directory=$1 revision=$2
  [[ $(git -C "$directory" rev-parse HEAD) == "$revision" ]] || fail "Cached checkout moved from $revision."
  local status
  status=$(GIT_OPTIONAL_LOCKS=0 git -C "$directory" status --porcelain=v1 --untracked-files=all --ignored=matching)
  [[ -z $status ]] || fail "Cached checkout $directory was modified: $status"
}

check_rtl() {
  local fixture=$1 experiment=$2 rtl=$3
  [[ -d $rtl ]] || fail "No RTL directory for $fixture/$experiment."
  if [[ $fixture == boom ]]; then
    require_rtl_module "$rtl" 'BoomTile(_[0-9]+)?'
    if [[ $experiment == experimental ]]; then
      require_rtl_module "$rtl" 'TilePMU(_[0-9]+)?'
    else
      reject_rtl_module "$rtl" 'TilePMU(_[0-9]+)?'
    fi
  else
    local selected=AliasCounterA excluded=AliasCounterB
    if [[ $experiment == experimental ]]; then
      selected=AliasCounterB excluded=AliasCounterA
    fi
    require_rtl_module "$rtl" "$selected"
    reject_rtl_module "$rtl" "$excluded"
  fi
}

run_case() {
  local fixture=$1 work="$MILESTONE_WORK/m4-$1"
  local MILESTONE_ARTIFACTS="$MILESTONE_ARTIFACTS/m4-$fixture"
  local workspace="$work/workspace" revision_a revision_b
  mkdir -p "$work" "$MILESTONE_ARTIFACTS"
  cp -R "$REPO_ROOT/fixtures/milestone-alias-$fixture" "$workspace"
  cd -- "$workspace"
  if [[ $fixture == counter ]]; then
    prepare_counter
    revision_a=$(cat .fixture/baseline)
    revision_b=$(cat .fixture/experimental)
  else
    revision_a=58ef2720eae13be26b3008c02b5a74ce29c61c44
    revision_b=ba03175e0a3cf5c0d5a7f14d385ac1c8998c4428
  fi

  run_workspace "$workspace" resolve hardwareResolve
  shopt -s nullglob
  local plans=(target/hardware/*/plan.json)
  [[ ${#plans[@]} == 1 ]] || fail "Expected one resolution plan for $fixture."
  local plan=${plans[0]} lock_fingerprint source_a source_b
  cp hardware.lock "$MILESTONE_ARTIFACTS/hardware.lock"
  cp "$plan" "$MILESTONE_ARTIFACTS/plan.json"
  lock_fingerprint=$(sha256sum hardware.lock | cut -d ' ' -f 1)

  source_a=$(jq -er --arg id "${fixture}_baseline" --arg revision "$revision_a" '
    .nodes[] | select(.id == $id and .source.checkout.commit == $revision) | .source.checkout.directory' "$plan")
  source_b=$(jq -er --arg id "${fixture}_experimental" --arg revision "$revision_b" '
    .nodes[] | select(.id == $id and .source.checkout.commit == $revision) | .source.checkout.directory' "$plan")
  [[ $source_a != "$source_b" ]] || fail 'The two revisions share a source checkout.'
  check_cached_source "$source_a" "$revision_a"
  check_cached_source "$source_b" "$revision_b"
  if [[ $fixture == boom ]]; then
    jq -e '
      ([.nodes[] | select(.id == "rocket-chip" or .id == "cde") | .id] | sort) == ["cde", "rocket-chip"]
      and ([.nodes[] | select(.id == "boom_baseline" or .id == "boom_experimental")
            | select(.dependencies | index("rocket-chip"))] | length) == 2
      and any(.nodes[]; .id == "rocket-chip" and (.dependencies | index("cde") != null))
    ' "$plan" >/dev/null
  fi

  local experiment run step=0
  for experiment in baseline experimental baseline experimental baseline; do
    step=$((step + 1))
    run_workspace "$workspace" "$step-$experiment" 'set hardwareOffline := true' "hardwareElaborate $experiment"
    run=$(generated_run "$step-$experiment")
    [[ -n $run && -f $run/run.json ]] || fail "Missing run manifest for $fixture/$experiment."
    jq -e --arg experiment "$experiment" --arg lock "$lock_fingerprint" \
      '.experiment == $experiment and .lockFingerprint == $lock' "$run/run.json" >/dev/null
    check_rtl "$fixture" "$experiment" "$run/chipyard/sims/verilator/generated-src"
    cmp hardware.lock "$MILESTONE_ARTIFACTS/hardware.lock"
    cmp "$plan" "$MILESTONE_ARTIFACTS/plan.json"
    plans=(target/hardware/*/plan.json)
    [[ ${#plans[@]} == 1 ]] || fail "Experiment $experiment generated a different dependency plan."
    check_cached_source "$source_a" "$revision_a"
    check_cached_source "$source_b" "$revision_b"
    rm -rf -- "$run"
  done
}

case ${1-} in
  counter|boom) run_case "$1" ;;
  '')
    failures=0
    for fixture in counter boom; do
      if ! bash "$0" "$fixture"; then
        printf 'M4 %s acceptance failed.\n' "$fixture" >&2
        failures=$((failures + 1))
      fi
    done
    ((failures == 0))
    ;;
  *) fail "Unknown M4 fixture: $1" ;;
esac
