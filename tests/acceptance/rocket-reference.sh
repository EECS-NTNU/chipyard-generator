#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

checkout="$ACCEPTANCE_WORK/chipyard"
reference="$REPO_ROOT/reference/chipyard-rocket"
log="$ACCEPTANCE_ARTIFACTS/reference.log"
printf '%s\n' 'Simulates the upstream Rocket baseline with the pinned reference payload.'
checkout_reference "$checkout"
(
    cd -- "$checkout"
    ./scripts/init-submodules-no-riscv-tools.sh
    git submodule update --init --recursive --depth=1 -- toolchains/riscv-tools/riscv-tests
) 2>&1 | tee "$ACCEPTANCE_ARTIFACTS/reference-submodules.log"

SLURM_CPUS_PER_TASK="$ACCEPTANCE_BUILD_JOBS" \
    bash "$reference/run.sh" "$checkout" "$CHIPYARD_TOOLCHAIN_PREFIX" "$ACCEPTANCE_WORK" 2>&1 | tee "$log"
check_reference_payload "$ACCEPTANCE_WORK/payload/rv64ui-p-simple"
simulation_passed "$log"
grep -xF 'REFERENCE PASS' "$log"

printf '%s\n' 'Matches the reference Chipyard submodule revisions and checkout states.'
awk '
    /^ / { print $2 "\t" $1 "\tchecked-out"; next }
    /^-/ { print $2 "\t" substr($1, 2) "\tuninitialized"; next }
    { print "Unexpected submodule status: " $0 > "/dev/stderr"; error = 1 }
    END { if (error) exit 1 }
' "$ACCEPTANCE_WORK/submodules.status" | LC_ALL=C sort > "$ACCEPTANCE_ARTIFACTS/submodules.tsv"
tail -n +2 "$reference/submodules.tsv" | LC_ALL=C sort > "$ACCEPTANCE_WORK/expected-submodules.tsv"
diff -u "$ACCEPTANCE_WORK/expected-submodules.tsv" "$ACCEPTANCE_ARTIFACTS/submodules.tsv"

printf '%s\n' 'Matches the pinned compiler, simulator, and hardware toolchain versions.'
grep -F 'version "20.0.2-internal"' "$log"
grep -Fx 'sbt.version=1.8.2' "$log"
grep -F 'Verilator 5.022' "$log"
grep -Fx 'CIRCT firtool-1.75.0' "$log"
grep -Fx 'riscv64-unknown-elf-gcc (gc891d8dc23e) 13.2.0' "$log"
grep -E '^g\+\+ .*13\.2\.0$' "$log"
grep -Fx 'GNU Make 4.3' "$log"
"$CHIPYARD_TOOLCHAIN_PREFIX/riscv-tools/bin/riscv64-unknown-elf-objcopy" --version > "$ACCEPTANCE_ARTIFACTS/objcopy.log"
grep -Fx 'GNU objcopy (GNU Binutils) 2.42' "$ACCEPTANCE_ARTIFACTS/objcopy.log"
grep -Fx 'val chisel6Version = "6.7.0"' "$checkout/build.sbt"
grep -E '^val scalaVersionFromChisel = .* else "2\.13\.16"$' "$checkout/build.sbt"
