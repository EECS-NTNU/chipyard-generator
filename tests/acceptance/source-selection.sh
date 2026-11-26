#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

selection="$ACCEPTANCE_WORK/selection"
standalone="$ACCEPTANCE_WORK/standalone"
cached_host="$ACCEPTANCE_WORK/cached-host"
cp -R -- "$REPO_ROOT/fixtures/select" "$selection"
cp -R -- "$REPO_ROOT/fixtures/select" "$standalone"
cp -R -- "$REPO_ROOT/fixtures/build-git" "$cached_host"
rm -rf -- "$standalone/config"

printf '%s\n' 'Elaborates the canonical workspace module without any system configuration.'
run_workspace "$standalone" standalone-resolve hardwareResolve
cp -- "$standalone/hardware.lock" "$ACCEPTANCE_ARTIFACTS/standalone.lock"
run_workspace "$standalone" standalone hardwareElaborateStandalone
standalone_run=$(generated_run standalone)
require_rtl_module "$standalone_run/rtl" SelectedCounter
grep -r -E -m 1 '\[10:0\]' --include='*.sv' "$standalone_run/rtl"
jq -e '.experiment == null and .configuration == "selectfixture.Module" and .backend == "circt-standalone"' \
    "$ACCEPTANCE_ARTIFACTS/standalone.json"
cmp -- "$standalone/hardware.lock" "$ACCEPTANCE_ARTIFACTS/standalone.lock"
rm -rf -- "$standalone_run"

scaffold="$ACCEPTANCE_WORK/scaffold-standalone"
printf '%s\n' 'Scaffolds a new workspace and elaborates its default standalone module.'
presolve create-project "$scaffold" > "$ACCEPTANCE_ARTIFACTS/scaffold.log" 2>&1
rm -rf -- "$scaffold/config"
run_workspace "$scaffold" scaffold-resolve hardwareResolve
run_workspace "$scaffold" scaffold-standalone hardwareElaborateStandalone
scaffold_run=$(generated_run scaffold-standalone)
require_rtl_module "$scaffold_run/rtl" Module
grep -r -E -m 1 '\[7:0\]' --include='*.sv' "$scaffold_run/rtl"
rm -rf -- "$scaffold_run"

run_workspace "$selection" selection-resolve hardwareResolve
cp -- "$selection/hardware.lock" "$ACCEPTANCE_ARTIFACTS/selection.lock"

function check_baseline() {
    local label=$1 run rtl
    run_workspace "$selection" "$label" 'hardwareElaborate baseline'
    run=$(generated_run "$label")
    rtl="$run/chipyard/sims/verilator/generated-src"
    require_rtl_module "$rtl" Rocket
    reject_rtl_module "$rtl" SelectedCounter
    reject_rtl_module "$rtl" PassiveCounter
    unzip -Z1 "$run/chipyard/.classpath_cache/hardwareSystem.jar" > "$ACCEPTANCE_ARTIFACTS/$label.classpath"
    if grep -E '^(selectfixture|fixturedep)/integrations/' "$ACCEPTANCE_ARTIFACTS/$label.classpath"; then
        fail "Optional integration classes leaked into the baseline"
    fi
    jq -e '
        .experiment == "baseline" and .configuration == "selectfixture.System" and
        (.sourceSelection.integrations | all(.selectedBy == null)) and
        (.sourceSelection.activeScalaSources | map(select(startswith("config/"))) == ["config/baseline/System.scala"])
    ' "$ACCEPTANCE_ARTIFACTS/$label.json"
    cmp -- "$selection/hardware.lock" "$ACCEPTANCE_ARTIFACTS/selection.lock"
    rm -rf -- "$run"
}

