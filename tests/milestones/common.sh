: "${REPO_ROOT:?Set REPO_ROOT to the repository checkout}"
: "${MILESTONE_WORK:?Set MILESTONE_WORK to a fresh scratch directory}"
: "${CHIPYARD_TOOLCHAIN_PREFIX:?Set CHIPYARD_TOOLCHAIN_PREFIX to the pinned toolchain}"

MILESTONE_ARTIFACTS="${MILESTONE_ARTIFACTS:-$MILESTONE_WORK/artifacts}"
MILESTONE_BUILD_JOBS="${MILESTONE_BUILD_JOBS:-2}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$MILESTONE_WORK/cache}"
unset USE_CHISEL7
mkdir -p -- "$MILESTONE_ARTIFACTS" "$XDG_CACHE_HOME"

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
    printf '%s  %s\n' "$expected" "$payload.identity" | sha256sum -c - | tee "$MILESTONE_ARTIFACTS/payload-checksum.log"
}

function build_reference_payload() {
    local checkout="$MILESTONE_WORK/payload-source" tests output="$MILESTONE_WORK/payload"
    tests="$checkout/toolchains/riscv-tools/riscv-tests"
    checkout_reference "$checkout"
    git -C "$checkout" submodule update --init --recursive --depth=1 -- toolchains/riscv-tools/riscv-tests
    mkdir -p -- "$output"
    PATH="$CHIPYARD_TOOLCHAIN_PREFIX/bin:$CHIPYARD_TOOLCHAIN_PREFIX/riscv-tools/bin:$PATH" \
        make -C "$output" -f "$tests/isa/Makefile" src_dir="$tests/isa" XLEN=64 rv64ui-p-simple
    check_reference_payload "$output/rv64ui-p-simple"
}

function generated_run() {
    sed -n 's/^\[info\] Generated Chipyard build: //p' "$MILESTONE_ARTIFACTS/$1.log"
}

function run_workspace() {
    local workspace=$1 label=$2 status=0 run
    shift 2
    (
        cd -- "$workspace"
        sbt -batch -Dsbt.color=false -Dsbt.supershell=false "set hardwareBuildJobs := $MILESTONE_BUILD_JOBS" "$@"
    ) > "$MILESTONE_ARTIFACTS/$label.log" 2>&1 || status=$?
    run=$(generated_run "$label")
    if [[ -f $run/run.json ]]; then
        cp -- "$run/run.json" "$MILESTONE_ARTIFACTS/$label.json"
    fi
    if (( status != 0 )); then
        tail -n 35 "$MILESTONE_ARTIFACTS/$label.log" >&2
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
