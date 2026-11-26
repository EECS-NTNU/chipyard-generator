#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

printf '%s\n' 'Simulates Rocket from an independent workspace using the reference payload.'
build_reference_payload
payload="$ACCEPTANCE_WORK/payload/rv64ui-p-simple"
payload_sha=$(sha256sum "$payload")
cp -R -- "$REPO_ROOT/fixtures/build-local" "$REPO_ROOT/fixtures/build-git" "$ACCEPTANCE_WORK/"
local_workspace="$ACCEPTANCE_WORK/build-local"
git_workspace="$ACCEPTANCE_WORK/build-git"
bash "$git_workspace/prepare.sh"

run_workspace "$local_workspace" local-resolve hardwareResolve
if run_workspace "$local_workspace" external-baseline-missing "hardwareSimulate baseline \"$payload\""; then
    fail 'Simulation succeeded before a simulator was built'
fi
grep -F 'No matching simulator build. Run presolve build baseline first.' \
    "$ACCEPTANCE_ARTIFACTS/external-baseline-missing.log"
run_workspace "$local_workspace" external-baseline-build 'hardwareBuildSimulator baseline'
baseline_build=$(generated_run external-baseline-build)
run_workspace "$local_workspace" external-baseline "hardwareSimulate baseline \"$payload\""
simulation_passed "$ACCEPTANCE_ARTIFACTS/external-baseline.log"
jq -e --arg digest "${payload_sha%% *}" --arg build "$baseline_build" '
    .experiment == "baseline" and .backend == "verilator-simulate" and
    .buildDirectory == $build and .payload.sha256 == $digest and .payload.randomSeed == 1
' "$ACCEPTANCE_ARTIFACTS/external-baseline.json"

printf '\n// Change the experiment inputs after building the simulator.\n' >> "$local_workspace/config/baseline/System.scala"
if run_workspace "$local_workspace" external-baseline-stale "hardwareSimulate baseline \"$payload\""; then
    fail 'Simulation selected a stale build by default'
fi
grep -F 'No matching simulator build. Run presolve build baseline first.' \
    "$ACCEPTANCE_ARTIFACTS/external-baseline-stale.log"
run_workspace "$local_workspace" external-baseline-pinned "hardwareSimulate baseline \"$payload\" --build \"$baseline_build\""
simulation_passed "$ACCEPTANCE_ARTIFACTS/external-baseline-pinned.log"
rm -rf -- "$local_workspace/target/hardware/builds"

function verify_rebuild() {
    local workspace=$1 label=$2 module=$3 run
    cp -- "$workspace/hardware.lock" "$ACCEPTANCE_ARTIFACTS/$label.lock"
    for attempt in first rebuilt; do
        run_workspace "$workspace" "$label-$attempt" 'hardwareElaborate counter'
        run=$(generated_run "$label-$attempt")
        require_rtl_module "$run/chipyard/sims/verilator/generated-src" "$module"
        cmp -- "$workspace/hardware.lock" "$ACCEPTANCE_ARTIFACTS/$label.lock"
        rm -rf -- "$workspace/target/hardware"
    done
    jq -e --slurp '.[0] == .[1]' "$ACCEPTANCE_ARTIFACTS/$label-first.json" "$ACCEPTANCE_ARTIFACTS/$label-rebuilt.json"
}

printf '%s\n' 'Elaborates the local counter again after deleting generated hardware outputs.'
verify_rebuild "$local_workspace" local-counter LocalCounter
printf '%s\n' 'Elaborates the pinned Git counter again after deleting generated hardware outputs.'
run_workspace "$git_workspace" git-resolve hardwareResolve
verify_rebuild "$git_workspace" git-counter GitCounter
