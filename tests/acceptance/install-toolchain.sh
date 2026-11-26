#!/usr/bin/env bash
set -euo pipefail

prefix=$1
revision=$(cat "$2")
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT

# Use this upstream Conda lock for the toolchain and the pinned revision for Chipyard sources.
lock_name=conda-requirements-riscv-tools-linux-64-lean.conda-lock.yml
lock_revision=dbc082e2206f787c3aba12b9b171e1704e15b707
lock_sha=1e2a664d9948f9ba30f51a67339f38f7cdb4d7f9d1bcb7acb84fccab97ad0d05
curl --fail --silent --show-error --location --retry 3 \
    "https://raw.githubusercontent.com/ucb-bar/chipyard/$lock_revision/conda-reqs/conda-lock-reqs/$lock_name" \
    --output "$scratch/conda-lock.yml"
printf '%s  %s\n' "$lock_sha" "$scratch/conda-lock.yml" | sha256sum -c -
/usr/bin/python3 - "$scratch/conda-lock.yml" > "$scratch/conda-explicit.txt" <<'PYTHON'
import sys

import yaml

with open(sys.argv[1], encoding="utf-8") as lock:
    packages = yaml.safe_load(lock)["package"]

print("@EXPLICIT")
for package in packages:
    if package["manager"] != "conda" or package["platform"] != "linux-64":
        raise ValueError(f"Unexpected package in the reference lock: {package['name']}")
    print(package["url"] + "#" + package["hash"]["md5"])
PYTHON

conda create --yes --quiet --no-default-packages --prefix "$prefix" --file "$scratch/conda-explicit.txt"

checkout="$scratch/chipyard"
git init -q "$checkout"
git -C "$checkout" remote add origin https://github.com/ucb-bar/chipyard.git
git -C "$checkout" fetch --depth=1 origin "$revision"
git -C "$checkout" checkout --detach FETCH_HEAD
spike=toolchains/riscv-tools/riscv-isa-sim
git -C "$checkout" submodule update --init --depth=1 -- "$spike"

export RISCV="$prefix/riscv-tools"
export PATH="$prefix/bin:$RISCV/bin:$PATH"
mkdir -- "$scratch/spike-build"
(
    cd -- "$scratch/spike-build"
    CC="$prefix/bin/gcc" CXX="$prefix/bin/g++" "$checkout/$spike/configure" \
        --prefix="$RISCV" --with-boost=no --with-boost-asio=no --with-boost-regex=no
    jobs=$(nproc)
    if (( jobs > 12 )); then jobs=12; fi
    make -j"$jobs" libfesvr.a libriscv.so install-hdrs install-config-hdrs
    install -m 644 libfesvr.a libriscv.so "$RISCV/lib/"
)
firtool=$(jq -er '.version' "$checkout/conda-reqs/circt.json")
firtool_sha=5c822c07268d20cb85f4488a416cd62b983d33c13ffa9a5b5656ce5078d6475c
curl --fail --silent --show-error --location --retry 3 \
    "https://github.com/llvm/circt/releases/download/$firtool/firrtl-bin-linux-x64.tar.gz" \
    --output "$scratch/firtool.tar.gz"
printf '%s  %s\n' "$firtool_sha" "$scratch/firtool.tar.gz" | sha256sum -c -
tar -xzf "$scratch/firtool.tar.gz" -C "$RISCV/bin" --strip-components=2 "$firtool/bin/firtool"

/opt/miniforge/bin/conda clean --all --yes
