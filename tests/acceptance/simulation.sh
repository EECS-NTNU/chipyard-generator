function build_smoke_payload() {
    local source="$REPO_ROOT/tests/acceptance/payloads" name=$1
    local riscv_bin="$CHIPYARD_TOOLCHAIN_PREFIX/riscv-tools/bin"
    mkdir -p -- "$ACCEPTANCE_WORK/payload"
    PATH="$riscv_bin:$PATH" "$riscv_bin/riscv64-unknown-elf-gcc" \
        -march=rv64imac_zicsr -mabi=lp64 -nostdlib -static \
        -Wl,--build-id=none,-T,"$source/htif.ld" \
        "$source/$name.S" -o "$ACCEPTANCE_WORK/payload/$name.elf"
}

function run_packaged_tool() {
    local workspace=$1 label=$2 status=0
    shift 2
    (
        cd -- "$workspace" || exit 1
        # Chipyard's TestDriver gates Chisel printf on the simulator's verbose flag.
        EXTRA_SIM_FLAGS="${EXTRA_SIM_FLAGS:-} +verbose" \
        JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:-} -XX:ActiveProcessorCount=$ACCEPTANCE_BUILD_JOBS -Dsbt.color=false -Dsbt.supershell=false" \
            presolve "$@"
    ) > "$ACCEPTANCE_ARTIFACTS/$label.log" 2>&1 || status=$?
    collect_run_manifests "$label"
    if (( status != 0 )); then
        tail -n 35 "$ACCEPTANCE_ARTIFACTS/$label.log" >&2
    fi
    return "$status"
}

function require_simulation_manifest() {
    local label=$1 workspace=$2 experiment=$3 payload=$4 manifest lock_hash payload_hash
    manifest="$ACCEPTANCE_ARTIFACTS/$label.json"
    lock_hash=$(sha256sum < "$workspace/hardware.lock" | cut -d ' ' -f 1)
    payload_hash=$(sha256sum < "$payload" | cut -d ' ' -f 1)
    jq -e --arg experiment "$experiment" --arg payload "$payload_hash" --arg build "$(generated_run "$label")" '
      .experiment == $experiment and .buildDirectory == $build and
      .backend == "verilator-simulate" and .payload.sha256 == $payload and .payload.randomSeed == 1
    ' "$manifest" >/dev/null || fail "Incomplete simulation manifest for $label"
    jq -e --arg experiment "$experiment" --arg lock "$lock_hash" '
      .experiment == $experiment and .lockFingerprint == $lock and .backend == "verilator-build" and
      (.toolchain.jdk | length > 0) and
      (.toolchain.verilator | length > 0) and
      (.sourceSelection.activeScalaSources | index("config/" + $experiment + "/System.scala") != null)
    ' "$ACCEPTANCE_ARTIFACTS/$label.build.json" >/dev/null || fail "Incomplete simulator build manifest for $label"
}
