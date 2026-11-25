#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

build_reference_payload
payload="$MILESTONE_WORK/payload/rv64ui-p-simple"
payload_sha=$(sha256sum "$payload")
cp -R -- "$REPO_ROOT/fixtures/build-local" "$REPO_ROOT/fixtures/build-git" "$MILESTONE_WORK/"
local_workspace="$MILESTONE_WORK/build-local"
git_workspace="$MILESTONE_WORK/build-git"
bash "$git_workspace/prepare.sh"

run_workspace "$local_workspace" local-resolve hardwareResolve
run_workspace "$local_workspace" external-baseline "hardwareSimulate baseline \"$payload\""
simulation_passed "$MILESTONE_ARTIFACTS/external-baseline.log"
jq -e --arg digest "${payload_sha%% *}" '
    .experiment == "baseline" and .backend == "verilator-simulate" and
    .payload.sha256 == $digest and .payload.randomSeed == 1
' "$MILESTONE_ARTIFACTS/external-baseline.json"
rm -rf -- "$local_workspace/target/hardware/builds"

function verify_rebuild() {
    local workspace=$1 label=$2 module=$3 run
    cp -- "$workspace/hardware.lock" "$MILESTONE_ARTIFACTS/$label.lock"
    for attempt in first rebuilt; do
        run_workspace "$workspace" "$label-$attempt" 'hardwareElaborate counter'
        run=$(generated_run "$label-$attempt")
        require_rtl_module "$run/chipyard/sims/verilator/generated-src" "$module"
        cmp -- "$workspace/hardware.lock" "$MILESTONE_ARTIFACTS/$label.lock"
        rm -rf -- "$workspace/target/hardware"
    done
    jq -e --slurp '.[0] == .[1]' "$MILESTONE_ARTIFACTS/$label-first.json" "$MILESTONE_ARTIFACTS/$label-rebuilt.json"
}

verify_rebuild "$local_workspace" local-counter LocalCounter
run_workspace "$git_workspace" git-resolve hardwareResolve
verify_rebuild "$git_workspace" git-counter GitCounter
