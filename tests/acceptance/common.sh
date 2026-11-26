: "${REPO_ROOT:?Set REPO_ROOT to the repository checkout}"
: "${ACCEPTANCE_WORK:?Set ACCEPTANCE_WORK to a fresh scratch directory}"
: "${CHIPYARD_TOOLCHAIN_PREFIX:?Set CHIPYARD_TOOLCHAIN_PREFIX to the pinned toolchain}"

ACCEPTANCE_ARTIFACTS="${ACCEPTANCE_ARTIFACTS:-$ACCEPTANCE_WORK/artifacts}"
ACCEPTANCE_BUILD_JOBS="${ACCEPTANCE_BUILD_JOBS:-2}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$ACCEPTANCE_WORK/cache}"
unset USE_CHISEL7
mkdir -p -- "$ACCEPTANCE_ARTIFACTS" "$XDG_CACHE_HOME"

function fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

function checkout_reference() {
    local checkout=$1 revision
    revision=$(cat "$REPO_ROOT/cli/src/main/resources/chipyard-revision")
    git init -q "$checkout"
    git -C "$checkout" remote add origin https://github.com/ucb-bar/chipyard.git
    git -C "$checkout" fetch --depth=1 origin "$revision"
    git -C "$checkout" checkout --detach FETCH_HEAD
}

function check_reference_payload() {
    local payload=$1 expected
    expected=e3b197523cb9335246d472affe658d92e5b6240a2faec3699c454cbae0e9bf54
    "$CHIPYARD_TOOLCHAIN_PREFIX/riscv-tools/bin/riscv64-unknown-elf-objcopy" --strip-debug "$payload" "$payload.identity"
    printf '%s  %s\n' "$expected" "$payload.identity" | sha256sum -c - | tee "$ACCEPTANCE_ARTIFACTS/payload-checksum.log"
}

function build_reference_payload() {
    local checkout="$ACCEPTANCE_WORK/payload-source" tests output="$ACCEPTANCE_WORK/payload"
    tests="$checkout/toolchains/riscv-tools/riscv-tests"
    checkout_reference "$checkout"
    git -C "$checkout" submodule update --init --recursive --depth=1 -- toolchains/riscv-tools/riscv-tests
    mkdir -p -- "$output"
    PATH="$CHIPYARD_TOOLCHAIN_PREFIX/bin:$CHIPYARD_TOOLCHAIN_PREFIX/riscv-tools/bin:$PATH" \
        make -C "$output" -f "$tests/isa/Makefile" src_dir="$tests/isa" XLEN=64 rv64ui-p-simple
    check_reference_payload "$output/rv64ui-p-simple"
}

function generated_run() {
    sed -n -e 's/^\[info\] Generated Chipyard build: //p' -e 's/^\[info\] Using Chipyard build: //p' \
        "$ACCEPTANCE_ARTIFACTS/$1.log"
}

function collect_run_manifests() {
    local label=$1 build run
    build=$(generated_run "$label")
    run=$(sed -n 's/^\[info\] Generated Chipyard run: //p' "$ACCEPTANCE_ARTIFACTS/$label.log")
    if [[ -n $run && -f $run/run.json ]]; then
        cp -- "$build/run.json" "$ACCEPTANCE_ARTIFACTS/$label.build.json"
        cp -- "$run/run.json" "$ACCEPTANCE_ARTIFACTS/$label.json"
    elif [[ -n $build && -f $build/run.json ]]; then
        cp -- "$build/run.json" "$ACCEPTANCE_ARTIFACTS/$label.json"
    fi
}

function run_workspace() {
    local workspace=$1 label=$2 status=0
    shift 2
    (
        cd -- "$workspace"
        sbt -batch -Dsbt.color=false -Dsbt.supershell=false "set hardwareBuildJobs := $ACCEPTANCE_BUILD_JOBS" "$@"
    ) > "$ACCEPTANCE_ARTIFACTS/$label.log" 2>&1 || status=$?
    collect_run_manifests "$label"
    if (( status != 0 )); then
        tail -n 35 "$ACCEPTANCE_ARTIFACTS/$label.log" >&2
    fi
    return "$status"
}

function require_rtl_module() {
    local rtl=$1 module=$2
    grep -r -q -E --include='*.sv' --include='*.v' "^[[:space:]]*module[[:space:]]+$module([[:space:](#;]|$)" "$rtl" ||
        fail "Expected RTL module $module under $rtl"
}

function reject_rtl_module() {
    local rtl=$1 module=$2
    [[ -d $rtl ]] || fail "Missing RTL directory $rtl"
    if grep -r -q -E --include='*.sv' --include='*.v' "^[[:space:]]*module[[:space:]]+$module([[:space:](#;]|$)" "$rtl"; then
        fail "Unexpected RTL module $module under $rtl"
    fi
}

function simulation_passed() {
    local log=$1
    grep -F 'Verilog $finish' "$log" || fail "Simulation did not reach Verilog \$finish"
    if grep -F '*** FAILED ***' "$log"; then
        fail "The simulated program signalled failure"
    fi
}
