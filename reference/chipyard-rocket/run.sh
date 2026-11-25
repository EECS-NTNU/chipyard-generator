#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 ]]; then
  echo "Usage: $0 CHIPYARD_CHECKOUT CHIPYARD_TOOLCHAIN_PREFIX OUTPUT_DIRECTORY" >&2
  exit 2
fi

chipyard=$(cd -- "$1" && pwd)
toolchain=$(cd -- "$2" && pwd)
mkdir -p -- "$3"
output=$(cd -- "$3" && pwd)

revision=a7946ede9bd625204435715dc40c750e2f8a251f
if [[ $(git -C "$chipyard" rev-parse HEAD) != "$revision" ]]; then
  echo "Expected Chipyard at $revision." >&2
  exit 1
fi

jobs=${SLURM_CPUS_PER_TASK:-12}
export RISCV="$toolchain/riscv-tools"
export PATH="$toolchain/bin:$RISCV/bin:$PATH"
export JAVA_HOME="$toolchain/lib/jvm"
export COURSIER_CACHE="$output/coursier"
export SBT_OPTS="-Dsbt.ivy.home=$output/ivy2 -Dsbt.global.base=$output/sbt -Dsbt.boot.directory=$output/sbt/boot -Dsbt.override.build.repos=true -Dsbt.repository.config=$output/repositories -Dsbt.supershell=false -Dsbt.color=false -Dsbt.server.autostart=false"
export JAVA_TOOL_OPTIONS="-Xmx14G -Xss8M -XX:ActiveProcessorCount=$jobs -Djava.io.tmpdir=$output/java-tmp"
unset USE_CHISEL7

mkdir -p "$output/payload" "$output/java-tmp" "$output/ivy2/local"
cat > "$output/repositories" <<'REPOSITORIES'
[repositories]
local
maven-central: https://repo.maven.apache.org/maven2
sbt-plugin-releases: https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
REPOSITORIES

java -version
cat "$chipyard/project/build.properties"
verilator --version
firtool --version
riscv64-unknown-elf-gcc --version | sed -n '1p'
g++ --version | sed -n '1p'
make --version | sed -n '1p'
git -C "$chipyard" submodule status --recursive > "$output/submodules.status"

tests="$chipyard/toolchains/riscv-tools/riscv-tests"
make -C "$output/payload" -f "$tests/isa/Makefile" src_dir="$tests/isa" XLEN=64 rv64ui-p-simple
payload="$output/payload/rv64ui-p-simple"
riscv64-unknown-elf-objcopy --strip-debug "$payload" "$output/payload/rv64ui-p-simple.identity"
sha256sum "$output/payload/rv64ui-p-simple.identity"

make -C "$chipyard/sims/verilator" -j"$jobs" CONFIG=RocketConfig
make -C "$chipyard/sims/verilator" CONFIG=RocketConfig RANDOM_SEED=1 run-binary-fast BINARY="$payload"
printf 'REFERENCE PASS\n'
