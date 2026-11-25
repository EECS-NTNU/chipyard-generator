function build_smoke_payload() {
    local source="$REPO_ROOT/tests/milestones/payloads" name=$1
    local riscv_bin="$CHIPYARD_TOOLCHAIN_PREFIX/riscv-tools/bin"
    mkdir -p -- "$MILESTONE_WORK/payload"
    PATH="$riscv_bin:$PATH" "$riscv_bin/riscv64-unknown-elf-gcc" \
        -march=rv64imac_zicsr -mabi=lp64 -nostdlib -static \
        -Wl,--build-id=none,-T,"$source/htif.ld" \
        "$source/$name.S" -o "$MILESTONE_WORK/payload/$name.elf"
}

function run_packaged_tool() {
    local workspace=$1 label=$2 status=0 run
    shift 2
    (
        cd -- "$workspace" || exit 1
        # Chipyard's TestDriver gates Chisel printf on the simulator's verbose flag.
        EXTRA_SIM_FLAGS="${EXTRA_SIM_FLAGS:-} +verbose" \
        JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:-} -XX:ActiveProcessorCount=$MILESTONE_BUILD_JOBS -Dsbt.color=false -Dsbt.supershell=false" \
            presolve "$@"
    ) > "$MILESTONE_ARTIFACTS/$label.log" 2>&1 || status=$?
    run=$(generated_run "$label")
    if [[ -n $run && -f $run/run.json ]]; then
        cp -- "$run/run.json" "$MILESTONE_ARTIFACTS/$label.json"
    fi
    if (( status != 0 )); then
        tail -n 35 "$MILESTONE_ARTIFACTS/$label.log" >&2
    fi
    return "$status"
}

function require_simulation_manifest() {
    local label=$1 workspace=$2 experiment=$3 payload=$4 manifest lock_hash payload_hash
    manifest="$MILESTONE_ARTIFACTS/$label.json"
    lock_hash=$(sha256sum < "$workspace/hardware.lock" | cut -d ' ' -f 1)
    payload_hash=$(sha256sum < "$payload" | cut -d ' ' -f 1)
    jq -e --arg experiment "$experiment" --arg lock "$lock_hash" --arg payload "$payload_hash" '
      .experiment == $experiment and .lockFingerprint == $lock and
      .backend == "verilator-simulate" and .payload.sha256 == $payload and
      .payload.randomSeed == 1 and (.toolchain.jdk | length > 0) and
      (.toolchain.verilator | length > 0) and
      (.sourceSelection.activeScalaSources | index("config/" + $experiment + "/System.scala") != null)
    ' "$manifest" >/dev/null || fail "Incomplete simulation manifest for $label"
}
