#!/usr/bin/env bash
set -euo pipefail

if [[ ${1:-} == --help || ${1:-} == -h ]]; then
    printf '%s\n' 'Usage: bash cli/setup.sh' 'Interactively install presolve and configure a Chipyard toolchain.'
    exit 0
fi
if (( $# )); then
    printf '%s\n' 'Usage: bash cli/setup.sh' >&2
    exit 2
fi

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
stage="$repo/cli/target/stage"
activation="$repo/cli/target/presolve-env.sh"
revision=$(<"$repo/cli/src/main/resources/chipyard-revision")
toolchain=''

step() { printf '\n%s\n' "$1"; }
fail() { printf 'presolve setup: %s\n' "$1" >&2; exit 1; }

confirm() {
    local answer
    read -r -p "$1 [y/N] " answer && [[ $answer == [yY] || $answer == [yY][eE][sS] ]]
}

check_toolchain() {
    local prefix=$1 executable version
    if [[ ! $prefix =~ ^[A-Za-z0-9_./+@-]+$ ]]; then
        printf 'Choose a toolchain path without spaces or shell metacharacters.\n' >&2
        return 1
    fi
    for executable in bin/java bin/make bin/g++ bin/verilator lib/jvm/bin/java riscv-tools/bin/firtool; do
        if [[ ! -x $prefix/$executable ]]; then
            printf 'Missing toolchain executable %s\n' "$prefix/$executable" >&2
            return 1
        fi
    done
    for executable in riscv-tools/lib/libfesvr.a; do
        if [[ ! -f $prefix/$executable ]]; then
            printf 'Missing toolchain library %s\n' "$prefix/$executable" >&2
            return 1
        fi
    done
    version=$("$prefix/bin/java" -version 2>&1) && [[ $version =~ version\ \"20[.\"] ]] || {
        printf 'Chipyard requires JDK 20 at %s/bin/java.\n' "$prefix" >&2
        return 1
    }
    version=$("$prefix/bin/verilator" --version 2>&1) && [[ $version =~ ^Verilator\ 5\.022([[:space:]]|$) ]] || {
        printf 'Chipyard requires Verilator 5.022 at %s/bin/verilator.\n' "$prefix" >&2
        return 1
    }
    version=$("$prefix/riscv-tools/bin/firtool" --version 2>&1) && [[ $version =~ CIRCT\ firtool-1\.75\.0([[:space:]]|$) ]] || {
        printf 'Chipyard requires firtool-1.75.0 at %s/riscv-tools/bin/firtool.\n' "$prefix" >&2
        return 1
    }
}

use_toolchain() {
    local prefix
    prefix=$(cd -- "$1" && pwd -P) && check_toolchain "$prefix" || return 1
    toolchain=$prefix
}

install_toolchain() {
    local conda_exe destination default_destination
    if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
        printf 'Automatic Chipyard toolchain installation is supported on Linux x86-64.\n' >&2
        return 1
    fi
    conda_exe=${CONDA_EXE:-$(type -P conda || true)}
    if [[ ! -x $conda_exe ]]; then
        printf 'Install Conda and make it available on PATH, then select install again.\n' >&2
        printf 'Conda instructions are linked from %s/reference/chipyard-rocket/README.md.\n' "$repo" >&2
        return 1
    fi
    default_destination="${XDG_DATA_HOME:-$HOME/.local/share}/presolve/chipyard-$revision"
    read -r -p "Chipyard checkout directory [$default_destination]: " destination || return 1
    destination=${destination:-$default_destination}
    [[ $destination == /* ]] || destination="$PWD/$destination"
    if [[ ! $destination =~ ^[A-Za-z0-9_./+@-]+$ ]]; then
        printf 'Choose an installation directory without spaces or shell metacharacters.\n' >&2
        return 1
    fi
    if [[ -e $destination || -L $destination ]]; then
        if [[ $(git -C "$destination" rev-parse --show-toplevel 2>/dev/null) == "$destination" &&
              $(git -C "$destination" rev-parse HEAD 2>/dev/null) == "$revision" ]] &&
              use_toolchain "$destination/.conda-env"; then
            return 0
        fi
        printf 'Installation directory already exists. Choose a new location or use an existing toolchain.\n' >&2
        return 1
    fi
    printf 'Chipyard setup downloads Conda packages and builds RISC-V tools in %s.\n' "$destination"
    confirm 'Start the toolchain installation?' || return 1
    mkdir -p -- "$(dirname -- "$destination")" &&
        mkdir -- "$destination" &&
        git -C "$destination" init &&
        git -C "$destination" fetch --depth=1 https://github.com/ucb-bar/chipyard.git "$revision" &&
        git -C "$destination" checkout --detach FETCH_HEAD &&
        (
            cd -- "$destination" &&
            export CONDA_EXE="$conda_exe" PATH="$(dirname -- "$conda_exe"):$PATH" &&
            ./build-setup.sh riscv-tools --skip-ctags --skip-precompile --skip-marshal --skip-clean
        ) &&
        use_toolchain "$destination/.conda-env" || {
        printf 'Toolchain installation did not complete. See the output above and any logs in %s.\n' "$destination" >&2
        return 1
    }
}

step '1/4 Check prerequisites'
command -v git >/dev/null || fail 'Install Git and add it to PATH, then rerun setup.'
command -v sbt >/dev/null || fail 'Install sbt and add it to PATH, then rerun setup.'
javac_version=$("${JAVA_HOME:+$JAVA_HOME/bin/}javac" -version 2>&1) ||
    fail 'Install a JDK (11 or newer) and set JAVA_HOME or add it to PATH, then rerun setup.'
[[ $javac_version =~ javac\ ([0-9]+) && ${BASH_REMATCH[1]} -ge 11 ]] ||
    fail "A JDK of version 11 or newer is required. Found $javac_version."
printf 'Found Git, sbt, and %s.\n' "$javac_version"

step '2/4 Publish the plugin and stage presolve'
(cd -- "$repo" && sbt publishLocal cli/stage) || fail 'sbt setup failed. Address the error above and rerun setup.'
"$stage/presolve" --help >/dev/null || fail 'The staged presolve launcher failed its startup check.'
printf 'Presolve is staged at %s.\n' "$stage"

step '3/4 Configure the Chipyard toolchain'
if [[ -n ${CHIPYARD_TOOLCHAIN_PREFIX:-} ]] && use_toolchain "$CHIPYARD_TOOLCHAIN_PREFIX"; then
    printf 'Found the configured toolchain at %s.\n' "$toolchain"
fi
while [[ -z $toolchain ]]; do
    read -r -p 'Use an existing toolchain [e], install with Conda [i], or skip for now [s]? [s] ' choice || break
    case $choice in
        e|E)
            read -r -p 'Existing toolchain directory: ' prefix || break
            if [[ -n $prefix ]]; then use_toolchain "$prefix" || true; fi
            ;;
        i|I) install_toolchain || true ;;
        s|S|'') break ;;
        *) printf 'Enter e, i, or s.\n' ;;
    esac
done

activation_tmp=$(mktemp "$activation.XXXXXX")
trap 'rm -f -- "$activation_tmp"' EXIT
{
    printf 'export PATH=%q:"$PATH"\n' "$stage"
    if [[ -n $toolchain ]]; then
        printf 'export CHIPYARD_TOOLCHAIN_PREFIX=%q\n' "$toolchain"
    fi
} > "$activation_tmp"
mv -f -- "$activation_tmp" "$activation"
if [[ -n $toolchain ]]; then
    printf 'Using Chipyard toolchain at %s.\n' "$toolchain"
else
    printf 'You can create and resolve workspaces now. Set CHIPYARD_TOOLCHAIN_PREFIX before compiling or simulating.\n'
fi

step '4/4 Create your first workspace'
workspace=''
if confirm 'Create a workspace now?'; then
    if read -r -p 'Workspace directory [my-accelerator]: ' workspace; then
        workspace=${workspace:-my-accelerator}
        "$stage/presolve" create-project "$workspace" || workspace=''
        if [[ -n $workspace ]] && confirm 'Run presolve resolve now? This may download Chipyard sources.'; then
            (cd -- "$workspace" && "$stage/presolve" resolve) ||
                printf 'Resolution did not complete. Retry presolve resolve from your workspace.\n' >&2
        fi
    fi
fi
printf '\nActivate presolve in your current shell\n  source %q\n' "$activation"
if [[ -n $workspace ]]; then
    printf '  cd -- %q\n' "$workspace"
else
    printf '  presolve create-project my-accelerator\n  cd my-accelerator\n'
fi
printf '  presolve resolve\n  presolve compile baseline\n  presolve run baseline ./program.elf\n'