printf '%s\n' 'Builds the baseline while excluding unselected experiments and broken or unavailable adapters.'
check_baseline baseline
printf '%s\n' 'Selects integrations recursively through a configuration helper.'
run_workspace "$selection" selected 'hardwareElaborate selected'
selected_run=$(generated_run selected)
selected_rtl="$selected_run/chipyard/sims/verilator/generated-src"
require_rtl_module "$selected_rtl" SelectedCounter
selected_counter=$(find "$selected_rtl" -name SelectedCounter.sv -print -quit)
grep -E -m 1 '\[12:0\]' "$selected_counter"
printf '%s\n' 'Compiles an explicitly imported adapter without elaborating its unused hardware.'
reject_rtl_module "$selected_rtl" PassiveCounter
unzip -Z1 "$selected_run/chipyard/.classpath_cache/hardwareSystem.jar" > "$ACCEPTANCE_ARTIFACTS/selected.classpath"
grep -Fx 'selectfixture/integrations/passive/PassiveCounter.class' "$ACCEPTANCE_ARTIFACTS/selected.classpath"
jq -e '
    .experiment == "selected" and .configuration == "selectfixture.System" and
    (.sourceSelection.activeScalaSources | index("config/baseline/System.scala") == null) and
    (.sourceSelection.integrations | map(select(.selectedBy != null) |
        {key: .package, value: .selectedBy.source}) | from_entries == {
            "fixturedep.integrations.extra": "workspace:integrations/leaf/src/main/scala/Pulse.scala",
            "selectfixture.integrations.activate": "config/selected/SelectedConfig.scala",
            "selectfixture.integrations.leaf": "workspace:integrations/activate/src/main/scala/WithCounter.scala",
            "selectfixture.integrations.passive": "config/selected/SelectedConfig.scala"
        })
' "$ACCEPTANCE_ARTIFACTS/selected.json"
cmp -- "$selection/hardware.lock" "$ACCEPTANCE_ARTIFACTS/selection.lock"
rm -rf -- "$selected_run"

printf '%s\n' 'Switches back to the baseline with the same lockfile and no leaked adapter classes.'
check_baseline baseline-warm
jq -e --slurp '.[0] == .[1]' "$ACCEPTANCE_ARTIFACTS/baseline.json" "$ACCEPTANCE_ARTIFACTS/baseline-warm.json"
LC_ALL=C sort "$ACCEPTANCE_ARTIFACTS/baseline.classpath" > "$ACCEPTANCE_WORK/baseline-classpath.sorted"
LC_ALL=C sort "$ACCEPTANCE_ARTIFACTS/baseline-warm.classpath" > "$ACCEPTANCE_WORK/warm-classpath.sorted"
cmp -- "$ACCEPTANCE_WORK/baseline-classpath.sorted" "$ACCEPTANCE_WORK/warm-classpath.sorted"

function expect_compile_failure() {
    local label=$1 diagnostic=$2 run
    shift 2
    if run_workspace "$selection" "$label" "$@"; then
        fail "Expected $label to fail compilation"
    fi
    grep -F -m 1 "$diagnostic" "$ACCEPTANCE_ARTIFACTS/$label.log" || fail "Missing $label diagnostic: $diagnostic"
    run=$(generated_run "$label")
    [[ -n $run && -f $run/run.json ]] || fail "No run manifest for $label"
    cmp -- "$selection/hardware.lock" "$ACCEPTANCE_ARTIFACTS/selection.lock"
    rm -rf -- "$run"
}

printf '%s\n' 'Reports the compiler error when a named import selects a broken adapter.'
expect_compile_failure broken 'not found: type UnavailableType' 'hardwareElaborate broken'

printf '%s\n' 'Rejects an undeclared host with a cold cache and leaves the source cache unchanged.'
bash "$cached_host/prepare.sh"
source_cache="$XDG_CACHE_HOME/hardware-generators/sources"
find "$source_cache" -name hardware-source-v1 -print | LC_ALL=C sort > "$ACCEPTANCE_WORK/sources-before"
expect_compile_failure uncached-host 'not found: object gitcounter' 'hardwareElaborate missing_host'
find "$source_cache" -name hardware-source-v1 -print | LC_ALL=C sort > "$ACCEPTANCE_WORK/sources-after"
cmp -- "$ACCEPTANCE_WORK/sources-before" "$ACCEPTANCE_WORK/sources-after"

printf '%s\n' 'Rejects the same undeclared host after another workspace has cached its checkout.'
run_workspace "$cached_host" cache-host hardwareResolve
host_plan=$(find "$cached_host/target/hardware" -name plan.json -print -quit)
host_checkout=$(jq -er '.nodes[] | select(.id == "git-counter") | .source.checkout.directory' "$host_plan")
[[ -f $host_checkout/src/main/scala/GitCounter.scala ]] || fail 'The undeclared Git generator was not prefetched'
[[ $host_checkout == "$source_cache/"* ]] || fail 'The host was prefetched into a different source cache'
if grep -Fxq -- "$host_checkout/.git/hardware-source-v1" "$ACCEPTANCE_WORK/sources-before"; then
    fail 'The host checkout was already cached before the cold-cache test'
fi
expect_compile_failure undeclared-host 'not found: object gitcounter' \
    'set hardwareOffline := true' 'hardwareElaborate missing_host'
