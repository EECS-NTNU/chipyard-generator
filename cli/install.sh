#!/bin/sh
set -eu

fail() {
    printf 'presolve install: %s\n' "$1" >&2
    exit 1
}

case ${1:-} in
    -h|--help)
        printf '%s\n' 'Usage: ./install.sh [--ivy-home DIRECTORY]' \
            'Install Presolve into ~/.local/bin and its plugin into the local sbt Ivy repository.'
        exit 0
        ;;
esac

ivy_home=$HOME/.ivy2
if [ "$#" -ne 0 ]; then
    [ "$#" -eq 2 ] && [ "$1" = --ivy-home ] && [ -n "$2" ] ||
        fail 'Usage: ./install.sh [--ivy-home DIRECTORY]'
    ivy_home=$2
fi

bundle=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
[ -f "$bundle/version" ] && [ -x "$bundle/presolve" ] &&
    [ -d "$bundle/lib" ] && [ -d "$bundle/ivy-local" ] ||
    fail 'Run this installer from the extracted Presolve archive.'
version=$(cat "$bundle/version")
case $version in
    ''|.|..|*[!a-zA-Z0-9._+-]*) fail 'Invalid Presolve release version.' ;;
esac

install_root=${XDG_DATA_HOME:-$HOME/.local/share}/presolve/releases
bin=$HOME/.local/bin
launcher=$bin/presolve
mkdir -p -- "$install_root" "$bin"
install_root=$(CDPATH= cd -- "$install_root" && pwd -P)
release=$install_root/$version

if [ -e "$launcher" ] || [ -L "$launcher" ]; then
    [ -L "$launcher" ] || fail "An existing launcher occupies $launcher."
    case $(readlink "$launcher") in
        "$install_root"/*/presolve) ;;
        *) fail "An existing launcher occupies $launcher." ;;
    esac
fi

"$bundle/presolve" --help >/dev/null
mkdir -p -- "$release/lib" "$ivy_home/local"
cp -R -- "$bundle/lib/." "$release/lib/"
cp -p -- "$bundle/presolve" "$release/presolve"
cp -R -- "$bundle/ivy-local/." "$ivy_home/local/"
ln -sfn -- "$release/presolve" "$launcher"

printf 'Installed Presolve at %s\n' "$launcher"
printf 'Installed the sbt plugin and resolver in %s/local\n' "$ivy_home"
printf 'Add %s to PATH if needed.\n' "$bin"
