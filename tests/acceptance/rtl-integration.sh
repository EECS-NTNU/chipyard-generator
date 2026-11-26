#!/usr/bin/env bash
set -euo pipefail

source "${REPO_ROOT:?}/tests/acceptance/common.sh"
source "$REPO_ROOT/tests/acceptance/simulation.sh"

function non_chisel_wrapper() {
    printf '%s\n' 'Regenerates SystemVerilog from an independently packaged wrapper and simulates all 256 inputs.'
    local workspace="$ACCEPTANCE_WORK/wrapper-consumer" wrapper="$ACCEPTANCE_WORK/verilog-wrapper"
    local revision payload run regenerated collateral
    cp -R -- "$REPO_ROOT/fixtures/wrapper-consumer" "$workspace"
    cp -R -- "$REPO_ROOT/fixtures/verilog-wrapper" "$wrapper"
    regenerated="$ACCEPTANCE_WORK/ByteTransform.sv"
    python3 "$wrapper/generate.py" "$regenerated"
    cmp -- "$regenerated" "$wrapper/src/main/resources/vsrc/ByteTransform.sv"
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
    payload="$ACCEPTANCE_WORK/payload/branches.elf"
    run_packaged_tool "$workspace" verilog-wrapper-resolve resolve
    jq -e --arg revision "$revision" 'any(.nodes[]; .id == "rtl-fixture" and .source.checkout.commit == $revision)' \
        "$workspace/hardware.lock" >/dev/null || fail "The wrapper commit is missing from hardware.lock"
    run_packaged_tool "$workspace" verilog-wrapper-build build default
    run_packaged_tool "$workspace" verilog-wrapper run default "$payload"
    simulation_passed "$ACCEPTANCE_ARTIFACTS/verilog-wrapper.log"
    grep -F 'Wrapper checked all 256 inputs' "$ACCEPTANCE_ARTIFACTS/verilog-wrapper.log"
    run=$(generated_run verilog-wrapper)
    collateral="$run/chipyard/sims/verilator/generated-src"
    require_rtl_module "$collateral" ByteTransform
    require_simulation_manifest verilog-wrapper "$workspace" default "$payload"
}

function mixed_revisions() {
    printf '%s\n' 'Simulates two aliased BOOM revisions with distinct hart identities and collision-free RTL.'
    local workspace="$ACCEPTANCE_WORK/mixed-boom-revisions" payload run lock
    cp -R -- "$REPO_ROOT/fixtures/alias-boom" "$workspace"
    mkdir -p -- "$workspace/config/mixed"
    cp -- "$REPO_ROOT/tests/acceptance/mixed/System.scala" "$workspace/config/mixed/"
    build_smoke_payload harts
    payload="$ACCEPTANCE_WORK/payload/harts.elf"
    run_packaged_tool "$workspace" mixed-boom-resolve resolve
    lock="$ACCEPTANCE_ARTIFACTS/mixed-boom.hardware.lock"
    cp -- "$workspace/hardware.lock" "$lock"

    run_packaged_tool "$workspace" mixed-boom-build build mixed
    run_packaged_tool "$workspace" mixed-boom run mixed "$payload"
    simulation_passed "$ACCEPTANCE_ARTIFACTS/mixed-boom.log"
    local log="$ACCEPTANCE_ARTIFACTS/mixed-boom.log"
    grep -E 'cpu@0[[:space:]]*\{' "$log"
    grep -E 'cpu@1[[:space:]]*\{' "$log"
    grep -E 'pmu@40001000[[:space:]]*\{' "$log"
    if grep -E 'pmu@40000000[[:space:]]*\{' "$log"; then
        fail "The pre-PMU BOOM hart unexpectedly exposes a PMU"
    fi
    run=$(generated_run mixed-boom)
    local filelists=()
    mapfile -t filelists < <(find "$run/chipyard/sims/verilator/generated-src" -name '*.all.f' -type f)
    (( ${#filelists[@]} == 1 )) || fail "Expected one final file list for the mixed-revision simulation"
    python3 "$REPO_ROOT/tests/acceptance/check-mixed-rtl.py" "${filelists[0]}"
    cmp -- "$lock" "$workspace/hardware.lock"
    require_simulation_manifest mixed-boom "$workspace" mixed "$payload"
}

case ${1-} in
    wrapper) non_chisel_wrapper ;;
    mixed) mixed_revisions ;;
    '')
        failures=0
        for check in wrapper mixed; do
            if ! bash "$0" "$check"; then
                printf 'RTL integration check %s failed.\n' "$check" >&2
                failures=$((failures + 1))
            fi
        done
        ((failures == 0))
        ;;
    *) fail "Unknown RTL integration check: $1" ;;
esac
