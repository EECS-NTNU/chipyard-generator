#!/usr/bin/env bash
set -euo pipefail

source "${REPO_ROOT:?}/tests/acceptance/common.sh"
source "$REPO_ROOT/tests/acceptance/simulation.sh"

function whole_generator() {
    printf '%s\n' 'Simulates a whole generator from an independent workspace and checks its behavior.'
    local workspace="$ACCEPTANCE_WORK/whole-generator" run
    cp -R -- "$REPO_ROOT/fixtures/build-local" "$workspace"
    cp -- "$REPO_ROOT/tests/acceptance/whole/System.scala" "$workspace/config/counter/System.scala"
    build_smoke_payload branches
    local payload="$ACCEPTANCE_WORK/payload/branches.elf"
    run_packaged_tool "$workspace" whole-generator-resolve resolve
    cp -- "$workspace/hardware.lock" "$ACCEPTANCE_ARTIFACTS/whole-generator.hardware.lock"
    run_packaged_tool "$workspace" whole-generator-build build counter
    run_packaged_tool "$workspace" whole-generator run counter "$payload"
    simulation_passed "$ACCEPTANCE_ARTIFACTS/whole-generator.log"
    grep -F 'Counter advanced correctly' "$ACCEPTANCE_ARTIFACTS/whole-generator.log"
    run=$(generated_run whole-generator)
    require_rtl_module "$run/chipyard/sims/verilator/generated-src" LocalCounter
    require_simulation_manifest whole-generator "$workspace" counter "$payload"
    printf '%s\n' 'Records the whole-generator run parameters in its manifest.'
    jq -e '.parameters.counter.width == 8' "$ACCEPTANCE_ARTIFACTS/whole-generator.build.json" >/dev/null ||
        fail "The whole-generator run manifest must record the effective counter width"
}

function replacement() {
    printf '%s\n' 'Simulates the default BOOM predictor from a separate BTB workspace.'
    local workspace="$ACCEPTANCE_WORK/boom-btb" run
    cp -R -- "$REPO_ROOT/fixtures/boom-btb" "$workspace"
    build_smoke_payload branches
    local payload="$ACCEPTANCE_WORK/payload/branches.elf"
    run_packaged_tool "$workspace" boom-btb-resolve resolve
    cp -- "$workspace/hardware.lock" "$ACCEPTANCE_ARTIFACTS/boom-btb.hardware.lock"
    run_packaged_tool "$workspace" boom-btb-baseline-build build baseline
    run_packaged_tool "$workspace" boom-btb-baseline run baseline "$payload"
    simulation_passed "$ACCEPTANCE_ARTIFACTS/boom-btb-baseline.log"
    run=$(generated_run boom-btb-baseline)
    require_rtl_module "$run/chipyard/sims/verilator/generated-src" BTBBranchPredictorBank
    reject_rtl_module "$run/chipyard/sims/verilator/generated-src" FixtureBTB

    printf '%s\n' 'Replaces the BOOM BTB through its native hook and checks taken-branch training.'
    run_packaged_tool "$workspace" boom-btb-custom-build build custom
    run_packaged_tool "$workspace" boom-btb-custom run custom "$payload"
    simulation_passed "$ACCEPTANCE_ARTIFACTS/boom-btb-custom.log"
    grep -F 'Custom BTB trained a taken branch' "$ACCEPTANCE_ARTIFACTS/boom-btb-custom.log"
    run=$(generated_run boom-btb-custom)
    require_rtl_module "$run/chipyard/sims/verilator/generated-src" FixtureBTB
    reject_rtl_module "$run/chipyard/sims/verilator/generated-src" BTBBranchPredictorBank

    require_simulation_manifest boom-btb-baseline "$workspace" baseline "$payload"
    require_simulation_manifest boom-btb-custom "$workspace" custom "$payload"
    jq -e '.parameters == {}' "$ACCEPTANCE_ARTIFACTS/boom-btb-baseline.build.json" >/dev/null
    local adapter="workspace:integrations/boom/src/main/scala/WithFixtureBTB.scala"
    jq -e --arg adapter "$adapter" '.sourceSelection.activeScalaSources | index($adapter) == null' \
        "$ACCEPTANCE_ARTIFACTS/boom-btb-baseline.build.json" >/dev/null
    jq -e --arg adapter "$adapter" '.sourceSelection.activeScalaSources | index($adapter) != null' \
        "$ACCEPTANCE_ARTIFACTS/boom-btb-custom.build.json" >/dev/null
    printf '%s\n' 'Records the effective custom BTB dimensions in the run manifest.'
    jq -e '.parameters["mainBtb.tile0"] | .sets == 64 and .ways == 4 and .offsetBits == 13 and .extendedSets == 64' \
        "$ACCEPTANCE_ARTIFACTS/boom-btb-custom.build.json" >/dev/null ||
        fail "The custom run manifest must record the effective BTB dimensions"
}

function unsupported_hook() {
    printf '%s\n' 'Reports an unsupported replacement when the host lacks the predictor hook.'
    local workspace="$ACCEPTANCE_WORK/unsupported-btb-host"
    cp -R -- "$REPO_ROOT/fixtures/boom-btb" "$workspace"
    run_packaged_tool "$workspace" unsupported-btb-host-resolve resolve
    if run_packaged_tool "$workspace" unsupported-btb-host compile unsupported; then
        fail "The replacement compiled against a host without the predictor hook"
    fi
    grep -F 'Unsupported integration: the fixture BTB requires a BOOM v3 predictor hook' \
        "$ACCEPTANCE_ARTIFACTS/unsupported-btb-host.log"
}

function workspace_isolation() {
    printf '%s\n' 'Keeps lockfiles and simulation outputs isolated across workspaces using the same packaged tool.'
    local whole="$ACCEPTANCE_WORK/whole-generator" btb="$ACCEPTANCE_WORK/boom-btb"
    local whole_run btb_run payload="$ACCEPTANCE_WORK/payload/branches.elf"
    whole_run=$(generated_run whole-generator)
    btb_run=$(generated_run boom-btb-custom)
    [[ $whole_run == "$whole/target/hardware/builds/"* && -d $whole_run ]] ||
        fail 'The whole-generator output is missing from its workspace'
    [[ $btb_run == "$btb/target/hardware/builds/"* && -d $btb_run ]] ||
        fail 'The BTB replacement output is missing from its workspace'
    cmp -- "$whole/hardware.lock" "$ACCEPTANCE_ARTIFACTS/whole-generator.hardware.lock"
    cmp -- "$btb/hardware.lock" "$ACCEPTANCE_ARTIFACTS/boom-btb.hardware.lock"
    if cmp -s -- "$whole/hardware.lock" "$btb/hardware.lock"; then
        fail 'The independent workspaces resolved identical lockfiles'
    fi
    require_simulation_manifest whole-generator "$whole" counter "$payload"
    require_simulation_manifest boom-btb-custom "$btb" custom "$payload"
}

case ${1-} in
    whole) whole_generator ;;
    replacement) replacement ;;
    unsupported) unsupported_hook ;;
    isolation) workspace_isolation ;;
    '')
        failures=0
        for check in whole replacement unsupported isolation; do
            if ! bash "$0" "$check"; then
                printf 'Generator simulation check %s failed.\n' "$check" >&2
                failures=$((failures + 1))
            fi
        done
        ((failures == 0))
        ;;
    *) fail "Unknown generator simulation check: $1" ;;
esac
