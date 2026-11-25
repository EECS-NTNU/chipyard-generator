#!/usr/bin/env bash
set -euo pipefail

source "${REPO_ROOT:?}/tests/milestones/common.sh"
source "$REPO_ROOT/tests/milestones/simulation.sh"

function non_chisel_wrapper() {
    local workspace="$MILESTONE_WORK/m6-wrapper-consumer" wrapper="$MILESTONE_WORK/verilog-wrapper"
    local revision payload run regenerated collateral
    cp -R -- "$REPO_ROOT/fixtures/milestone-wrapper-consumer" "$workspace"
    cp -R -- "$REPO_ROOT/fixtures/milestone-verilog-wrapper" "$wrapper"
    regenerated="$MILESTONE_WORK/MilestoneByteTransform.sv"
    python3 "$wrapper/generate.py" "$regenerated"
    cmp -- "$regenerated" "$wrapper/src/main/resources/vsrc/MilestoneByteTransform.sv"
    (
        export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
        export GIT_AUTHOR_NAME='Hardware fixture' GIT_COMMITTER_NAME='Hardware fixture'
        export GIT_AUTHOR_EMAIL='fixture@example.invalid' GIT_COMMITTER_EMAIL='fixture@example.invalid'
        export GIT_AUTHOR_DATE=2026-11-25T00:00:00Z GIT_COMMITTER_DATE=2026-11-25T00:00:00Z
        git -C "$wrapper" init -q --object-format=sha1 --template=
        git -C "$wrapper" add --all
        git -C "$wrapper" commit -qm 'Publish generated SystemVerilog fixture'
    )
    revision=$(git -C "$wrapper" rev-parse HEAD)
    printf '%s\n' "$revision" > "$workspace/wrapper.commit"

    build_smoke_payload branches
    payload="$MILESTONE_WORK/payload/branches.elf"
    run_packaged_tool "$workspace" m6-wrapper-resolve resolve
    jq -e --arg revision "$revision" 'any(.nodes[]; .id == "rtl-fixture" and .source.checkout.commit == $revision)' \
        "$workspace/hardware.lock" >/dev/null || fail "The wrapper commit is missing from hardware.lock"
    run_packaged_tool "$workspace" m6-wrapper run default "$payload"
    simulation_passed "$MILESTONE_ARTIFACTS/m6-wrapper.log"
    grep -F 'M6 wrapper checked all 256 inputs' "$MILESTONE_ARTIFACTS/m6-wrapper.log"
    run=$(generated_run m6-wrapper)
    collateral="$run/chipyard/sims/verilator/generated-src"
    require_rtl_module "$collateral" MilestoneByteTransform
    require_simulation_manifest m6-wrapper "$workspace" default "$payload"
}

function mixed_revisions() {
    local workspace="$MILESTONE_WORK/m6-mixed-revisions" payload run lock
    cp -R -- "$REPO_ROOT/fixtures/milestone-alias-boom" "$workspace"
    mkdir -p -- "$workspace/config/mixed"
    cp -- "$REPO_ROOT/tests/milestones/mixed/System.scala" "$workspace/config/mixed/"
    build_smoke_payload harts
    payload="$MILESTONE_WORK/payload/harts.elf"
    run_packaged_tool "$workspace" m6-mixed-resolve resolve
    lock="$MILESTONE_ARTIFACTS/m6-mixed.hardware.lock"
    cp -- "$workspace/hardware.lock" "$lock"

    run_packaged_tool "$workspace" m6-mixed run mixed "$payload"
    simulation_passed "$MILESTONE_ARTIFACTS/m6-mixed.log"
    local log="$MILESTONE_ARTIFACTS/m6-mixed.log"
    grep -E 'cpu@0[[:space:]]*\{' "$log"
    grep -E 'cpu@1[[:space:]]*\{' "$log"
    grep -E 'pmu@40001000[[:space:]]*\{' "$log"
    if grep -E 'pmu@40000000[[:space:]]*\{' "$log"; then
        fail "The pre-PMU BOOM hart unexpectedly exposes a PMU"
    fi
    run=$(generated_run m6-mixed)
    local filelists=()
    mapfile -t filelists < <(find "$run/chipyard/sims/verilator/generated-src" -name '*.all.f' -type f)
    (( ${#filelists[@]} == 1 )) || fail "Expected one final file list for the mixed-revision simulation"
    python3 "$REPO_ROOT/tests/milestones/check-mixed-rtl.py" "${filelists[0]}"
    cmp -- "$lock" "$workspace/hardware.lock"
    require_simulation_manifest m6-mixed "$workspace" mixed "$payload"
}

case ${1-} in
    wrapper) non_chisel_wrapper ;;
    mixed) mixed_revisions ;;
    '')
        failures=0
        for check in wrapper mixed; do
            if ! bash "$0" "$check"; then
                printf 'M6 %s acceptance failed.\n' "$check" >&2
                failures=$((failures + 1))
            fi
        done
        ((failures == 0))
        ;;
    *) fail "Unknown M6 check: $1" ;;
esac
