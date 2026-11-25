#!/usr/bin/env bash
set -euo pipefail

source "${REPO_ROOT:?}/tests/milestones/common.sh"
source "$REPO_ROOT/tests/milestones/simulation.sh"

function whole_generator() {
    local workspace="$MILESTONE_WORK/m5-whole" run
    cp -R -- "$REPO_ROOT/fixtures/build-local" "$workspace"
    cp -- "$REPO_ROOT/tests/milestones/whole/System.scala" "$workspace/config/counter/System.scala"
    build_smoke_payload branches
    local payload="$MILESTONE_WORK/payload/branches.elf"
    run_packaged_tool "$workspace" m5-whole-resolve resolve
    run_packaged_tool "$workspace" m5-whole run counter "$payload"
    simulation_passed "$MILESTONE_ARTIFACTS/m5-whole.log"
    grep -F 'M5 whole counter advanced correctly' "$MILESTONE_ARTIFACTS/m5-whole.log"
    run=$(generated_run m5-whole)
    require_rtl_module "$run/chipyard/sims/verilator/generated-src" LocalCounter
    require_simulation_manifest m5-whole "$workspace" counter "$payload"
    jq -e '(.parameters | type == "object")' "$MILESTONE_ARTIFACTS/m5-whole.json" >/dev/null ||
        fail "The whole-generator run manifest must record the effective parameters"
}

function replacement() {
    local workspace="$MILESTONE_WORK/m5-btb" run
    cp -R -- "$REPO_ROOT/fixtures/milestone-btb" "$workspace"
    build_smoke_payload branches
    local payload="$MILESTONE_WORK/payload/branches.elf"
    run_packaged_tool "$workspace" m5-btb-resolve resolve
    run_packaged_tool "$workspace" m5-btb-baseline run baseline "$payload"
    simulation_passed "$MILESTONE_ARTIFACTS/m5-btb-baseline.log"
    run=$(generated_run m5-btb-baseline)
    require_rtl_module "$run/chipyard/sims/verilator/generated-src" BTBBranchPredictorBank
    reject_rtl_module "$run/chipyard/sims/verilator/generated-src" MilestoneFixtureBTB

    run_packaged_tool "$workspace" m5-btb-custom run custom "$payload"
    simulation_passed "$MILESTONE_ARTIFACTS/m5-btb-custom.log"
    grep -F 'M5 custom BTB trained a taken branch' "$MILESTONE_ARTIFACTS/m5-btb-custom.log"
    run=$(generated_run m5-btb-custom)
    require_rtl_module "$run/chipyard/sims/verilator/generated-src" MilestoneFixtureBTB
    reject_rtl_module "$run/chipyard/sims/verilator/generated-src" BTBBranchPredictorBank

    require_simulation_manifest m5-btb-baseline "$workspace" baseline "$payload"
    require_simulation_manifest m5-btb-custom "$workspace" custom "$payload"
    local adapter="workspace:integrations/boom/src/main/scala/WithFixtureBTB.scala"
    jq -e --arg adapter "$adapter" '.sourceSelection.activeScalaSources | index($adapter) == null' \
        "$MILESTONE_ARTIFACTS/m5-btb-baseline.json" >/dev/null
    jq -e --arg adapter "$adapter" '.sourceSelection.activeScalaSources | index($adapter) != null' \
        "$MILESTONE_ARTIFACTS/m5-btb-custom.json" >/dev/null
    jq -e '.parameters | .. | objects | select(.sets == 64 and .ways == 4)' \
        "$MILESTONE_ARTIFACTS/m5-btb-custom.json" >/dev/null ||
        fail "The custom run manifest must record the effective BTB dimensions"
}

function unsupported_hook() {
    local workspace="$MILESTONE_WORK/m5-unsupported-hook"
    cp -R -- "$REPO_ROOT/fixtures/milestone-btb" "$workspace"
    run_packaged_tool "$workspace" m5-unsupported-resolve resolve
    if run_packaged_tool "$workspace" m5-unsupported compile unsupported; then
        fail "The replacement compiled against a host without the predictor hook"
    fi
    grep -F 'Unsupported integration: the fixture BTB requires a BOOM v3 predictor hook' \
        "$MILESTONE_ARTIFACTS/m5-unsupported.log"
}

case ${1-} in
    whole) whole_generator ;;
    replacement) replacement ;;
    unsupported) unsupported_hook ;;
    '')
        failures=0
        for check in whole replacement unsupported; do
            if ! bash "$0" "$check"; then
                printf 'M5 %s acceptance failed.\n' "$check" >&2
                failures=$((failures + 1))
            fi
        done
        ((failures == 0))
        ;;
    *) fail "Unknown M5 check: $1" ;;
esac
